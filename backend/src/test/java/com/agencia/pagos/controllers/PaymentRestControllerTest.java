package com.agencia.pagos.controllers;

import com.agencia.pagos.TestcontainersConfiguration;
import com.agencia.pagos.dtos.request.UserCreateDTO;
import com.agencia.pagos.dtos.response.TokenDTO;
import com.agencia.pagos.entities.BankAccount;
import com.agencia.pagos.entities.Currency;
import com.agencia.pagos.entities.Installment;
import com.agencia.pagos.entities.InstallmentStatus;
import com.agencia.pagos.entities.PaymentMethod;
import com.agencia.pagos.entities.PaymentOutcomeStatus;
import com.agencia.pagos.entities.PaymentSubmission;
import com.agencia.pagos.entities.PaymentSubmissionStatus;
import com.agencia.pagos.entities.Student;
import com.agencia.pagos.entities.Trip;
import com.agencia.pagos.entities.user.User;
import com.agencia.pagos.repositories.BankAccountRepository;
import com.agencia.pagos.repositories.InstallmentRepository;
import com.agencia.pagos.repositories.PaymentSubmissionRepository;
import com.agencia.pagos.repositories.StudentRepository;
import com.agencia.pagos.repositories.TripRepository;
import com.agencia.pagos.repositories.UserRepository;
import com.agencia.pagos.services.storage.PaymentAttachmentStorageService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.mail.javamail.JavaMailSender;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.BDDMockito.given;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(properties = {
        "app.mail.to=test@agencia.com",
        "app.mail.from=no-reply@agencia.com"
})
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
class PaymentRestControllerTest extends ControllerIntegrationTestSupport {

    private record PaymentFixture(TokenDTO userTokens, User user, Student student, Trip trip) {
    }

    @MockBean
    private JavaMailSender javaMailSender;

    @MockBean
    private PaymentAttachmentStorageService paymentAttachmentStorageService;

    @Autowired
    private TripRepository tripRepository;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private InstallmentRepository installmentRepository;

    @Autowired
    private BankAccountRepository bankAccountRepository;

    @Autowired
    private StudentRepository studentRepository;

    @Autowired
    private PaymentSubmissionRepository paymentSubmissionRepository;

    @Test
    void getPendingReview_siendoAdmin_devuelvePagosPendientesAgrupadosPorSubmission() throws Exception {
        TokenDTO adminTokens = signUpAdmin(buildValidUser("admin-pending-grouped"));
        PaymentFixture fixture = createPaymentFixture("payment-pending-grouped", Currency.ARS);
        Installment first = createInstallment(fixture.trip(), fixture.user(), fixture.student(), 1, "100.00", InstallmentStatus.YELLOW);
        createInstallment(fixture.trip(), fixture.user(), fixture.student(), 2, "100.00", InstallmentStatus.YELLOW);
        createInstallment(fixture.trip(), fixture.user(), fixture.student(), 3, "100.00", InstallmentStatus.YELLOW);
        BankAccount bankAccount = createBankAccount(Currency.ARS);
        given(paymentAttachmentStorageService.resolveFileReference("receipts/trip-10/user-20/pending.jpg"))
                .willReturn("https://backend.example/api/v1/payment-attachments/admin-review-token");

        paymentSubmissionRepository.save(buildPendingSubmission(
                first,
                bankAccount,
                "250.00",
                "250.00",
                "receipts/trip-10/user-20/pending.jpg"
        ));

        mockMvc.perform(get("/api/v1/payments/pending-review")
                        .header("Authorization", "Bearer " + adminTokens.accessToken()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].submissionId").exists())
                .andExpect(jsonPath("$[0].status").value("PENDING"))
                .andExpect(jsonPath("$[0].tripId").value(fixture.trip().getId()))
                .andExpect(jsonPath("$[0].userEmail").value(fixture.user().getEmail()))
                .andExpect(jsonPath("$[0].reportedAmount").value(250))
                .andExpect(jsonPath("$[0].fileKey").value("https://backend.example/api/v1/payment-attachments/admin-review-token"))
                .andExpect(jsonPath("$[0].allocations.length()").value(3))
                .andExpect(jsonPath("$[0].allocations[2].amountInTripCurrency").value(50));
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

    @Test
    void reviewPayment_normalYRepetido_devuelveResueltoYLuego409() throws Exception {
        TokenDTO adminTokens = signUpAdmin(buildValidUser("admin-review-payment"));
        PaymentSubmission submission = createPendingPaymentSubmission("payment-review", "100.00");

        mockMvc.perform(patch("/api/v1/payments/{id}/review", submission.getId())
                        .header("Authorization", "Bearer " + adminTokens.accessToken())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"approvedAmount\":100.00}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("APPROVED"));

        mockMvc.perform(patch("/api/v1/payments/{id}/review", submission.getId())
                        .header("Authorization", "Bearer " + adminTokens.accessToken())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"approvedAmount\":100.00}"))
                .andExpect(status().isConflict())
                .andExpect(content().string("Este pago ya fue revisado"));
    }

    @Test
    void voidPayment_normalYRepetido_devuelveAnuladoYLuego409() throws Exception {
        TokenDTO adminTokens = signUpAdmin(buildValidUser("admin-void-payment"));
        PaymentSubmission submission = createPendingPaymentSubmission("payment-void", "100.00");

        mockMvc.perform(patch("/api/v1/payments/{id}/review", submission.getId())
                        .header("Authorization", "Bearer " + adminTokens.accessToken())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"approvedAmount\":100.00}"))
                .andExpect(status().isOk());

        mockMvc.perform(post("/api/v1/payments/{id}/void", submission.getId())
                        .header("Authorization", "Bearer " + adminTokens.accessToken()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("VOIDED"));

        mockMvc.perform(post("/api/v1/payments/{id}/void", submission.getId())
                        .header("Authorization", "Bearer " + adminTokens.accessToken()))
                .andExpect(status().isConflict())
                .andExpect(content().string("Este pago ya fue anulado"));
    }

    @Test
    void reviewPayment_concurrentRequests_resuelveUnaSolaVez() throws Exception {
        TokenDTO adminTokens = signUpAdmin(buildValidUser("admin-review-concurrent"));
        PaymentSubmission submission = createPendingPaymentSubmission("payment-review-concurrent", "100.00");
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch release = new CountDownLatch(1);
        ExecutorService executor = Executors.newFixedThreadPool(2);

        try {
            Future<Integer> first = executor.submit(() -> performConcurrentReview(ready, release, adminTokens.accessToken(), submission.getId()));
            Future<Integer> second = executor.submit(() -> performConcurrentReview(ready, release, adminTokens.accessToken(), submission.getId()));
            assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue();
            release.countDown();

            int firstStatus = first.get(15, TimeUnit.SECONDS);
            int secondStatus = second.get(15, TimeUnit.SECONDS);
            assertThat(java.util.List.of(firstStatus, secondStatus))
                .containsExactlyInAnyOrder(200, 409);
        } finally {
            release.countDown();
            executor.shutdownNow();
        }

        Installment installment = installmentRepository.findById(submission.getAnchorInstallment().getId()).orElseThrow();
        assertThat(installment.getPaidAmount()).isEqualByComparingTo("100.00");
        assertThat(paymentOutcomeRepository.findAll()).hasSize(1);
    }

    private int performConcurrentReview(
            CountDownLatch ready,
            CountDownLatch release,
            String adminToken,
            Long submissionId
    ) throws Exception {
        ready.countDown();
        if (!release.await(10, TimeUnit.SECONDS)) {
            throw new IllegalStateException("Concurrent review release timed out");
        }
        return mockMvc.perform(patch("/api/v1/payments/{id}/review", submissionId)
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"approvedAmount\":100.00}"))
                .andReturn()
                .getResponse()
                .getStatus();
    }

    @Test
    void voidPayment_concurrentRequests_revierteUnaSolaVez() throws Exception {
        TokenDTO adminTokens = signUpAdmin(buildValidUser("admin-void-concurrent"));
        PaymentSubmission submission = createPendingPaymentSubmission("payment-void-concurrent", "100.00");

        // Move the submission into the APPROVED state so VOID has work to do.
        mockMvc.perform(patch("/api/v1/payments/{id}/review", submission.getId())
                        .header("Authorization", "Bearer " + adminTokens.accessToken())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"approvedAmount\":100.00}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("APPROVED"));

        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch release = new CountDownLatch(1);
        ExecutorService executor = Executors.newFixedThreadPool(2);

        try {
            Future<Integer> first = executor.submit(() -> performConcurrentVoid(ready, release, adminTokens.accessToken(), submission.getId()));
            Future<Integer> second = executor.submit(() -> performConcurrentVoid(ready, release, adminTokens.accessToken(), submission.getId()));
            assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue();
            release.countDown();

            int firstStatus = first.get(15, TimeUnit.SECONDS);
            int secondStatus = second.get(15, TimeUnit.SECONDS);
            assertThat(java.util.List.of(firstStatus, secondStatus))
                .containsExactlyInAnyOrder(200, 409);
        } finally {
            release.countDown();
            executor.shutdownNow();
        }

        Installment installment = installmentRepository.findById(submission.getAnchorInstallment().getId()).orElseThrow();
        assertThat(installment.getPaidAmount()).isEqualByComparingTo("0");
        // Exactly one PaymentOutcome (the original APPROVED) and its status is VOIDED
        // after the successful concurrent void. The conflicting request must not have
        // created a duplicate outcome.
        assertThat(paymentOutcomeRepository.findAll()).hasSize(1);
        assertThat(paymentOutcomeRepository.findAll().get(0).getStatus())
                .isEqualTo(PaymentOutcomeStatus.VOIDED);
        assertThat(paymentSubmissionRepository.findById(submission.getId()).orElseThrow().getStatus())
                .isEqualTo(PaymentSubmissionStatus.VOIDED);
    }

    private int performConcurrentVoid(
            CountDownLatch ready,
            CountDownLatch release,
            String adminToken,
            Long submissionId
    ) throws Exception {
        ready.countDown();
        if (!release.await(10, TimeUnit.SECONDS)) {
            throw new IllegalStateException("Concurrent void release timed out");
        }
        return mockMvc.perform(post("/api/v1/payments/{id}/void", submissionId)
                        .header("Authorization", "Bearer " + adminToken))
                .andReturn()
                .getResponse()
                .getStatus();
    }

    private PaymentFixture createPaymentFixture(String prefix, Currency currency) throws Exception {
        UserCreateDTO participantDto = buildValidUser(prefix);
        TokenDTO userTokens = signUp(participantDto);
        User user = userRepository.findByEmail(participantDto.email()).orElseThrow();
        Student student = getFirstStudent(user);
        Trip trip = createTripForUser(user, prefix, currency);
        return new PaymentFixture(userTokens, user, student, trip);
    }

    private PaymentSubmission createPendingPaymentSubmission(String prefix, String amount) throws Exception {
        PaymentFixture fixture = createPaymentFixture(prefix, Currency.ARS);
        Installment installment = createInstallment(
                fixture.trip(), fixture.user(), fixture.student(), 1, amount, InstallmentStatus.YELLOW);
        return paymentSubmissionRepository.save(buildPendingSubmission(
                installment,
                createBankAccount(Currency.ARS),
                amount,
                amount,
                "inline-test-receipt"
        ));
    }

    private Student getFirstStudent(User user) {
        return studentRepository.findByParentId(user.getId()).stream().findFirst().orElseThrow();
    }

    private Trip createTripForUser(User user, String prefix, Currency currency) {
        Trip trip = new Trip();
        trip.setName("Trip payment " + prefix);
        trip.setCurrency(currency);
        trip.setTotalAmount(BigDecimal.valueOf(120000));
        trip.setFirstInstallmentAmount(BigDecimal.valueOf(10000));
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

    private PaymentSubmission buildPendingSubmission(
            Installment anchorInstallment,
            BankAccount bankAccount,
            String reportedAmount,
            String amountInTripCurrency,
            String fileKey
    ) {
        PaymentSubmission submission = new PaymentSubmission();
        submission.setTrip(anchorInstallment.getTrip());
        submission.setUser(anchorInstallment.getUser());
        submission.setStudent(anchorInstallment.getStudent());
        submission.setAnchorInstallment(anchorInstallment);
        submission.setBankAccount(bankAccount);
        submission.setReportedAmount(new BigDecimal(reportedAmount));
        submission.setPaymentCurrency(bankAccount.getCurrency());
        submission.setExchangeRate(null);
        submission.setAmountInTripCurrency(new BigDecimal(amountInTripCurrency));
        submission.setReportedPaymentDate(LocalDate.now());
        submission.setPaymentMethod(PaymentMethod.BANK_TRANSFER);
        submission.setStatus(PaymentSubmissionStatus.PENDING);
        submission.setFileKey(fileKey);
        return submission;
    }
}
