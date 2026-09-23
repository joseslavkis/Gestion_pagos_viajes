package com.agencia.pagos.payment;

import com.agencia.pagos.TestcontainersConfiguration;
import com.agencia.pagos.payment.dto.PaymentBatchPreviewDTO;
import com.agencia.pagos.payment.dto.PaymentPreviewRequestDTO;
import com.agencia.pagos.payment.dto.PaymentSubmissionDTO;
import com.agencia.pagos.payment.dto.RegisterPaymentDTO;
import com.agencia.pagos.payment.dto.ReviewPaymentDTO;
import com.agencia.pagos.user.dto.UserCreateDTO;
import com.agencia.pagos.user.dto.TokenDTO;
import com.agencia.pagos.payment.BankAccount;
import com.agencia.pagos.shared.money.Currency;
import com.agencia.pagos.trip.Installment;
import com.agencia.pagos.trip.InstallmentStatus;
import com.agencia.pagos.payment.PaymentMethod;
import com.agencia.pagos.payment.PaymentOutcomeStatus;
import com.agencia.pagos.payment.PaymentSubmission;
import com.agencia.pagos.payment.PaymentSubmissionStatus;
import com.agencia.pagos.user.Student;
import com.agencia.pagos.trip.Trip;
import com.agencia.pagos.user.User;
import com.agencia.pagos.payment.BankAccountRepository;
import com.agencia.pagos.trip.InstallmentRepository;
import com.agencia.pagos.payment.PaymentSubmissionRepository;
import com.agencia.pagos.user.StudentRepository;
import com.agencia.pagos.trip.TripRepository;
import com.agencia.pagos.user.UserRepository;
import com.agencia.pagos.payment.storage.PaymentAttachmentStorageService;
import com.agencia.pagos.testsupport.ControllerIntegrationTestSupport;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mail.javamail.JavaMailSender;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
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

    @MockBean
    private ExchangeRateService exchangeRateService;

    @Autowired
    private EntityManager entityManager;

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

    @Autowired
    private PaymentAllocationRepository paymentAllocationRepository;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private PaymentService paymentService;

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
                .andExpect(jsonPath("$[0].reportedAmount").value("250.00"))
                .andExpect(jsonPath("$[0].fileKey").value("https://backend.example/api/v1/payment-attachments/admin-review-token"))
                .andExpect(jsonPath("$[0].allocations.length()").value(3))
                .andExpect(jsonPath("$[0].allocations[2].amountInTripCurrency").value("50.00"));
    }

    @Test
    void previewRegisterReload_preservesQuoteIdentityWithoutProviderRefetch() throws Exception {
        LocalDate requestedDate = LocalDate.now();
        LocalDate effectiveDate = requestedDate.minusDays(1);
        ExchangeRateQuote quote = new ExchangeRateQuote(
                new BigDecimal("1234.567"),
                requestedDate,
                effectiveDate,
                "official-closing",
                "argentinadatos.com",
                "2026-09-18T15:30:00Z"
        );
        PaymentFixture fixture = createPaymentFixture("payment-quote-reload", Currency.USD);
        Installment installment = createInstallment(
                fixture.trip(), fixture.user(), fixture.student(), 1, "100.00", InstallmentStatus.YELLOW);
        BankAccount bankAccount = createBankAccount(Currency.ARS);
        given(exchangeRateService.getOfficialQuoteForDate(requestedDate)).willReturn(quote);

        String previewBody = mockMvc.perform(post("/api/v1/payments/preview")
                        .header("Authorization", "Bearer " + fixture.userTokens().accessToken())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "anchorInstallmentId": %d,
                                  "reportedAmount": 123456.70,
                                  "reportedPaymentDate": "%s",
                                  "paymentCurrency": "ARS"
                                }
                                """.formatted(installment.getId(), requestedDate)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.exchangeRate").value("1234.567"))
                .andExpect(jsonPath("$.quoteRequestedDate").value(requestedDate.toString()))
                .andExpect(jsonPath("$.quoteEffectiveDate").value(effectiveDate.toString()))
                .andExpect(jsonPath("$.quoteSource").value("official-closing"))
                .andExpect(jsonPath("$.quoteProvider").value("argentinadatos.com"))
                .andExpect(jsonPath("$.quoteProviderTimestamp").value("2026-09-18T15:30:00Z"))
                .andExpect(jsonPath("$.calculationVersion").value("2"))
                .andReturn()
                .getResponse()
                .getContentAsString();

        String previewToken = objectMapper.readTree(previewBody).path("previewToken").asText();
        org.mockito.Mockito.clearInvocations(exchangeRateService);

        String registrationBody = mockMvc.perform(multipart("/api/v1/payments")
                        .header("Authorization", "Bearer " + fixture.userTokens().accessToken())
                        .param("anchorInstallmentId", String.valueOf(installment.getId()))
                        .param("reportedAmount", "123456.70")
                        .param("reportedPaymentDate", requestedDate.toString())
                        .param("paymentCurrency", "ARS")
                        .param("paymentMethod", "BANK_TRANSFER")
                        .param("bankAccountId", String.valueOf(bankAccount.getId()))
                        .param("previewToken", previewToken)
                        .with(request -> {
                            request.setMethod("POST");
                            return request;
                        }))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.exchangeRate").value("1234.567"))
                .andExpect(jsonPath("$.quoteSource").value("official-closing"))
                .andExpect(jsonPath("$.quoteProvider").value("argentinadatos.com"))
                .andExpect(jsonPath("$.calculationVersion").value("2"))
                .andReturn()
                .getResponse()
                .getContentAsString();

        org.mockito.Mockito.verify(exchangeRateService, org.mockito.Mockito.never())
                .getOfficialQuoteForDate(org.mockito.ArgumentMatchers.any(LocalDate.class));

        Long submissionId = objectMapper.readTree(registrationBody).path("submissionId").asLong();
        transactionTemplate.executeWithoutResult(status -> {
            paymentSubmissionRepository.flush();
            entityManager.clear();
        });
        PaymentSubmission reloaded = paymentSubmissionRepository.findById(submissionId).orElseThrow();

        assertThat(reloaded.getExchangeRate()).isEqualByComparingTo("1234.567");
        assertThat(reloaded.getExchangeRateScale()).isEqualTo(3);
        assertThat(reloaded.getExchangeRateRequestedDate()).isEqualTo(requestedDate);
        assertThat(reloaded.getExchangeRateEffectiveDate()).isEqualTo(effectiveDate);
        assertThat(reloaded.getExchangeRateSource()).isEqualTo("official-closing");
        assertThat(reloaded.getExchangeRateProvider()).isEqualTo("argentinadatos.com");
        assertThat(reloaded.getExchangeRateProviderTimestamp()).isEqualTo("2026-09-18T15:30:00Z");
        assertThat(reloaded.getCalculationVersion()).isEqualTo("2");

        Map<String, Object> persistedSnapshot = jdbcTemplate.queryForMap("""
                SELECT exchange_rate, exchange_rate_scale, exchange_rate_source,
                       exchange_rate_provider, calculation_version
                FROM payment_submissions
                WHERE id = ?
                """, submissionId);
        assertThat((BigDecimal) persistedSnapshot.get("exchange_rate"))
                .isEqualByComparingTo("1234.567");
        assertThat(persistedSnapshot.get("exchange_rate_scale")).isEqualTo(3);
        assertThat(persistedSnapshot.get("exchange_rate_source")).isEqualTo("official-closing");
        assertThat(persistedSnapshot.get("exchange_rate_provider")).isEqualTo("argentinadatos.com");
        assertThat(persistedSnapshot.get("calculation_version")).isEqualTo("2");
    }

    @Test
    void quoteIdentity_survivesReloadFullPartialReviewAndVoid() throws Exception {
        LocalDate paymentDate = LocalDate.now();
        ExchangeRateQuote quote = new ExchangeRateQuote(
                new BigDecimal("1234.567"),
                paymentDate,
                paymentDate.minusDays(1),
                "official-closing",
                "argentinadatos.com",
                "2026-09-19T12:00:00Z");

        RegisteredSnapshot full = registerQuotedSnapshot(
                "quote-full-review", "100.00", "123456.70", quote);
        paymentService.reviewPayment(
                full.submissionId(),
                new ReviewPaymentDTO(new BigDecimal("123456.70"), null),
                "admin@test.com");
        assertPersistedSnapshot(full.submissionId(), quote, PaymentSubmissionStatus.RESOLVED);
        paymentService.voidPayment(full.submissionId(), "admin@test.com");
        assertPersistedSnapshot(full.submissionId(), quote, PaymentSubmissionStatus.VOIDED);

        RegisteredSnapshot partial = registerQuotedSnapshot(
                "quote-partial-review", "100.00", "123456.70", quote);
        paymentService.reviewPayment(
                partial.submissionId(),
                new ReviewPaymentDTO(new BigDecimal("61728.35"), "Partial approval contract"),
                "admin@test.com");
        assertPersistedSnapshot(partial.submissionId(), quote, PaymentSubmissionStatus.RESOLVED);
        paymentService.voidPayment(partial.submissionId(), "admin@test.com");
        assertPersistedSnapshot(partial.submissionId(), quote, PaymentSubmissionStatus.VOIDED);

        org.mockito.Mockito.verify(exchangeRateService, org.mockito.Mockito.times(2))
                .getOfficialQuoteForDate(paymentDate);
    }

    @Test
    void decimalStrings_preservePersistedScaleAcrossHistoryAndPendingReview() throws Exception {
        LocalDate paymentDate = LocalDate.now();
        ExchangeRateQuote quote = new ExchangeRateQuote(
                new BigDecimal("1015.50"),
                paymentDate,
                paymentDate,
                "official",
                "provider-a",
                "2026-09-19T12:00:00Z");
        RegisteredSnapshot registered = registerQuotedSnapshot(
                "decimal-history", "0.01", "10.16", quote);

        var pending = paymentService.getPendingReviewReceipts().stream()
                .filter(item -> item.submissionId().equals(registered.submissionId()))
                .findFirst()
                .orElseThrow();
        var pendingJson = objectMapper.valueToTree(pending);
        assertThat(pendingJson.path("reportedAmount").asText()).isEqualTo("10.16");
        assertThat(pendingJson.path("amountInTripCurrency").asText()).isEqualTo("0.01");
        assertThat(pendingJson.path("exchangeRate").asText()).isEqualTo("1015.50");
        assertThat(pendingJson.path("quoteProvider").asText()).isEqualTo("provider-a");

        paymentService.reviewPayment(
                registered.submissionId(),
                new ReviewPaymentDTO(new BigDecimal("10.16"), null),
                "admin@test.com");
        var approvedHistory = paymentService.getReceiptsForInstallment(registered.installmentId());
        assertThat(approvedHistory).hasSize(1);
        var approvedJson = objectMapper.valueToTree(approvedHistory.getFirst());
        assertThat(approvedJson.path("reportedAmount").asText()).isEqualTo("10.16");
        assertThat(approvedJson.path("amountInTripCurrency").asText()).isEqualTo("0.01");
        assertThat(approvedJson.path("exchangeRate").asText()).isEqualTo("1015.50");
        assertThat(approvedJson.path("quoteProvider").asText()).isEqualTo("provider-a");

        paymentService.voidPayment(registered.submissionId(), "admin@test.com");
        var voidedHistory = paymentService.getReceiptsForInstallment(registered.installmentId());
        assertThat(voidedHistory).hasSize(1);
        var voidedJson = objectMapper.valueToTree(voidedHistory.getFirst());
        assertThat(voidedJson.path("exchangeRate").asText()).isEqualTo("1015.50");
        assertThat(voidedJson.path("quoteProvider").asText()).isEqualTo("provider-a");
    }

    @Test
    void legacyPendingReview_usesPersistedRateAndLabelsReconciliationWithoutProviderCall() throws Exception {
        LegacyPendingFixture legacy = createLegacyPendingFixture(
                "legacy-pending-review", "legacy-pending-receipt");
        org.mockito.Mockito.clearInvocations(exchangeRateService);

        paymentService.reviewPayment(
                legacy.submissionId(),
                new ReviewPaymentDTO(new BigDecimal("10.16"), null),
                "admin@test.com");
        paymentSubmissionRepository.flush();
        entityManager.clear();

        PaymentSubmission reviewed = paymentSubmissionRepository
                .findByIdWithContext(legacy.submissionId()).orElseThrow();
        PaymentOutcome approved = reviewed.getOutcomes().stream()
                .filter(outcome -> outcome.getStatus() == PaymentOutcomeStatus.APPROVED)
                .findFirst()
                .orElseThrow();
        assertThat(reviewed.getExchangeRate()).isEqualByComparingTo("1015.50");
        assertThat(reviewed.getCalculationVersion()).isEqualTo("v1");
        assertThat(approved.getAdminObservation())
                .isEqualTo("Aprobación conciliada con los valores históricos v1 persistidos");
        assertThat(approved.getReportedAmount()).isEqualByComparingTo("10.16");
        assertThat(approved.getAmountInTripCurrency()).isEqualByComparingTo("0.01");
        org.mockito.Mockito.verify(exchangeRateService, org.mockito.Mockito.never())
                .getOfficialQuoteForDate(org.mockito.ArgumentMatchers.any(LocalDate.class));
        org.mockito.Mockito.verify(exchangeRateService, org.mockito.Mockito.never())
                .getOfficialRateForDate(org.mockito.ArgumentMatchers.any(LocalDate.class));
    }

    @Test
    void legacyPendingReview_rejectsExplicitlyWithoutReconstructingRate() throws Exception {
        LegacyPendingFixture legacy = createLegacyPendingFixture(
                "legacy-pending-reject", "legacy-pending-rejected-receipt");
        org.mockito.Mockito.clearInvocations(exchangeRateService);

        paymentService.reviewPayment(
                legacy.submissionId(),
                new ReviewPaymentDTO(new BigDecimal("0.00"), "Rechazo explícito de pago histórico v1"),
                "admin@test.com");
        paymentSubmissionRepository.flush();
        entityManager.clear();

        PaymentSubmission reviewed = paymentSubmissionRepository
                .findByIdWithContext(legacy.submissionId()).orElseThrow();
        PaymentOutcome rejected = reviewed.getOutcomes().stream()
                .filter(outcome -> outcome.getStatus() == PaymentOutcomeStatus.REJECTED)
                .findFirst()
                .orElseThrow();
        assertThat(reviewed.getExchangeRate()).isEqualByComparingTo("1015.50");
        assertThat(reviewed.getCalculationVersion()).isEqualTo("v1");
        assertThat(rejected.getAdminObservation()).isEqualTo("Rechazo explícito de pago histórico v1");
        assertThat(installmentRepository.findById(legacy.installmentId()).orElseThrow().getPaidAmount())
                .isEqualByComparingTo("0.00");
        org.mockito.Mockito.verify(exchangeRateService, org.mockito.Mockito.never())
                .getOfficialQuoteForDate(org.mockito.ArgumentMatchers.any(LocalDate.class));
        org.mockito.Mockito.verify(exchangeRateService, org.mockito.Mockito.never())
                .getOfficialRateForDate(org.mockito.ArgumentMatchers.any(LocalDate.class));
    }

    @Test
    void voidReversesPersistedAllocationsExactlyWithoutRewritingApprovedHistory() throws Exception {
        LocalDate paymentDate = LocalDate.now();
        ExchangeRateQuote quote = new ExchangeRateQuote(
                new BigDecimal("3.000"),
                paymentDate,
                paymentDate,
                "official-closing",
                "provider-a",
                "2026-09-19T12:00:00Z");
        PaymentFixture fixture = createPaymentFixture("void-persisted-allocations", Currency.ARS);
        Installment first = createInstallment(
                fixture.trip(), fixture.user(), fixture.student(), 1, "100.01", InstallmentStatus.YELLOW);
        Installment second = createInstallment(
                fixture.trip(), fixture.user(), fixture.student(), 2, "100.00", InstallmentStatus.YELLOW);
        BankAccount bankAccount = createBankAccount(Currency.USD);
        given(exchangeRateService.getOfficialQuoteForDate(paymentDate)).willReturn(quote);

        PaymentBatchPreviewDTO preview = paymentService.previewPayment(
                new PaymentPreviewRequestDTO(first.getId(), new BigDecimal("66.67"), paymentDate, Currency.USD),
                fixture.user().getEmail());
        PaymentSubmissionDTO registered = paymentService.registerPayment(
                new RegisterPaymentDTO(
                        first.getId(),
                        new BigDecimal("66.67"),
                        paymentDate,
                        Currency.USD,
                        PaymentMethod.BANK_TRANSFER,
                        bankAccount.getId(),
                        preview.previewToken()),
                fixture.user().getEmail());
        paymentService.reviewPayment(
                registered.submissionId(),
                new ReviewPaymentDTO(new BigDecimal("66.67"), null),
                "admin@test.com");
        paymentSubmissionRepository.flush();
        entityManager.clear();

        PaymentSubmission approvedSubmission = paymentSubmissionRepository
                .findByIdWithContext(registered.submissionId()).orElseThrow();
        PaymentOutcome approvedOutcome = approvedSubmission.getOutcomes().stream()
                .filter(outcome -> outcome.getStatus() == PaymentOutcomeStatus.APPROVED)
                .findFirst()
                .orElseThrow();
        Long approvedOutcomeId = approvedOutcome.getId();
        Map<Long, BigDecimal> persistedTripAllocations = approvedOutcome.getAllocations().stream()
                .collect(java.util.stream.Collectors.toMap(
                        allocation -> allocation.getInstallment().getId(),
                        PaymentAllocation::getAmountInTripCurrency));
        Map<Long, BigDecimal> persistedReportedAllocations = approvedOutcome.getAllocations().stream()
                .collect(java.util.stream.Collectors.toMap(
                        allocation -> allocation.getInstallment().getId(),
                        PaymentAllocation::getReportedAmount));
        org.mockito.Mockito.clearInvocations(exchangeRateService);

        paymentService.voidPayment(registered.submissionId(), "void-admin@test.com");
        paymentSubmissionRepository.flush();
        entityManager.clear();

        assertThat(installmentRepository.findById(first.getId()).orElseThrow().getPaidAmount())
                .isEqualByComparingTo("0.00");
        assertThat(installmentRepository.findById(second.getId()).orElseThrow().getPaidAmount())
                .isEqualByComparingTo("0.00");
        PaymentSubmission voidedSubmission = paymentSubmissionRepository
                .findByIdWithContext(registered.submissionId()).orElseThrow();
        PaymentOutcome preservedApprovedOutcome = voidedSubmission.getOutcomes().stream()
                .filter(outcome -> outcome.getId().equals(approvedOutcomeId))
                .findFirst()
                .orElseThrow();
        assertThat(preservedApprovedOutcome.getStatus()).isEqualTo(PaymentOutcomeStatus.APPROVED);
        assertThat(preservedApprovedOutcome.getReportedAmount()).isEqualByComparingTo("66.67");
        assertThat(preservedApprovedOutcome.getAmountInTripCurrency()).isEqualByComparingTo("200.01");
        assertThat(preservedApprovedOutcome.getAllocations()).allSatisfy(allocation -> {
            assertThat(allocation.getAmountInTripCurrency())
                    .isEqualByComparingTo(persistedTripAllocations.get(allocation.getInstallment().getId()));
            assertThat(allocation.getReportedAmount())
                    .isEqualByComparingTo(persistedReportedAllocations.get(allocation.getInstallment().getId()));
        });
        PaymentOutcome voidOutcome = voidedSubmission.getOutcomes().stream()
                .filter(outcome -> outcome.getStatus() == PaymentOutcomeStatus.VOIDED)
                .findFirst()
                .orElseThrow();
        assertThat(voidOutcome.getReportedAmount()).isEqualByComparingTo("66.67");
        assertThat(voidOutcome.getAmountInTripCurrency()).isEqualByComparingTo("200.01");
        assertThat(paymentAllocationRepository.count()).isEqualTo(2);
        org.mockito.Mockito.verify(exchangeRateService, org.mockito.Mockito.never())
                .getOfficialQuoteForDate(org.mockito.ArgumentMatchers.any(LocalDate.class));
        org.mockito.Mockito.verify(exchangeRateService, org.mockito.Mockito.never())
                .getOfficialRateForDate(org.mockito.ArgumentMatchers.any(LocalDate.class));
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
        // The approved outcome remains immutable and one separate VOIDED audit outcome is
        // created. The conflicting request must not create a duplicate reversal outcome.
        assertThat(paymentOutcomeRepository.findAll()).hasSize(2);
        assertThat(paymentOutcomeRepository.findAll())
                .extracting(PaymentOutcome::getStatus)
                .containsExactlyInAnyOrder(PaymentOutcomeStatus.APPROVED, PaymentOutcomeStatus.VOIDED);
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

    private RegisteredSnapshot registerQuotedSnapshot(
            String prefix,
            String installmentAmount,
            String reportedAmount,
            ExchangeRateQuote quote
    ) throws Exception {
        PaymentFixture fixture = createPaymentFixture(prefix, Currency.USD);
        Installment installment = createInstallment(
                fixture.trip(), fixture.user(), fixture.student(), 1, installmentAmount, InstallmentStatus.YELLOW);
        BankAccount bankAccount = createBankAccount(Currency.ARS);
        given(exchangeRateService.getOfficialQuoteForDate(quote.requestedDate())).willReturn(quote);

        PaymentBatchPreviewDTO preview = paymentService.previewPayment(
                new PaymentPreviewRequestDTO(
                        installment.getId(),
                        new BigDecimal(reportedAmount),
                        quote.requestedDate(),
                        Currency.ARS),
                fixture.user().getEmail());
        PaymentSubmissionDTO registered = paymentService.registerPayment(
                new RegisterPaymentDTO(
                        installment.getId(),
                        new BigDecimal(reportedAmount),
                        quote.requestedDate(),
                        Currency.ARS,
                        PaymentMethod.BANK_TRANSFER,
                        bankAccount.getId(),
                        preview.previewToken()),
                fixture.user().getEmail());

        paymentSubmissionRepository.flush();
        entityManager.clear();
        assertPersistedSnapshot(registered.submissionId(), quote, PaymentSubmissionStatus.PENDING);
        return new RegisteredSnapshot(registered.submissionId(), installment.getId());
    }

    private void assertPersistedSnapshot(
            Long submissionId,
            ExchangeRateQuote quote,
            PaymentSubmissionStatus expectedStatus
    ) {
        entityManager.clear();
        PaymentSubmission reloaded = paymentSubmissionRepository.findById(submissionId).orElseThrow();
        assertThat(reloaded.getStatus()).isEqualTo(expectedStatus);
        assertThat(reloaded.getExchangeRate()).isEqualByComparingTo(quote.sellRate());
        assertThat(reloaded.getExchangeRateScale()).isEqualTo(quote.sellRate().scale());
        assertThat(reloaded.getExchangeRateRequestedDate()).isEqualTo(quote.requestedDate());
        assertThat(reloaded.getExchangeRateEffectiveDate()).isEqualTo(quote.effectiveDate());
        assertThat(reloaded.getExchangeRateSource()).isEqualTo(quote.source());
        assertThat(reloaded.getExchangeRateProvider()).isEqualTo(quote.provider());
        assertThat(reloaded.getExchangeRateProviderTimestamp()).isEqualTo(quote.providerTimestamp());
        assertThat(reloaded.getCalculationVersion()).isEqualTo("2");
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

    private LegacyPendingFixture createLegacyPendingFixture(String prefix, String fileKey) throws Exception {
        PaymentFixture fixture = createPaymentFixture(prefix, Currency.USD);
        Installment installment = createInstallment(
                fixture.trip(), fixture.user(), fixture.student(), 1, "0.01", InstallmentStatus.YELLOW);
        PaymentSubmission legacy = buildPendingSubmission(
                installment,
                createBankAccount(Currency.ARS),
                "10.16",
                "0.01",
                fileKey);
        legacy.setExchangeRate(new BigDecimal("1015.50"));
        legacy.setExchangeRateScale(2);
        legacy.setExchangeRateRequestedDate(LocalDate.now().minusDays(2));
        legacy.setExchangeRateEffectiveDate(LocalDate.now().minusDays(3));
        legacy.setExchangeRateSource("legacy-source");
        legacy.setExchangeRateProvider("legacy-source");
        legacy.setCalculationVersion("v1");
        Long submissionId = paymentSubmissionRepository.saveAndFlush(legacy).getId();
        entityManager.clear();
        return new LegacyPendingFixture(submissionId, installment.getId());
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

    private record RegisteredSnapshot(Long submissionId, Long installmentId) {
    }

    private record LegacyPendingFixture(Long submissionId, Long installmentId) {
    }
}
