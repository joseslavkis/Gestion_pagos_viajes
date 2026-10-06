package com.agencia.pagos.payment;

import com.agencia.pagos.TestcontainersConfiguration;
import com.agencia.pagos.shared.money.Currency;
import com.agencia.pagos.testsupport.ControllerIntegrationTestSupport;
import com.agencia.pagos.trip.Installment;
import com.agencia.pagos.trip.Trip;
import com.agencia.pagos.user.Student;
import com.agencia.pagos.user.User;
import com.agencia.pagos.user.dto.UserCreateDTO;
import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.HttpURLConnection;
import java.math.BigDecimal;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "server.address=127.0.0.1",
        "spring.servlet.multipart.max-file-size=5MB",
        "spring.servlet.multipart.max-request-size=30MB",
        "app.storage.receipts.provider=filesystem",
        "app.storage.receipts.filesystem.public-base-url=http://127.0.0.1"
})
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
class PaymentHttpTransportIntegrationTest extends ControllerIntegrationTestSupport {

    private static final int FILE_LIMIT = 5 * 1024 * 1024;
    private static final String BOUNDARY = "PaymentTransportRegressionBoundary";
    private static final String PAYMENT_DATE = LocalDate.now().minusDays(1).toString();

    @TempDir
    static Path receiptDirectory;

    @DynamicPropertySource
    static void receiptStorage(DynamicPropertyRegistry registry) {
        registry.add("app.storage.receipts.filesystem.base-path", () -> receiptDirectory.toString());
    }

    @LocalServerPort
    private int port;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private final HttpClient client = HttpClient.newBuilder()
            .version(HttpClient.Version.HTTP_1_1)
            .connectTimeout(Duration.ofSeconds(10))
            .build();
    private String userToken;
    private Long installmentId;
    private Long bankAccountId;

    @BeforeEach
    void seedAuthenticatedPayment() throws Exception {
        // Match the inherited disposable-DB reset with a reset of this class's own receipt files.
        try (Stream<Path> paths = Files.walk(receiptDirectory)) {
            for (Path file : paths.filter(Files::isRegularFile).toList()) {
                Files.delete(file);
            }
        }
        UserCreateDTO participant = buildValidUser("http-transport");
        userToken = signUp(participant).accessToken();
        User user = userRepository.findByEmail(participant.email()).orElseThrow();
        Student student = studentRepository.findByParentId(user.getId()).getFirst();
        Trip trip = seedPendingTrip("http-transport", List.of());
        trip.getAssignedUsers().add(user);
        tripRepository.save(trip);

        Installment installment = new Installment();
        installment.setTrip(trip);
        installment.setUser(user);
        installment.setStudent(student);
        installment.setInstallmentNumber(1);
        installment.setDueDate(LocalDate.now().plusDays(7));
        installment.setCapitalAmount(new BigDecimal("100.00"));
        installmentId = installmentRepository.saveAndFlush(installment).getId();
        bankAccountId = bankAccountRepository.saveAndFlush(BankAccount.builder()
                .bankName("Synthetic Bank").accountLabel("Transport ARS").accountHolder("Synthetic Holder")
                .accountNumber("local-transport").taxId("30-71131646-5").cbu("1000000000000000000000")
                .alias("TRANSPORT.ARS").currency(Currency.ARS).active(true).displayOrder(1).build()).getId();
    }

    @Test
    void fileAboveActualServletLimitReturns413WithoutSideEffects() throws Exception {
        assertRejected(paymentFields("10.00"), 1, FILE_LIMIT + 1, false, 413,
                "Los archivos adjuntos superan el tamaño permitido. Reduzca su tamaño e intente nuevamente.");
    }

    @Test
    void legacyAliasAboveActualRequestLimitReturns413WithoutSideEffects() throws Exception {
        assertRejected(paymentFields("10.00"), 5, FILE_LIMIT, true, 413,
                "Los archivos adjuntos superan el tamaño permitido. Reduzca su tamaño e intente nuevamente.");
    }

    @ParameterizedTest
    @CsvSource({
            "paymentCurrency, EUR",
            "paymentMethod, INVALID",
            "reportedPaymentDate, bad-date",
            "reportedAmount, abc",
            "bankAccountId, bad-id"
    })
    void invalidTypedParameterReturns400WithoutSideEffects(String parameter, String value) throws Exception {
        Map<String, String> fields = paymentFields("10.00");
        fields.put(parameter, value);
        assertRejected(fields, 1, 64, true, 400,
                "Los datos enviados no son válidos. Revise la información e intente nuevamente.");
    }

    @Test
    void missingRequiredAmountReturns400WithoutSideEffects() throws Exception {
        Map<String, String> fields = paymentFields("10.00");
        fields.remove("reportedAmount");
        assertRejected(fields, 1, 64, true, 400,
                "Faltan datos obligatorios. Complete la información e intente nuevamente.");
    }

    @ParameterizedTest
    @CsvSource({"1, 0, true", "5, 0, false", "5, 1024, true"})
    void allowedSizeBoundariesStillCreatePendingPayments(int fileCount, int belowLimit, boolean alias)
            throws Exception {
        Snapshot before = snapshot();
        PaymentResponse response = submit(paymentFields("10.00"), fileCount, FILE_LIMIT - belowLimit, alias);
        assertThat(response.statusCode()).isEqualTo(201);
        JsonNode payment = objectMapper.readTree(response.body());
        assertThat(payment.path("status").asText()).isEqualTo("PENDING");
        assertThat(payment.path("reportedAmount").asText()).isEqualTo("10.00");
        assertThat(payment.path("approvedAmount").asText()).isEqualTo("0.00");
        assertThat(payment.path("fileKeys").size()).isEqualTo(fileCount);
        Snapshot after = snapshot();
        assertThat(after.submissions()).isEqualTo(before.submissions() + 1);
        assertThat(after.attachments()).isEqualTo(before.attachments() + fileCount);
        assertThat(after.files().size()).isEqualTo(before.files().size() + fileCount);
        assertThat(after.files()).containsAll(before.files());
        assertFinancialStateUnchanged(before, after);
    }

    @Test
    void previouslyPartiallyPaidInstallmentCanCalculateAndSubmitWithoutApplyingPendingMoney() throws Exception {
        PaymentResponse initial = submit(paymentFields("40.00"), 1, 64, true);
        assertThat(initial.statusCode()).isEqualTo(201);
        long submissionId = objectMapper.readTree(initial.body()).path("submissionId").asLong();
        String adminToken = signUpAdmin(buildValidUser("http-transport-admin")).accessToken();
        HttpResponse<String> approval = sendJson("PATCH", "/api/v1/payments/" + submissionId + "/review",
                Map.of("approvedAmount", "40.00", "approvedCurrency", "ARS"), adminToken);
        assertThat(approval.statusCode()).isEqualTo(200);
        assertThat(installmentRepository.findById(installmentId).orElseThrow().getPaidAmount())
                .isEqualByComparingTo("40.00");

        JsonNode calculation = calculate("20.00");
        assertThat(calculation.path("anchorRemainingAmount").asText()).isEqualTo("60.00");
        Map<String, String> fields = fields("20.00", calculation.path("previewToken").asText());
        Snapshot before = snapshot();
        PaymentResponse pending = submit(fields, 1, 64, true);
        assertThat(pending.statusCode()).isEqualTo(201);
        JsonNode payment = objectMapper.readTree(pending.body());
        assertThat(payment.path("status").asText()).isEqualTo("PENDING");
        assertThat(payment.path("reportedAmount").asText()).isEqualTo("20.00");
        assertThat(payment.path("approvedAmount").asText()).isEqualTo("0.00");
        Snapshot after = snapshot();
        assertThat(after.submissions()).isEqualTo(before.submissions() + 1);
        assertThat(after.attachments()).isEqualTo(before.attachments() + 1);
        assertThat(after.files().size()).isEqualTo(before.files().size() + 1);
        assertThat(after.files()).containsAll(before.files());
        assertFinancialStateUnchanged(before, after);
    }

    private void assertRejected(Map<String, String> fields, int fileCount, int fileSize, boolean alias,
                                int expectedStatus, String expectedBody) throws Exception {
        Snapshot before = snapshot();
        PaymentResponse response = submit(fields, fileCount, fileSize, alias);
        // Check persistence even on RED: transport rejection must never reach storage/business writes.
        assertThat(snapshot()).isEqualTo(before);
        assertThat(response.statusCode()).isEqualTo(expectedStatus);
        assertThat(response.body()).isEqualTo(expectedBody);
    }

    private Map<String, String> paymentFields(String amount) throws Exception {
        return fields(amount, calculate(amount).path("previewToken").asText());
    }

    private JsonNode calculate(String amount) throws Exception {
        HttpResponse<String> response = sendJson("POST", "/api/v1/payments/calculation", Map.of(
                "anchorInstallmentId", installmentId, "reportedAmount", amount,
                "reportedPaymentDate", PAYMENT_DATE, "paymentCurrency", "ARS", "intent", "MANUAL"), userToken);
        assertThat(response.statusCode()).isEqualTo(200);
        JsonNode calculation = objectMapper.readTree(response.body());
        assertThat(calculation.path("status").asText()).isEqualTo("READY");
        assertThat(calculation.path("previewToken").asText()).isNotBlank();
        return calculation;
    }

    private Map<String, String> fields(String amount, String token) {
        Map<String, String> fields = new LinkedHashMap<>();
        fields.put("anchorInstallmentId", installmentId.toString());
        fields.put("reportedAmount", amount);
        fields.put("reportedPaymentDate", PAYMENT_DATE);
        fields.put("paymentCurrency", "ARS");
        fields.put("paymentMethod", "BANK_TRANSFER");
        fields.put("bankAccountId", bankAccountId.toString());
        fields.put("previewToken", token);
        return fields;
    }

    private HttpResponse<String> sendJson(String method, String path, Object body, String token) throws Exception {
        HttpRequest request = request(path, token).header("Content-Type", "application/json")
                .method(method, HttpRequest.BodyPublishers.ofString(objectMapper.writeValueAsString(body))).build();
        return client.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
    }

    private PaymentResponse submit(Map<String, String> fields, int fileCount, int fileSize, boolean alias)
            throws Exception {
        ByteArrayOutputStream body = new ByteArrayOutputStream();
        for (var field : fields.entrySet()) {
            body.write(("--" + BOUNDARY + "\r\nContent-Disposition: form-data; name=\"" + field.getKey()
                    + "\"\r\n\r\n" + field.getValue() + "\r\n").getBytes(StandardCharsets.UTF_8));
        }
        byte[] receipt = new byte[fileSize];
        System.arraycopy("%PDF-1.4\n".getBytes(StandardCharsets.UTF_8), 0, receipt, 0, 9);
        if (alias) {
            writeFilePart(body, "file", "receipt-0.pdf", receipt);
        }
        for (int index = 0; index < fileCount; index++) {
            writeFilePart(body, "files", "receipt-" + index + ".pdf", receipt);
        }
        body.write(("--" + BOUNDARY + "--\r\n").getBytes(StandardCharsets.UTF_8));
        byte[] payload = body.toByteArray();
        HttpURLConnection connection = (HttpURLConnection) URI.create(
                "http://127.0.0.1:" + port + "/api/v1/payments").toURL().openConnection();
        connection.setConnectTimeout(10_000);
        connection.setReadTimeout(60_000);
        connection.setRequestMethod("POST");
        connection.setRequestProperty("Authorization", "Bearer " + userToken);
        connection.setRequestProperty("Content-Type", "multipart/form-data; boundary=" + BOUNDARY);
        connection.setRequestProperty("Expect", "100-continue");
        connection.setFixedLengthStreamingMode(payload.length);
        connection.setDoOutput(true);
        try {
            IOException writeFailure = null;
            try (var output = connection.getOutputStream()) {
                output.write(payload);
            } catch (IOException exception) {
                // Early rejection can stop the upload; still read the one server response, never retry.
                writeFailure = exception;
            }
            int status = connection.getResponseCode();
            if (writeFailure != null && status < 400) {
                throw writeFailure;
            }
            try (var input = status >= 400 ? connection.getErrorStream() : connection.getInputStream()) {
                assertThat(input).as("HTTP response body").isNotNull();
                return new PaymentResponse(status, new String(input.readAllBytes(), StandardCharsets.UTF_8));
            }
        } finally {
            connection.disconnect();
        }
    }

    private void writeFilePart(ByteArrayOutputStream body, String field, String name, byte[] receipt) throws Exception {
        body.write(("--" + BOUNDARY + "\r\nContent-Disposition: form-data; name=\"" + field
                + "\"; filename=\"" + name + "\"\r\nContent-Type: application/pdf\r\n\r\n")
                .getBytes(StandardCharsets.UTF_8));
        body.write(receipt);
        body.write("\r\n".getBytes(StandardCharsets.UTF_8));
    }

    private HttpRequest.Builder request(String path, String token) {
        return HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path))
                .timeout(Duration.ofSeconds(60)).header("Authorization", "Bearer " + token);
    }

    private Snapshot snapshot() throws Exception {
        List<Map<String, Object>> balances = jdbcTemplate.queryForList(
                "SELECT id, total_due, paid_amount, status FROM installments ORDER BY id");
        List<String> files;
        try (Stream<Path> paths = Files.walk(receiptDirectory)) {
            files = paths.filter(Files::isRegularFile).map(path -> receiptDirectory.relativize(path).toString())
                    .sorted().toList();
        }
        return new Snapshot(paymentSubmissionRepository.count(), countRows("payment_submission_attachments"),
                paymentOutcomeRepository.count(), paymentAllocationRepository.count(), balances, files);
    }

    private long countRows(String table) {
        return jdbcTemplate.queryForObject("SELECT count(*) FROM " + table, Long.class);
    }

    private void assertFinancialStateUnchanged(Snapshot before, Snapshot after) {
        assertThat(after.balances()).isEqualTo(before.balances());
        assertThat(after.outcomes()).isEqualTo(before.outcomes());
        assertThat(after.allocations()).isEqualTo(before.allocations());
    }

    private record Snapshot(long submissions, long attachments, long outcomes, long allocations,
                            List<Map<String, Object>> balances, List<String> files) {}

    private record PaymentResponse(int statusCode, String body) {}
}
