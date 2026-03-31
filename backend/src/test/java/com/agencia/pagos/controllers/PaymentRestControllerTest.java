package com.agencia.pagos.controllers;

import com.agencia.pagos.TestcontainersConfiguration;
import com.agencia.pagos.dtos.request.PaymentPreviewRequestDTO;
import com.agencia.pagos.dtos.request.ReviewPaymentDTO;
import com.agencia.pagos.dtos.request.UserCreateDTO;
import com.agencia.pagos.dtos.response.TokenDTO;
import com.agencia.pagos.entities.BankAccount;
import com.agencia.pagos.entities.Currency;
import com.agencia.pagos.entities.Installment;
import com.agencia.pagos.entities.InstallmentStatus;
import com.agencia.pagos.entities.PaymentBatch;
import com.agencia.pagos.entities.PaymentMethod;
import com.agencia.pagos.entities.PaymentReceipt;
import com.agencia.pagos.entities.ReceiptStatus;
import com.agencia.pagos.entities.Student;
import com.agencia.pagos.entities.Trip;
import com.agencia.pagos.entities.user.User;
import com.agencia.pagos.repositories.BankAccountRepository;
import com.agencia.pagos.repositories.InstallmentRepository;
import com.agencia.pagos.repositories.PaymentBatchRepository;
import com.agencia.pagos.repositories.PaymentReceiptRepository;
import com.agencia.pagos.repositories.StudentRepository;
import com.agencia.pagos.repositories.TripRepository;
import com.agencia.pagos.repositories.UserRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mock.web.MockMultipartFile;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Comparator;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(properties = {
        "app.mail.to=test@agencia.com",
        "app.mail.from=no-reply@agencia.com"
})
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
class PaymentRestControllerTest extends ControllerIntegrationTestSupport {

    private record PaymentFixture(TokenDTO userTokens, User user, Student student, Trip trip) {}

    @MockBean
    private JavaMailSender javaMailSender;

    @Autowired
    private TripRepository tripRepository;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private InstallmentRepository installmentRepository;

    @Autowired
    private PaymentReceiptRepository paymentReceiptRepository;

    @Autowired
    private PaymentBatchRepository paymentBatchRepository;

    @Autowired
    private BankAccountRepository bankAccountRepository;

    @Autowired
    private StudentRepository studentRepository;

    @Test
    void previewPayment_unaCuotaDevuelveTotalExacto() throws Exception {
        PaymentFixture fixture = createPaymentFixture("payment-preview-one", Currency.ARS);
        Installment first = createInstallment(fixture.trip(), fixture.user(), fixture.student(), 1, "100.00", InstallmentStatus.YELLOW);
        createInstallment(fixture.trip(), fixture.user(), fixture.student(), 2, "100.00", InstallmentStatus.YELLOW);
        createInstallment(fixture.trip(), fixture.user(), fixture.student(), 3, "100.00", InstallmentStatus.YELLOW);

        mockMvc.perform(post("/api/v1/payments/preview")
                        .header("Authorization", "Bearer " + fixture.userTokens().accessToken())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new PaymentPreviewRequestDTO(
                                first.getId(),
                                1,
                                LocalDate.now(),
                                Currency.ARS
                        ))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.anchorInstallmentId").value(first.getId()))
                .andExpect(jsonPath("$.installmentsCount").value(1))
                .andExpect(jsonPath("$.paymentCurrency").value("ARS"))
                .andExpect(jsonPath("$.totalReportedAmount").value(100))
                .andExpect(jsonPath("$.installments.length()").value(1))
                .andExpect(jsonPath("$.installments[0].installmentNumber").value(1))
                .andExpect(jsonPath("$.installments[0].remainingAmount").value(100));
    }

    @Test
    void previewPayment_dosCuotasDevuelveTotalExacto() throws Exception {
        PaymentFixture fixture = createPaymentFixture("payment-preview-two", Currency.ARS);
        Installment first = createInstallment(fixture.trip(), fixture.user(), fixture.student(), 1, "100.00", InstallmentStatus.YELLOW);
        createInstallment(fixture.trip(), fixture.user(), fixture.student(), 2, "100.00", InstallmentStatus.YELLOW);
        createInstallment(fixture.trip(), fixture.user(), fixture.student(), 3, "100.00", InstallmentStatus.YELLOW);

        mockMvc.perform(post("/api/v1/payments/preview")
                        .header("Authorization", "Bearer " + fixture.userTokens().accessToken())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new PaymentPreviewRequestDTO(
                                first.getId(),
                                2,
                                LocalDate.now(),
                                Currency.ARS
                        ))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.installmentsCount").value(2))
                .andExpect(jsonPath("$.totalReportedAmount").value(200))
                .andExpect(jsonPath("$.installments.length()").value(2))
                .andExpect(jsonPath("$.installments[0].installmentNumber").value(1))
                .andExpect(jsonPath("$.installments[1].installmentNumber").value(2));
    }

    @Test
    void previewPayment_tresCuotasDevuelveTotalExacto() throws Exception {
        PaymentFixture fixture = createPaymentFixture("payment-preview-three", Currency.ARS);
        Installment first = createInstallment(fixture.trip(), fixture.user(), fixture.student(), 1, "100.00", InstallmentStatus.YELLOW);
        createInstallment(fixture.trip(), fixture.user(), fixture.student(), 2, "100.00", InstallmentStatus.YELLOW);
        createInstallment(fixture.trip(), fixture.user(), fixture.student(), 3, "100.00", InstallmentStatus.YELLOW);

        mockMvc.perform(post("/api/v1/payments/preview")
                        .header("Authorization", "Bearer " + fixture.userTokens().accessToken())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new PaymentPreviewRequestDTO(
                                first.getId(),
                                3,
                                LocalDate.now(),
                                Currency.ARS
                        ))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.installmentsCount").value(3))
                .andExpect(jsonPath("$.totalReportedAmount").value(300))
                .andExpect(jsonPath("$.installments.length()").value(3))
                .andExpect(jsonPath("$.installments[2].installmentNumber").value(3));
    }

    @Test
    void registerPayment_multipartCreaBatchYLineasExactas() throws Exception {
        PaymentFixture fixture = createPaymentFixture("payment-register-batch", Currency.ARS);
        Installment first = createInstallment(fixture.trip(), fixture.user(), fixture.student(), 1, "100.00", InstallmentStatus.YELLOW);
        createInstallment(fixture.trip(), fixture.user(), fixture.student(), 2, "100.00", InstallmentStatus.YELLOW);
        createInstallment(fixture.trip(), fixture.user(), fixture.student(), 3, "100.00", InstallmentStatus.YELLOW);
        BankAccount bankAccount = createBankAccount(Currency.ARS);

        MockMultipartFile file = new MockMultipartFile(
                "file",
                "comprobante.jpg",
                "image/jpeg",
                "contenido".getBytes()
        );

        mockMvc.perform(multipart("/api/v1/payments")
                        .file(file)
                        .header("Authorization", "Bearer " + fixture.userTokens().accessToken())
                        .param("anchorInstallmentId", String.valueOf(first.getId()))
                        .param("installmentsCount", "2")
                        .param("reportedPaymentDate", LocalDate.now().toString())
                        .param("paymentCurrency", "ARS")
                        .param("paymentMethod", "BANK_TRANSFER")
                        .param("bankAccountId", String.valueOf(bankAccount.getId()))
                        .with(request -> {
                            request.setMethod("POST");
                            return request;
                        }))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.batchId").exists())
                .andExpect(jsonPath("$.reportedAmount").value(200))
                .andExpect(jsonPath("$.bankAccountId").value(bankAccount.getId()))
                .andExpect(jsonPath("$.installments.length()").value(2))
                .andExpect(jsonPath("$.installments[0].installmentNumber").value(1))
                .andExpect(jsonPath("$.installments[1].installmentNumber").value(2))
                .andExpect(jsonPath("$.installments[0].status").value("PENDING"))
                .andExpect(jsonPath("$.installments[1].status").value("PENDING"));

        List<PaymentBatch> batches = paymentBatchRepository.findAll();
        List<PaymentReceipt> receipts = paymentReceiptRepository.findAll().stream()
                .sorted(Comparator.comparing(receipt -> receipt.getInstallment().getInstallmentNumber()))
                .toList();

        assertEquals(1, batches.size());
        assertEquals(2, receipts.size());
        assertTrue(batches.get(0).getFileKey().startsWith("data:image/jpeg;base64,"));
        assertEquals(ReceiptStatus.PENDING, receipts.get(0).getStatus());
        assertEquals(ReceiptStatus.PENDING, receipts.get(1).getStatus());
        assertEquals(first.getId(), receipts.get(0).getInstallment().getId());
        assertEquals(batches.get(0).getId(), receipts.get(0).getBatch().getId());
        assertEquals(batches.get(0).getId(), receipts.get(1).getBatch().getId());
    }

    @Test
    void registerPayment_anchorNoEsPrimeraPendiente_devuelve409() throws Exception {
        PaymentFixture fixture = createPaymentFixture("payment-register-anchor", Currency.ARS);
        createInstallment(fixture.trip(), fixture.user(), fixture.student(), 1, "100.00", InstallmentStatus.YELLOW);
        Installment second = createInstallment(fixture.trip(), fixture.user(), fixture.student(), 2, "100.00", InstallmentStatus.YELLOW);
        BankAccount bankAccount = createBankAccount(Currency.ARS);

        mockMvc.perform(multipart("/api/v1/payments")
                        .header("Authorization", "Bearer " + fixture.userTokens().accessToken())
                        .param("anchorInstallmentId", String.valueOf(second.getId()))
                        .param("installmentsCount", "1")
                        .param("reportedPaymentDate", LocalDate.now().toString())
                        .param("paymentCurrency", "ARS")
                        .param("paymentMethod", "BANK_TRANSFER")
                        .param("bankAccountId", String.valueOf(bankAccount.getId()))
                        .with(request -> {
                            request.setMethod("POST");
                            return request;
                        }))
                .andExpect(status().isConflict());
    }

    @Test
    void registerPayment_conPendienteEnElGrupo_devuelve409() throws Exception {
        PaymentFixture fixture = createPaymentFixture("payment-register-pending", Currency.ARS);
        Installment first = createInstallment(fixture.trip(), fixture.user(), fixture.student(), 1, "100.00", InstallmentStatus.YELLOW);
        createInstallment(fixture.trip(), fixture.user(), fixture.student(), 2, "100.00", InstallmentStatus.YELLOW);
        BankAccount bankAccount = createBankAccount(Currency.ARS);

        paymentReceiptRepository.save(PaymentReceipt.builder()
                .installment(first)
                .bankAccount(bankAccount)
                .reportedAmount(new BigDecimal("100.00"))
                .paymentCurrency(Currency.ARS)
                .amountInTripCurrency(new BigDecimal("100.00"))
                .reportedPaymentDate(LocalDate.now())
                .paymentMethod(PaymentMethod.BANK_TRANSFER)
                .status(ReceiptStatus.PENDING)
                .fileKey("")
                .build());

        mockMvc.perform(multipart("/api/v1/payments")
                        .header("Authorization", "Bearer " + fixture.userTokens().accessToken())
                        .param("anchorInstallmentId", String.valueOf(first.getId()))
                        .param("installmentsCount", "1")
                        .param("reportedPaymentDate", LocalDate.now().toString())
                        .param("paymentCurrency", "ARS")
                        .param("paymentMethod", "BANK_TRANSFER")
                        .param("bankAccountId", String.valueOf(bankAccount.getId()))
                        .with(request -> {
                            request.setMethod("POST");
                            return request;
                        }))
                .andExpect(status().isConflict());
    }

    @Test
    void getPendingReview_siendoAdmin_devuelveBatchesAgrupados() throws Exception {
        TokenDTO adminTokens = signUpAdmin(buildValidUser("admin-pending-grouped"));
        PaymentFixture fixture = createPaymentFixture("payment-pending-grouped", Currency.ARS);
        Installment first = createInstallment(fixture.trip(), fixture.user(), fixture.student(), 1, "100.00", InstallmentStatus.YELLOW);
        Installment second = createInstallment(fixture.trip(), fixture.user(), fixture.student(), 2, "100.00", InstallmentStatus.YELLOW);
        BankAccount bankAccount = createBankAccount(Currency.ARS);
        PaymentBatch batch = createBatch(bankAccount, "200.00", Currency.ARS, "200.00");

        PaymentReceipt firstReceipt = createReceipt(first, batch, bankAccount, "100.00", "100.00", ReceiptStatus.PENDING);
        PaymentReceipt secondReceipt = createReceipt(second, batch, bankAccount, "100.00", "100.00", ReceiptStatus.PENDING);
        createReceipt(second, null, bankAccount, "30.00", "30.00", ReceiptStatus.REJECTED);

        mockMvc.perform(get("/api/v1/payments/pending-review")
                        .header("Authorization", "Bearer " + adminTokens.accessToken()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].batchId").value(batch.getId()))
                .andExpect(jsonPath("$[0].tripId").value(fixture.trip().getId()))
                .andExpect(jsonPath("$[0].userEmail").value(fixture.user().getEmail()))
                .andExpect(jsonPath("$[0].bankAccountAlias").value(bankAccount.getAlias()))
                .andExpect(jsonPath("$[0].receipts.length()").value(2))
                .andExpect(jsonPath("$[0].receipts[0].receiptId").value(firstReceipt.getId()))
                .andExpect(jsonPath("$[0].receipts[0].installmentNumber").value(1))
                .andExpect(jsonPath("$[0].receipts[1].receiptId").value(secondReceipt.getId()))
                .andExpect(jsonPath("$[0].receipts[1].installmentNumber").value(2));
    }

    @Test
    void reviewPayment_aprobarLineaSoloAfectaSuCuota() throws Exception {
        TokenDTO adminTokens = signUpAdmin(buildValidUser("admin-review-line"));
        PaymentFixture fixture = createPaymentFixture("payment-review-line", Currency.ARS);
        Installment first = createInstallment(fixture.trip(), fixture.user(), fixture.student(), 1, "100.00", InstallmentStatus.YELLOW);
        Installment second = createInstallment(fixture.trip(), fixture.user(), fixture.student(), 2, "100.00", InstallmentStatus.YELLOW);
        BankAccount bankAccount = createBankAccount(Currency.ARS);
        PaymentBatch batch = createBatch(bankAccount, "200.00", Currency.ARS, "200.00");

        PaymentReceipt firstReceipt = createReceipt(first, batch, bankAccount, "100.00", "100.00", ReceiptStatus.PENDING);
        createReceipt(second, batch, bankAccount, "100.00", "100.00", ReceiptStatus.PENDING);

        mockMvc.perform(patch("/api/v1/payments/{id}/review", firstReceipt.getId())
                        .header("Authorization", "Bearer " + adminTokens.accessToken())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new ReviewPaymentDTO(ReceiptStatus.APPROVED, null))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("APPROVED"))
                .andExpect(jsonPath("$.installmentId").value(first.getId()));

        Installment persistedFirst = installmentRepository.findById(first.getId()).orElseThrow();
        Installment persistedSecond = installmentRepository.findById(second.getId()).orElseThrow();

        assertEquals(0, persistedFirst.getPaidAmount().compareTo(new BigDecimal("100.00")));
        assertEquals(0, persistedSecond.getPaidAmount().compareTo(BigDecimal.ZERO));
    }

    @Test
    void reviewPayment_rechazarLineaNoRedistribuyeImporteAlResto() throws Exception {
        TokenDTO adminTokens = signUpAdmin(buildValidUser("admin-review-reject-line"));
        PaymentFixture fixture = createPaymentFixture("payment-review-reject-line", Currency.ARS);
        Installment first = createInstallment(fixture.trip(), fixture.user(), fixture.student(), 1, "100.00", InstallmentStatus.YELLOW);
        Installment second = createInstallment(fixture.trip(), fixture.user(), fixture.student(), 2, "100.00", InstallmentStatus.YELLOW);
        BankAccount bankAccount = createBankAccount(Currency.ARS);
        PaymentBatch batch = createBatch(bankAccount, "200.00", Currency.ARS, "200.00");

        PaymentReceipt firstReceipt = createReceipt(first, batch, bankAccount, "100.00", "100.00", ReceiptStatus.PENDING);
        PaymentReceipt secondReceipt = createReceipt(second, batch, bankAccount, "100.00", "100.00", ReceiptStatus.PENDING);

        mockMvc.perform(patch("/api/v1/payments/{id}/review", firstReceipt.getId())
                        .header("Authorization", "Bearer " + adminTokens.accessToken())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new ReviewPaymentDTO(ReceiptStatus.APPROVED, null))))
                .andExpect(status().isOk());

        mockMvc.perform(patch("/api/v1/payments/{id}/review", secondReceipt.getId())
                        .header("Authorization", "Bearer " + adminTokens.accessToken())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new ReviewPaymentDTO(
                                ReceiptStatus.REJECTED,
                                "Monto no acreditado"
                        ))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("REJECTED"))
                .andExpect(jsonPath("$.adminObservation").value("Monto no acreditado"));

        Installment persistedFirst = installmentRepository.findById(first.getId()).orElseThrow();
        Installment persistedSecond = installmentRepository.findById(second.getId()).orElseThrow();
        PaymentReceipt rejected = paymentReceiptRepository.findById(secondReceipt.getId()).orElseThrow();

        assertEquals(0, persistedFirst.getPaidAmount().compareTo(new BigDecimal("100.00")));
        assertEquals(0, persistedSecond.getPaidAmount().compareTo(BigDecimal.ZERO));
        assertEquals(ReceiptStatus.REJECTED, rejected.getStatus());
    }

    @Test
    void voidPayment_anulaLineaAprobadaSinDesarmarOtrasCuotas() throws Exception {
        TokenDTO adminTokens = signUpAdmin(buildValidUser("admin-void-line"));
        PaymentFixture fixture = createPaymentFixture("payment-void-line", Currency.ARS);
        Installment first = createInstallment(fixture.trip(), fixture.user(), fixture.student(), 1, "100.00", InstallmentStatus.YELLOW);
        Installment second = createInstallment(fixture.trip(), fixture.user(), fixture.student(), 2, "100.00", InstallmentStatus.YELLOW);
        BankAccount bankAccount = createBankAccount(Currency.ARS);
        PaymentBatch batch = createBatch(bankAccount, "200.00", Currency.ARS, "200.00");

        PaymentReceipt firstReceipt = createReceipt(first, batch, bankAccount, "100.00", "100.00", ReceiptStatus.PENDING);
        PaymentReceipt secondReceipt = createReceipt(second, batch, bankAccount, "100.00", "100.00", ReceiptStatus.PENDING);

        mockMvc.perform(patch("/api/v1/payments/{id}/review", firstReceipt.getId())
                        .header("Authorization", "Bearer " + adminTokens.accessToken())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new ReviewPaymentDTO(ReceiptStatus.APPROVED, null))))
                .andExpect(status().isOk());

        mockMvc.perform(patch("/api/v1/payments/{id}/review", secondReceipt.getId())
                        .header("Authorization", "Bearer " + adminTokens.accessToken())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new ReviewPaymentDTO(ReceiptStatus.APPROVED, null))))
                .andExpect(status().isOk());

        mockMvc.perform(post("/api/v1/payments/{id}/void", firstReceipt.getId())
                        .header("Authorization", "Bearer " + adminTokens.accessToken()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("REJECTED"))
                .andExpect(jsonPath("$.adminObservation").value("Anulado por administrador"));

        Installment persistedFirst = installmentRepository.findById(first.getId()).orElseThrow();
        Installment persistedSecond = installmentRepository.findById(second.getId()).orElseThrow();

        assertEquals(0, persistedFirst.getPaidAmount().compareTo(BigDecimal.ZERO));
        assertEquals(0, persistedSecond.getPaidAmount().compareTo(new BigDecimal("100.00")));
    }

    @Test
    void getMyInstallments_cuotaTotalmenteCubiertaSeMuestraComoPagada() throws Exception {
        PaymentFixture fixture = createPaymentFixture("payment-my-installments-paid", Currency.ARS);
        Installment installment = createInstallment(fixture.trip(), fixture.user(), fixture.student(), 1, "100.00", InstallmentStatus.YELLOW);
        installment.setPaidAmount(new BigDecimal("100.00"));
        installmentRepository.save(installment);

        mockMvc.perform(get("/api/v1/payments/my/installments")
                        .header("Authorization", "Bearer " + fixture.userTokens().accessToken()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].installmentStatus").value("GREEN"))
                .andExpect(jsonPath("$[0].uiStatusCode").value("PAID"))
                .andExpect(jsonPath("$[0].uiStatusLabel").value("Pagada"))
                .andExpect(jsonPath("$[0].uiStatusTone").value("green"));
    }

    @Test
    void getPendingReview_sinAutenticacion_devuelve401() throws Exception {
        mockMvc.perform(get("/api/v1/payments/pending-review"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void getPendingReview_siendoUserNormal_devuelve403() throws Exception {
        TokenDTO userTokens = signUp(buildValidUser("user-pending-review-forbidden"));

        mockMvc.perform(get("/api/v1/payments/pending-review")
                        .header("Authorization", "Bearer " + userTokens.accessToken()))
                .andExpect(status().isForbidden());
    }

    private PaymentFixture createPaymentFixture(String prefix, Currency currency) throws Exception {
        UserCreateDTO participantDto = buildValidUser(prefix);
        TokenDTO userTokens = signUp(participantDto);
        User user = userRepository.findByEmail(participantDto.email()).orElseThrow();
        Student student = getFirstStudent(user);
        Trip trip = createTripForUser(user, prefix, currency);
        return new PaymentFixture(userTokens, user, student, trip);
    }

    private Student getFirstStudent(User user) {
        return studentRepository.findByParentId(user.getId()).stream().findFirst().orElseThrow();
    }

    private Trip createTripForUser(User user, String prefix, Currency currency) {
        Trip trip = new Trip();
        trip.setName("Trip payment " + prefix);
        trip.setCurrency(currency);
        trip.setTotalAmount(BigDecimal.valueOf(120000));
        trip.setInstallmentsCount(12);
        trip.setDueDay(10);
        trip.setYellowWarningDays(5);
        trip.setFixedFineAmount(BigDecimal.valueOf(5000));
        trip.setRetroactiveActive(false);
        trip.setFirstDueDate(LocalDate.now().plusMonths(1));
        trip.getAssignedUsers().add(user);
        return tripRepository.save(trip);
    }

    private Installment createInstallment(
            Trip trip,
            User user,
            Student student,
            int installmentNumber,
            String capitalAmount,
            InstallmentStatus status
    ) {
        Installment installment = new Installment();
        installment.setTrip(trip);
        installment.setUser(user);
        installment.setStudent(student);
        installment.setInstallmentNumber(installmentNumber);
        installment.setDueDate(LocalDate.now().plusDays(installmentNumber));
        installment.setCapitalAmount(new BigDecimal(capitalAmount));
        installment.setRetroactiveAmount(BigDecimal.ZERO);
        installment.setFineAmount(BigDecimal.ZERO);
        installment.setPaidAmount(BigDecimal.ZERO);
        installment.setStatus(status);
        installment.recalculateTotalDue();
        return installmentRepository.save(installment);
    }

    private BankAccount createBankAccount(Currency currency) {
        return bankAccountRepository.save(BankAccount.builder()
                .bankName(currency == Currency.USD ? "Banco Galicia" : "Banco ICBC")
                .accountLabel(currency == Currency.USD ? "Cuenta en dolares" : "Cuenta en pesos")
                .accountHolder("Proyecto VA SRL")
                .accountNumber("0001-" + System.nanoTime())
                .taxId("30-71131646-5")
                .cbu(String.valueOf(System.nanoTime()))
                .alias("ALIAS." + System.nanoTime())
                .currency(currency)
                .active(true)
                .displayOrder(1)
                .build());
    }

    private PaymentBatch createBatch(BankAccount bankAccount, String reportedAmount, Currency paymentCurrency, String amountInTripCurrency) {
        PaymentBatch batch = new PaymentBatch();
        batch.setReportedAmount(new BigDecimal(reportedAmount));
        batch.setPaymentCurrency(paymentCurrency);
        batch.setExchangeRate(null);
        batch.setAmountInTripCurrency(new BigDecimal(amountInTripCurrency));
        batch.setReportedPaymentDate(LocalDate.now());
        batch.setPaymentMethod(PaymentMethod.BANK_TRANSFER);
        batch.setBankAccount(bankAccount);
        batch.setFileKey("data:image/jpeg;base64,ZHVtbXk=");
        return paymentBatchRepository.save(batch);
    }

    private PaymentReceipt createReceipt(
            Installment installment,
            PaymentBatch batch,
            BankAccount bankAccount,
            String reportedAmount,
            String amountInTripCurrency,
            ReceiptStatus status
    ) {
        return paymentReceiptRepository.save(PaymentReceipt.builder()
                .installment(installment)
                .batch(batch)
                .bankAccount(bankAccount)
                .reportedAmount(new BigDecimal(reportedAmount))
                .paymentCurrency(batch != null ? batch.getPaymentCurrency() : bankAccount.getCurrency())
                .amountInTripCurrency(new BigDecimal(amountInTripCurrency))
                .reportedPaymentDate(LocalDate.now())
                .paymentMethod(PaymentMethod.BANK_TRANSFER)
                .status(status)
                .fileKey("")
                .adminObservation(status == ReceiptStatus.REJECTED ? "Rechazado previamente" : null)
                .build());
    }
}
