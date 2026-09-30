package com.agencia.pagos.payment;

import com.agencia.pagos.TestcontainersConfiguration;
import com.agencia.pagos.payment.dto.PaymentBatchPreviewDTO;
import com.agencia.pagos.payment.dto.PaymentPreviewRequestDTO;
import com.agencia.pagos.payment.dto.PaymentSubmissionDTO;
import com.agencia.pagos.payment.dto.RegisterPaymentDTO;
import com.agencia.pagos.payment.dto.ReviewPaymentDTO;
import com.agencia.pagos.payment.storage.PaymentAttachmentStorageService;
import com.agencia.pagos.shared.money.Currency;
import com.agencia.pagos.testsupport.ControllerIntegrationTestSupport;
import com.agencia.pagos.trip.Installment;
import com.agencia.pagos.trip.InstallmentStatus;
import com.agencia.pagos.trip.Trip;
import com.agencia.pagos.user.Student;
import com.agencia.pagos.user.User;
import com.agencia.pagos.user.dto.TokenDTO;
import com.agencia.pagos.user.dto.UserCreateDTO;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.http.MediaType;
import org.springframework.mail.javamail.JavaMailSender;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Covers administrative amount correction during review of a PENDING payment:
 * the admin may approve an amount higher or lower than the reported one.
 * reportedAmount stays immutable; the APPROVED outcome carries the corrected
 * amount; rejectedAmount is max(reported - approved, 0).
 */
@SpringBootTest(properties = {
        "app.mail.to=test@agencia.com",
        "app.mail.from=no-reply@agencia.com"
})
@AutoConfigureMockMvc
@Import({
        TestcontainersConfiguration.class,
        PaymentReviewAmountCorrectionTest.FixedPaymentBusinessClockConfiguration.class
})
class PaymentReviewAmountCorrectionTest extends ControllerIntegrationTestSupport {

    private static final ZoneId BUSINESS_ZONE = ZoneId.of("America/Argentina/Buenos_Aires");
    private static final Instant BUSINESS_NOW = Instant.parse("2026-01-01T15:00:00Z");
    private static final LocalDate BUSINESS_TODAY = LocalDate.ofInstant(BUSINESS_NOW, BUSINESS_ZONE);
    private static final String REVIEWER = "admin@test.com";

    @TestConfiguration(proxyBeanMethods = false)
    static class FixedPaymentBusinessClockConfiguration {
        @Bean("fixedPaymentBusinessClock")
        @Primary
        @Qualifier("paymentBusinessClock")
        Clock fixedPaymentBusinessClock() {
            return Clock.fixed(BUSINESS_NOW, ZoneOffset.UTC);
        }
    }

    private record PaymentFixture(TokenDTO userTokens, User user, Student student, Trip trip) {}

    @MockBean
    private JavaMailSender javaMailSender;

    @MockBean
    private ExchangeRateService exchangeRateService;

    @MockBean
    private PaymentAttachmentStorageService paymentAttachmentStorageService;

    @Autowired
    private PaymentService paymentService;

    @Test
    void reviewPayment_equalAmount_approvesWithoutObservation() {
        PaymentFixture fixture = createPaymentFixture("correction-equal", Currency.ARS);
        List<Installment> installments = createThreeHundredPending(fixture);
        BankAccount bankAccount = createBankAccount(Currency.ARS);

        PaymentSubmissionDTO registered = registerArsPayment(
                installments.get(0), "300.00", bankAccount, fixture.user().getEmail());

        PaymentSubmissionDTO reviewed = paymentService.reviewPayment(
                registered.submissionId(), new ReviewPaymentDTO(new BigDecimal("300.00"), null), REVIEWER);

        assertEquals("APPROVED", reviewed.status().name());
        assertEquals(0, reviewed.reportedAmount().compareTo(new BigDecimal("300.00")));
        assertEquals(0, reviewed.approvedAmount().compareTo(new BigDecimal("300.00")));
        assertEquals(0, reviewed.rejectedAmount().compareTo(BigDecimal.ZERO));

        PaymentSubmission persisted = paymentSubmissionRepository
                .findByIdWithContext(registered.submissionId()).orElseThrow();
        assertEquals(0, persisted.getReportedAmount().compareTo(new BigDecimal("300.00")));
        assertEquals(1, persisted.getOutcomes().size());
        PaymentOutcome approved = singleOutcome(persisted, PaymentOutcomeStatus.APPROVED);
        assertEquals(0, approved.getReportedAmount().compareTo(new BigDecimal("300.00")));
        assertEquals(0, totalPaid(installments).compareTo(new BigDecimal("300.00")));
    }

    @Test
    void reviewPayment_downwardCorrection_partiallyApproves() {
        PaymentFixture fixture = createPaymentFixture("correction-down", Currency.ARS);
        List<Installment> installments = createThreeHundredPending(fixture);
        BankAccount bankAccount = createBankAccount(Currency.ARS);

        PaymentSubmissionDTO registered = registerArsPayment(
                installments.get(0), "300.00", bankAccount, fixture.user().getEmail());

        PaymentSubmissionDTO reviewed = paymentService.reviewPayment(
                registered.submissionId(),
                new ReviewPaymentDTO(new BigDecimal("240.00"), "El comprobante corresponde a $240.00 reales"),
                REVIEWER);

        assertEquals("PARTIALLY_APPROVED", reviewed.status().name());
        assertEquals(0, reviewed.reportedAmount().compareTo(new BigDecimal("300.00")));
        assertEquals(0, reviewed.approvedAmount().compareTo(new BigDecimal("240.00")));
        assertEquals(0, reviewed.rejectedAmount().compareTo(new BigDecimal("60.00")));

        PaymentSubmission persisted = paymentSubmissionRepository
                .findByIdWithContext(registered.submissionId()).orElseThrow();
        assertEquals(0, persisted.getReportedAmount().compareTo(new BigDecimal("300.00")));
        PaymentOutcome approved = singleOutcome(persisted, PaymentOutcomeStatus.APPROVED);
        PaymentOutcome rejected = singleOutcome(persisted, PaymentOutcomeStatus.REJECTED);
        assertEquals(0, approved.getReportedAmount().compareTo(new BigDecimal("240.00")));
        assertEquals(0, rejected.getReportedAmount().compareTo(new BigDecimal("60.00")));
        assertEquals(0, totalPaid(installments).compareTo(new BigDecimal("240.00")));
    }

    @Test
    void reviewPayment_upwardCorrection_approvesCorrectedAmount() {
        PaymentFixture fixture = createPaymentFixture("correction-up", Currency.ARS);
        List<Installment> installments = createThreeHundredPending(fixture);
        BankAccount bankAccount = createBankAccount(Currency.ARS);

        PaymentSubmissionDTO registered = registerArsPayment(
                installments.get(0), "240.00", bankAccount, fixture.user().getEmail());

        PaymentSubmissionDTO reviewed = paymentService.reviewPayment(
                registered.submissionId(),
                new ReviewPaymentDTO(new BigDecimal("300.00"), "El depósito bancario acreditado fue de $300.00"),
                REVIEWER);

        assertEquals("APPROVED", reviewed.status().name());
        assertEquals(0, reviewed.reportedAmount().compareTo(new BigDecimal("240.00")));
        assertEquals(0, reviewed.approvedAmount().compareTo(new BigDecimal("300.00")));
        assertEquals(0, reviewed.rejectedAmount().compareTo(BigDecimal.ZERO));

        PaymentSubmission persisted = paymentSubmissionRepository
                .findByIdWithContext(registered.submissionId()).orElseThrow();
        assertEquals(0, persisted.getReportedAmount().compareTo(new BigDecimal("240.00")));
        PaymentOutcome approved = singleOutcome(persisted, PaymentOutcomeStatus.APPROVED);
        assertEquals(0, approved.getReportedAmount().compareTo(new BigDecimal("300.00")));
        assertEquals(0, approved.getAmountInTripCurrency().compareTo(new BigDecimal("300.00")));
        assertEquals("El depósito bancario acreditado fue de $300.00", approved.getAdminObservation());
        assertEquals(REVIEWER, approved.getResolvedByEmail());
        assertNotNull(approved.getResolvedAt());
        assertTrue(persisted.getOutcomes().stream()
                .noneMatch(outcome -> outcome.getStatus() == PaymentOutcomeStatus.REJECTED));
        assertEquals(0, allocationTripTotal(approved).compareTo(new BigDecimal("300.00")));
        assertEquals(0, totalPaid(installments).compareTo(new BigDecimal("300.00")));
    }

    @Test
    void reviewPayment_upwardCorrectionWithoutObservation_rejectsWithoutSideEffects() {
        PaymentFixture fixture = createPaymentFixture("correction-up-noobs", Currency.ARS);
        List<Installment> installments = createThreeHundredPending(fixture);
        BankAccount bankAccount = createBankAccount(Currency.ARS);

        PaymentSubmissionDTO registered = registerArsPayment(
                installments.get(0), "240.00", bankAccount, fixture.user().getEmail());

        assertThrows(IllegalStateException.class, () -> paymentService.reviewPayment(
                registered.submissionId(), new ReviewPaymentDTO(new BigDecimal("300.00"), "  "), REVIEWER));

        assertPendingWithoutOutcomes(registered.submissionId(), installments);
    }

    @Test
    void reviewPayment_downwardCorrectionWithoutObservation_rejectsWithoutSideEffects() {
        PaymentFixture fixture = createPaymentFixture("correction-down-noobs", Currency.ARS);
        List<Installment> installments = createThreeHundredPending(fixture);
        BankAccount bankAccount = createBankAccount(Currency.ARS);

        PaymentSubmissionDTO registered = registerArsPayment(
                installments.get(0), "300.00", bankAccount, fixture.user().getEmail());

        assertThrows(IllegalStateException.class, () -> paymentService.reviewPayment(
                registered.submissionId(), new ReviewPaymentDTO(new BigDecimal("240.00"), null), REVIEWER));

        assertPendingWithoutOutcomes(registered.submissionId(), installments);
    }

    @Test
    void reviewPayment_upwardCorrectionExceedingBalance_rejectsAtomically() {
        PaymentFixture fixture = createPaymentFixture("correction-exceeds", Currency.ARS);
        List<Installment> installments = createThreeHundredPending(fixture);
        BankAccount bankAccount = createBankAccount(Currency.ARS);

        PaymentSubmissionDTO registered = registerArsPayment(
                installments.get(0), "240.00", bankAccount, fixture.user().getEmail());

        assertThrows(IllegalStateException.class, () -> paymentService.reviewPayment(
                registered.submissionId(),
                new ReviewPaymentDTO(new BigDecimal("400.00"), "Corrección que excede el saldo"),
                REVIEWER));

        assertPendingWithoutOutcomes(registered.submissionId(), installments);
    }

    @Test
    void reviewPayment_upwardCrossCurrencyCorrection_usesSnapshotRate() {
        LocalDate paymentDate = BUSINESS_TODAY;
        PaymentFixture fixture = createPaymentFixture("correction-up-fx", Currency.ARS);
        List<Installment> installments = createThreeHundredPending(fixture);
        BankAccount bankAccount = createBankAccount(Currency.USD);
        given(exchangeRateService.getOfficialQuoteForDate(paymentDate)).willReturn(new ExchangeRateQuote(
                new BigDecimal("1000"), paymentDate, paymentDate, "official", "provider-a", null));

        PaymentBatchPreviewDTO preview = paymentService.previewPayment(
                new PaymentPreviewRequestDTO(
                        installments.get(0).getId(), new BigDecimal("0.24"), paymentDate, Currency.USD),
                fixture.user().getEmail());
        PaymentSubmissionDTO registered = paymentService.registerPayment(
                new RegisterPaymentDTO(
                        installments.get(0).getId(),
                        new BigDecimal("0.24"),
                        paymentDate,
                        Currency.USD,
                        PaymentMethod.BANK_TRANSFER,
                        bankAccount.getId(),
                        preview.previewToken()),
                fixture.user().getEmail());

        org.mockito.Mockito.clearInvocations(exchangeRateService);
        PaymentSubmissionDTO reviewed = paymentService.reviewPayment(
                registered.submissionId(),
                new ReviewPaymentDTO(new BigDecimal("0.30"), "El banco acreditó USD 0.30"),
                REVIEWER);

        assertEquals("APPROVED", reviewed.status().name());
        assertEquals(0, reviewed.reportedAmount().compareTo(new BigDecimal("0.24")));
        assertEquals(0, reviewed.approvedAmount().compareTo(new BigDecimal("0.30")));
        assertEquals(0, reviewed.rejectedAmount().compareTo(BigDecimal.ZERO));

        PaymentSubmission persisted = paymentSubmissionRepository
                .findByIdWithContext(registered.submissionId()).orElseThrow();
        PaymentOutcome approved = singleOutcome(persisted, PaymentOutcomeStatus.APPROVED);
        assertEquals(0, approved.getReportedAmount().compareTo(new BigDecimal("0.30")));
        assertEquals(0, approved.getAmountInTripCurrency().compareTo(new BigDecimal("300.00")));
        assertEquals(0, totalPaid(installments).compareTo(new BigDecimal("300.00")));
        verify(exchangeRateService, never())
                .getOfficialQuoteForDate(org.mockito.ArgumentMatchers.any(LocalDate.class));
    }

    @Test
    void reviewPayment_legacySameCurrencyUpwardCorrection_preservesAdminObservation() {
        PaymentFixture fixture = createPaymentFixture("correction-legacy-up", Currency.ARS);
        List<Installment> installments = createThreeHundredPending(fixture);
        BankAccount bankAccount = createBankAccount(Currency.ARS);
        PaymentSubmission legacy = buildLegacySubmission(
                installments.get(0), bankAccount, "240.00", "240.00");
        String adminReason = "El depósito real fue de 300.";

        PaymentSubmissionDTO reviewed = paymentService.reviewPayment(
                legacy.getId(), new ReviewPaymentDTO(new BigDecimal("300.00"), adminReason), REVIEWER);

        assertEquals("APPROVED", reviewed.status().name());
        assertEquals(0, reviewed.reportedAmount().compareTo(new BigDecimal("240.00")));
        assertEquals(0, reviewed.approvedAmount().compareTo(new BigDecimal("300.00")));
        assertEquals(0, reviewed.rejectedAmount().compareTo(BigDecimal.ZERO));

        PaymentSubmission persisted = paymentSubmissionRepository
                .findByIdWithContext(legacy.getId()).orElseThrow();
        PaymentOutcome approved = singleOutcome(persisted, PaymentOutcomeStatus.APPROVED);
        assertTrue(approved.getAdminObservation() != null
                && approved.getAdminObservation().contains(adminReason));
        assertTrue(reviewed.adminObservation() != null
                && reviewed.adminObservation().contains(adminReason));
        assertEquals(0, totalPaid(installments).compareTo(new BigDecimal("300.00")));
    }

    @Test
    void reviewPayment_legacySameCurrencyDownwardCorrection_historyShowsAdminObservation() {
        PaymentFixture fixture = createPaymentFixture("correction-legacy-down", Currency.ARS);
        List<Installment> installments = createThreeHundredPending(fixture);
        BankAccount bankAccount = createBankAccount(Currency.ARS);
        PaymentSubmission legacy = buildLegacySubmission(
                installments.get(0), bankAccount, "300.00", "300.00");
        String adminReason = "El comprobante corresponde a 240 reales.";

        PaymentSubmissionDTO reviewed = paymentService.reviewPayment(
                legacy.getId(), new ReviewPaymentDTO(new BigDecimal("240.00"), adminReason), REVIEWER);

        assertEquals("PARTIALLY_APPROVED", reviewed.status().name());
        assertEquals(adminReason, reviewed.adminObservation());
        assertEquals(0, totalPaid(installments).compareTo(new BigDecimal("240.00")));
    }

    @Test
    void reviewPayment_maxMoneyAmount_approvesWhenBalanceAllows() {
        PaymentFixture fixture = createPaymentFixture("correction-max", Currency.ARS);
        Installment anchor = createInstallment(
                fixture.trip(), fixture.user(), fixture.student(), 1, "99999999.99", InstallmentStatus.YELLOW);
        BankAccount bankAccount = createBankAccount(Currency.ARS);
        PaymentSubmissionDTO registered = registerArsPayment(
                anchor, "99999999.99", bankAccount, fixture.user().getEmail());

        PaymentSubmissionDTO reviewed = paymentService.reviewPayment(
                registered.submissionId(), new ReviewPaymentDTO(new BigDecimal("99999999.99"), null), REVIEWER);

        assertEquals("APPROVED", reviewed.status().name());
        assertEquals(0, reviewed.approvedAmount().compareTo(new BigDecimal("99999999.99")));
        assertEquals(0, reviewed.rejectedAmount().compareTo(BigDecimal.ZERO));
        assertEquals(0, totalPaid(List.of(anchor)).compareTo(new BigDecimal("99999999.99")));
    }

    @Test
    void reviewPayment_aboveMaxMoney_rejectsBeforePersisting() {
        PaymentFixture fixture = createPaymentFixture("correction-over-max", Currency.ARS);
        List<Installment> installments = createThreeHundredPending(fixture);
        BankAccount bankAccount = createBankAccount(Currency.ARS);
        PaymentSubmissionDTO registered = registerArsPayment(
                installments.get(0), "240.00", bankAccount, fixture.user().getEmail());

        assertThrows(IllegalArgumentException.class, () -> paymentService.reviewPayment(
                registered.submissionId(),
                new ReviewPaymentDTO(new BigDecimal("100000000.00"), "Intento sobre el máximo persistible"),
                REVIEWER));

        assertPendingWithoutOutcomes(registered.submissionId(), installments);
    }

    @Test
    void reviewPayment_observationExactly500Chars_accepted() {
        PaymentFixture fixture = createPaymentFixture("correction-obs-500", Currency.ARS);
        List<Installment> installments = createThreeHundredPending(fixture);
        BankAccount bankAccount = createBankAccount(Currency.ARS);
        PaymentSubmissionDTO registered = registerArsPayment(
                installments.get(0), "300.00", bankAccount, fixture.user().getEmail());

        PaymentSubmissionDTO reviewed = paymentService.reviewPayment(
                registered.submissionId(),
                new ReviewPaymentDTO(new BigDecimal("240.00"), "x".repeat(500)),
                REVIEWER);

        assertEquals("PARTIALLY_APPROVED", reviewed.status().name());
        assertEquals(500, reviewed.adminObservation().length());
    }

    @Test
    void reviewPayment_observationOver500Chars_rejectsWithoutSideEffects() {
        PaymentFixture fixture = createPaymentFixture("correction-obs-501", Currency.ARS);
        List<Installment> installments = createThreeHundredPending(fixture);
        BankAccount bankAccount = createBankAccount(Currency.ARS);
        PaymentSubmissionDTO registered = registerArsPayment(
                installments.get(0), "300.00", bankAccount, fixture.user().getEmail());

        assertThrows(IllegalArgumentException.class, () -> paymentService.reviewPayment(
                registered.submissionId(),
                new ReviewPaymentDTO(new BigDecimal("240.00"), "x".repeat(501)),
                REVIEWER));

        assertPendingWithoutOutcomes(registered.submissionId(), installments);
    }

    @Test
    void reviewPayment_observationOver500Chars_returnsBadRequest() throws Exception {
        PaymentFixture fixture = createPaymentFixture("correction-obs-501-api", Currency.ARS);
        List<Installment> installments = createThreeHundredPending(fixture);
        BankAccount bankAccount = createBankAccount(Currency.ARS);
        PaymentSubmissionDTO registered = registerArsPayment(
                installments.get(0), "300.00", bankAccount, fixture.user().getEmail());
        TokenDTO adminTokens = signUpAdmin(buildValidUser("correction-obs-501-admin"));

        mockMvc.perform(patch("/api/v1/payments/{id}/review", registered.submissionId())
                        .header("Authorization", "Bearer " + adminTokens.accessToken())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"approvedAmount\": 240.00, \"adminObservation\": \""
                                + "x".repeat(501) + "\"}"))
                .andExpect(status().isBadRequest());

        assertPendingWithoutOutcomes(registered.submissionId(), installments);
    }

    @Test
    void voidPayment_afterUpwardCorrection_reversesCorrectedAllocations() {
        PaymentFixture fixture = createPaymentFixture("correction-up-void", Currency.ARS);
        List<Installment> installments = createThreeHundredPending(fixture);
        BankAccount bankAccount = createBankAccount(Currency.ARS);

        PaymentSubmissionDTO registered = registerArsPayment(
                installments.get(0), "240.00", bankAccount, fixture.user().getEmail());
        paymentService.reviewPayment(
                registered.submissionId(),
                new ReviewPaymentDTO(new BigDecimal("300.00"), "El depósito bancario acreditado fue de $300.00"),
                REVIEWER);
        assertEquals(0, totalPaid(installments).compareTo(new BigDecimal("300.00")));

        PaymentSubmissionDTO voided = paymentService.voidPayment(registered.submissionId(), REVIEWER);

        assertEquals("VOIDED", voided.status().name());
        assertEquals(0, totalPaid(installments).compareTo(BigDecimal.ZERO));
        PaymentSubmission persisted = paymentSubmissionRepository
                .findByIdWithContext(registered.submissionId()).orElseThrow();
        PaymentOutcome voidOutcome = singleOutcome(persisted, PaymentOutcomeStatus.VOIDED);
        assertEquals(0, voidOutcome.getReportedAmount().compareTo(new BigDecimal("300.00")));
        assertEquals(0, voidOutcome.getAmountInTripCurrency().compareTo(new BigDecimal("300.00")));
    }

    private List<Installment> createThreeHundredPending(PaymentFixture fixture) {
        Installment first = createInstallment(
                fixture.trip(), fixture.user(), fixture.student(), 1, "100.00", InstallmentStatus.YELLOW);
        Installment second = createInstallment(
                fixture.trip(), fixture.user(), fixture.student(), 2, "100.00", InstallmentStatus.YELLOW);
        Installment third = createInstallment(
                fixture.trip(), fixture.user(), fixture.student(), 3, "100.00", InstallmentStatus.YELLOW);
        return List.of(first, second, third);
    }

    private PaymentSubmissionDTO registerArsPayment(
            Installment anchor, String amount, BankAccount bankAccount, String email) {
        return paymentService.registerPayment(
                anchor.getId(),
                new BigDecimal(amount),
                BUSINESS_TODAY,
                Currency.ARS,
                PaymentMethod.BANK_TRANSFER,
                bankAccount.getId(),
                null,
                email);
    }

    private PaymentSubmission buildLegacySubmission(
            Installment anchor, BankAccount bankAccount, String reportedAmount, String amountInTripCurrency) {
        PaymentSubmission submission = new PaymentSubmission();
        submission.setTrip(anchor.getTrip());
        submission.setUser(anchor.getUser());
        submission.setStudent(anchor.getStudent());
        submission.setAnchorInstallment(anchor);
        submission.setBankAccount(bankAccount);
        submission.setReportedAmount(new BigDecimal(reportedAmount));
        submission.setPaymentCurrency(Currency.ARS);
        submission.setExchangeRate(null);
        submission.setAmountInTripCurrency(new BigDecimal(amountInTripCurrency));
        submission.setReportedPaymentDate(BUSINESS_TODAY);
        submission.setPaymentMethod(PaymentMethod.BANK_TRANSFER);
        submission.setStatus(PaymentSubmissionStatus.PENDING);
        submission.setFileKey("legacy-test-receipt");
        submission.setCalculationVersion("v1");
        return paymentSubmissionRepository.save(submission);
    }

    private PaymentOutcome singleOutcome(PaymentSubmission submission, PaymentOutcomeStatus status) {
        return submission.getOutcomes().stream()
                .filter(outcome -> outcome.getStatus() == status)
                .findFirst()
                .orElseThrow(() -> new AssertionError("Missing outcome " + status));
    }

    private BigDecimal totalPaid(List<Installment> installments) {
        BigDecimal total = BigDecimal.ZERO;
        for (Installment installment : installments) {
            BigDecimal paid = installmentRepository.findById(installment.getId()).orElseThrow().getPaidAmount();
            total = total.add(paid == null ? BigDecimal.ZERO : paid);
        }
        return total;
    }

    private BigDecimal allocationTripTotal(PaymentOutcome approved) {
        return approved.getAllocations().stream()
                .map(PaymentAllocation::getAmountInTripCurrency)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    private void assertPendingWithoutOutcomes(Long submissionId, List<Installment> installments) {
        PaymentSubmission persisted = paymentSubmissionRepository
                .findByIdWithContext(submissionId).orElseThrow();
        assertEquals(PaymentSubmissionStatus.PENDING, persisted.getStatus());
        assertTrue(persisted.getOutcomes().isEmpty());
        assertEquals(0, totalPaid(installments).compareTo(BigDecimal.ZERO));
    }

    private PaymentFixture createPaymentFixture(String prefix, Currency currency) {
        try {
            UserCreateDTO participantDto = buildValidUser(prefix);
            TokenDTO userTokens = signUp(participantDto);
            User user = userRepository.findByEmail(participantDto.email()).orElseThrow();
            Student student = studentRepository.findByParentId(user.getId()).stream().findFirst().orElseThrow();
            Trip trip = createTripForUser(user, prefix, currency);
            return new PaymentFixture(userTokens, user, student, trip);
        } catch (Exception exception) {
            throw new IllegalStateException("Could not create payment fixture", exception);
        }
    }

    private Trip createTripForUser(User user, String prefix, Currency currency) {
        Trip trip = new Trip();
        trip.setName("Trip correction " + prefix);
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
            InstallmentStatus status) {
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
}
