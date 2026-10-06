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
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;
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
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionSynchronizationManager;

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
 * amount and currency. Rejected money is derived from the trip-cent delta using
 * only the original frozen conversion, never by subtracting unlike currencies.
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

    @Autowired
    private JdbcTemplate jdbc;

    @Test
    void administrativeArsUsesPaymentDateOutsideLocksAndPreservesOriginalAndVoidEvidence() {
        PaymentFixture fixture = createPaymentFixture("admin-ars-historical", Currency.USD);
        List<Installment> installments = createThreeHundredPending(fixture);
        PaymentSubmission submission = originalSubmission(installments.getFirst(), Currency.USD,
                "200.00", "200.00", null);
        LocalDate date = LocalDate.of(2026, 9, 3);
        submission.setReportedPaymentDate(date);
        paymentSubmissionRepository.saveAndFlush(submission);
        String original = immutableSubmission(submission.getId());
        given(exchangeRateService.getOfficialQuoteForDate(date)).willAnswer(invocation -> {
            assertTrue(!TransactionSynchronizationManager.isActualTransactionActive());
            // Actual PostgreSQL NOWAIT locks prove quote resolution holds no financial locks.
            transactionTemplate.executeWithoutResult(status -> {
                jdbc.queryForObject("SELECT id FROM payment_submissions WHERE id = ? FOR UPDATE NOWAIT", Long.class, submission.getId());
                jdbc.queryForObject("SELECT id FROM installments WHERE id = ? FOR UPDATE NOWAIT", Long.class, installments.getFirst().getId());
            });
            return new ExchangeRateQuote(new BigDecimal("1530.00000000"), date, date.minusDays(1),
                    "historical-source", "historical-provider", "2026-09-02T12:00:00Z");
        });
        paymentService.reviewPayment(submission.getId(),
                new ReviewPaymentDTO(new BigDecimal("153000.00"), Currency.ARS, null), REVIEWER);
        PaymentSubmission reviewed = paymentSubmissionRepository.findByIdWithContext(submission.getId()).orElseThrow();
        PaymentOutcome approved = singleOutcome(reviewed, PaymentOutcomeStatus.APPROVED);
        PaymentOutcome rejected = singleOutcome(reviewed, PaymentOutcomeStatus.REJECTED);
        assertEquals(Currency.ARS, approved.getCurrency());
        assertEquals(new BigDecimal("100.00"), approved.getAmountInTripCurrency());
        assertEquals(new BigDecimal("1530.00000000"), approved.getExchangeRate());
        assertEquals(8, approved.getExchangeRateScale());
        assertEquals(date, approved.getExchangeRateRequestedDate());
        assertEquals(date.minusDays(1), approved.getExchangeRateEffectiveDate());
        assertEquals("historical-provider", approved.getExchangeRateProvider());
        assertEquals("historical-source", approved.getExchangeRateSource());
        assertEquals("2026-09-02T12:00:00Z", approved.getExchangeRateProviderTimestamp());
        assertEquals("2", approved.getCalculationVersion());
        assertEquals(Currency.USD, rejected.getCurrency());
        assertEquals(new BigDecimal("100.00"), rejected.getReportedAmount());
        assertEquals(new BigDecimal("200.00"), approved.getAmountInTripCurrency().add(rejected.getAmountInTripCurrency()));
        assertEquals(original, immutableSubmission(submission.getId()));
        org.mockito.Mockito.clearInvocations(exchangeRateService);
        PaymentOutcomeSnapshot approvedSnapshot = PaymentOutcomeSnapshot.fromOutcome(approved);
        paymentService.voidPayment(submission.getId(), REVIEWER);
        PaymentSubmission voided = paymentSubmissionRepository.findByIdWithContext(submission.getId()).orElseThrow();
        assertEquals(approvedSnapshot, PaymentOutcomeSnapshot.fromOutcome(singleOutcome(voided, PaymentOutcomeStatus.VOIDED)));
        assertEquals(0, totalPaid(installments).signum());
        assertEquals(original, immutableSubmission(submission.getId()));
        org.mockito.Mockito.verifyNoInteractions(exchangeRateService);
    }

    @Test
    void originalArsUsdTripApprovedInUsdUsesIdentityAndOriginalFxForRejectedRemainder() {
        PaymentFixture fixture = createPaymentFixture("admin-usd-identity", Currency.USD);
        List<Installment> installments = createThreeHundredPending(fixture);
        PaymentSubmission submission = originalSubmission(installments.getFirst(), Currency.ARS,
                "306000.00", "200.00", "1530.00000000");
        String original = immutableSubmission(submission.getId());
        paymentService.reviewPayment(submission.getId(),
                new ReviewPaymentDTO(new BigDecimal("150.00"), Currency.USD, null), REVIEWER);
        PaymentSubmission reviewed = paymentSubmissionRepository.findByIdWithContext(submission.getId()).orElseThrow();
        PaymentOutcome approved = singleOutcome(reviewed, PaymentOutcomeStatus.APPROVED);
        PaymentOutcome rejected = singleOutcome(reviewed, PaymentOutcomeStatus.REJECTED);
        assertEquals(Currency.USD, approved.getCurrency());
        assertEquals(null, approved.getExchangeRate());
        assertEquals(new BigDecimal("150.00"), allocationTripTotal(approved));
        assertEquals(new BigDecimal("76500.00"), rejected.getReportedAmount());
        assertEquals(new BigDecimal("50.00"), rejected.getAmountInTripCurrency());
        assertEquals(PaymentOutcomeSnapshot.fromSubmission(submission), PaymentOutcomeSnapshot.fromOutcome(rejected));
        assertEquals(new BigDecimal("100.00"), installmentRepository.findById(installments.get(0).getId()).orElseThrow().getPaidAmount());
        assertEquals(new BigDecimal("50.00"), installmentRepository.findById(installments.get(1).getId()).orElseThrow().getPaidAmount());
        assertEquals(original, immutableSubmission(submission.getId()));
        org.mockito.Mockito.verifyNoInteractions(exchangeRateService);
    }

    @Test
    void originalArsTripArsApprovedInUsdUsesNewQuoteAndUpwardEconomicCreditHasNoRejection() {
        PaymentFixture fixture = createPaymentFixture("admin-usd-new-quote", Currency.ARS);
        List<Installment> installments = createThreeHundredPending(fixture);
        PaymentSubmission submission = originalSubmission(installments.getFirst(), Currency.ARS, "100.00", "100.00", null);
        given(exchangeRateService.getOfficialQuoteForDate(BUSINESS_TODAY)).willReturn(new ExchangeRateQuote(
                new BigDecimal("100"), BUSINESS_TODAY, BUSINESS_TODAY, "test-quote", "test-provider", "test-time"));
        paymentService.reviewPayment(submission.getId(),
                new ReviewPaymentDTO(new BigDecimal("1.50"), Currency.USD, "Credit confirmed in USD"), REVIEWER);
        PaymentSubmission reviewed = paymentSubmissionRepository.findByIdWithContext(submission.getId()).orElseThrow();
        assertEquals(1, reviewed.getOutcomes().size());
        PaymentOutcome approved = singleOutcome(reviewed, PaymentOutcomeStatus.APPROVED);
        assertEquals(Currency.USD, approved.getCurrency());
        assertEquals(new BigDecimal("150.00"), approved.getAmountInTripCurrency());
        assertEquals(new BigDecimal("1.50"), approved.getAllocations().stream().map(PaymentAllocation::getReportedAmount).reduce(BigDecimal.ZERO, BigDecimal::add));
        verify(exchangeRateService).getOfficialQuoteForDate(BUSINESS_TODAY);
    }

    @ParameterizedTest
    @EnumSource(Currency.class)
    void equalNumericAmountWithDifferentCurrencyAcceptsNoObservation(Currency originalCurrency) {
        PaymentFixture fixture = createPaymentFixture("admin-currency-no-observation", originalCurrency);
        List<Installment> installments = createThreeHundredPending(fixture);
        PaymentSubmission submission = originalSubmission(installments.getFirst(), originalCurrency, "100.00", "100.00", null);
        String original = immutableSubmission(submission.getId());
        Currency administrative = originalCurrency == Currency.ARS ? Currency.USD : Currency.ARS;
        given(exchangeRateService.getOfficialQuoteForDate(BUSINESS_TODAY)).willReturn(new ExchangeRateQuote(
                new BigDecimal("2.00"), BUSINESS_TODAY, BUSINESS_TODAY, "test-quote", "test-provider", "test-time"));
        paymentService.reviewPayment(submission.getId(), new ReviewPaymentDTO(new BigDecimal("100.00"), administrative, null), REVIEWER);
        PaymentSubmission reviewed = paymentSubmissionRepository.findByIdWithContext(submission.getId()).orElseThrow();
        PaymentOutcome approved = singleOutcome(reviewed, PaymentOutcomeStatus.APPROVED);
        BigDecimal expectedTrip = new BigDecimal(originalCurrency == Currency.ARS ? "200.00" : "50.00");
        assertEquals(administrative, approved.getCurrency());
        assertEquals(new BigDecimal("100.00"), approved.getReportedAmount());
        assertEquals(expectedTrip, approved.getAmountInTripCurrency());
        assertEquals(expectedTrip, allocationTripTotal(approved));
        assertEquals(expectedTrip, totalPaid(installments));
        assertEquals(null, approved.getAdminObservation());
        if (originalCurrency == Currency.USD) {
            PaymentOutcome rejected = singleOutcome(reviewed, PaymentOutcomeStatus.REJECTED);
            assertEquals(originalCurrency, rejected.getCurrency());
            assertEquals(new BigDecimal("50.00"), rejected.getAmountInTripCurrency());
            assertEquals(new BigDecimal("100.00"), expectedTrip.add(rejected.getAmountInTripCurrency()));
        } else {
            assertEquals(1, reviewed.getOutcomes().size());
        }
        assertEquals(original, immutableSubmission(submission.getId()));
        verify(exchangeRateService).getOfficialQuoteForDate(BUSINESS_TODAY);
    }

    @Test
    void zeroApprovalInAlternateCurrencyNeedsNoQuoteAndPreservesExactOriginalTotals() {
        PaymentFixture fixture = createPaymentFixture("admin-zero-no-quote", Currency.USD);
        List<Installment> installments = createThreeHundredPending(fixture);
        PaymentSubmission submission = originalSubmission(installments.getFirst(), Currency.USD, "100.00", "100.00", null);
        paymentService.reviewPayment(submission.getId(), new ReviewPaymentDTO(BigDecimal.ZERO, Currency.ARS, null), REVIEWER);
        PaymentOutcome rejected = singleOutcome(paymentSubmissionRepository.findByIdWithContext(submission.getId()).orElseThrow(), PaymentOutcomeStatus.REJECTED);
        assertEquals(Currency.USD, rejected.getCurrency());
        assertEquals(new BigDecimal("100.00"), rejected.getReportedAmount());
        assertEquals(new BigDecimal("100.00"), rejected.getAmountInTripCurrency());
        assertEquals(0, totalPaid(installments).signum());
        org.mockito.Mockito.verifyNoInteractions(exchangeRateService);
    }

    @Test
    void freshBalanceAfterQuotePreparationRejectsConvertedOverpaymentWithoutReviewEffects() {
        PaymentFixture fixture = createPaymentFixture("admin-fresh-balance", Currency.USD);
        List<Installment> installments = createThreeHundredPending(fixture);
        PaymentSubmission submission = originalSubmission(installments.getFirst(), Currency.USD, "300.00", "300.00", null);
        given(exchangeRateService.getOfficialQuoteForDate(BUSINESS_TODAY)).willAnswer(invocation -> {
            transactionTemplate.executeWithoutResult(status -> {
                for (Installment installment : installments) {
                    jdbc.update("UPDATE installments SET paid_amount = 100 WHERE id = ?", installment.getId());
                }
            });
            return new ExchangeRateQuote(new BigDecimal("100"), BUSINESS_TODAY, BUSINESS_TODAY, "test-quote", "test-provider", null);
        });
        assertThrows(IllegalStateException.class, () -> paymentService.reviewPayment(submission.getId(),
                new ReviewPaymentDTO(new BigDecimal("10000.00"), Currency.ARS, "Confirmed credit"), REVIEWER));
        PaymentSubmission unchanged = paymentSubmissionRepository.findByIdWithContext(submission.getId()).orElseThrow();
        assertEquals(PaymentSubmissionStatus.PENDING, unchanged.getStatus());
        assertTrue(unchanged.getOutcomes().isEmpty());
        assertEquals(0, paymentAllocationRepository.count());
        assertEquals(0, totalPaid(installments).compareTo(new BigDecimal("300.00"))); // Only the competing committed payment remains.
    }

    @Test
    void unavailableHistoricalQuoteIsUserSafeAndLeavesNoReviewEffects() {
        PaymentFixture fixture = createPaymentFixture("admin-provider-unavailable", Currency.USD);
        List<Installment> installments = createThreeHundredPending(fixture);
        PaymentSubmission submission = originalSubmission(installments.getFirst(), Currency.USD, "100.00", "100.00", null);
        given(exchangeRateService.getOfficialQuoteForDate(BUSINESS_TODAY)).willThrow(new IllegalStateException("internal provider detail"));
        IllegalStateException error = assertThrows(IllegalStateException.class, () -> paymentService.reviewPayment(submission.getId(),
                new ReviewPaymentDTO(new BigDecimal("10000.00"), Currency.ARS, "Confirmed credit"), REVIEWER));
        assertEquals("No se pudo obtener la cotización histórica. Intente revisar el pago nuevamente.", error.getMessage());
        assertPendingWithoutOutcomes(submission.getId(), installments);
    }

    @Test
    void positiveTripRemainderMayRoundToZeroOriginalCentsWithoutFabrication() {
        PaymentFixture fixture = createPaymentFixture("admin-tiny-remainder", Currency.USD);
        List<Installment> installments = createThreeHundredPending(fixture);
        PaymentSubmission submission = originalSubmission(installments.getFirst(), Currency.ARS, "0.01", "1.00", "0.01");
        paymentService.reviewPayment(submission.getId(), new ReviewPaymentDTO(new BigDecimal("0.99"), Currency.USD, "Confirmed USD credit"), REVIEWER);
        PaymentSubmission reviewed = paymentSubmissionRepository.findByIdWithContext(submission.getId()).orElseThrow();
        PaymentOutcome rejected = singleOutcome(reviewed, PaymentOutcomeStatus.REJECTED);
        assertEquals(new BigDecimal("0.00"), rejected.getReportedAmount());
        assertEquals(new BigDecimal("0.01"), rejected.getAmountInTripCurrency());
        assertEquals(new BigDecimal("1.00"), rejected.getAmountInTripCurrency().add(singleOutcome(reviewed, PaymentOutcomeStatus.APPROVED).getAmountInTripCurrency()));
    }

    @Test
    void allocationDatabaseFailureRollsBackOutcomeAllocationsBalanceAndStatus() {
        PaymentFixture fixture = createPaymentFixture("admin-allocation-rollback", Currency.ARS);
        List<Installment> installments = createThreeHundredPending(fixture);
        PaymentSubmission submission = originalSubmission(installments.getFirst(), Currency.ARS, "200.00", "200.00", null);
        jdbc.execute("CREATE FUNCTION reject_review_allocation() RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN RAISE EXCEPTION 'test allocation failure'; END $$");
        jdbc.execute("CREATE TRIGGER reject_review_allocation BEFORE INSERT ON payment_allocations FOR EACH ROW EXECUTE FUNCTION reject_review_allocation()");
        try {
            assertThrows(RuntimeException.class, () -> paymentService.reviewPayment(submission.getId(),
                    new ReviewPaymentDTO(new BigDecimal("200.00"), Currency.ARS, null), REVIEWER));
            assertPendingWithoutOutcomes(submission.getId(), installments);
            assertEquals(0, paymentAllocationRepository.count());
        } finally {
            jdbc.execute("DROP TRIGGER reject_review_allocation ON payment_allocations");
            jdbc.execute("DROP FUNCTION reject_review_allocation()");
        }
    }

    private PaymentSubmission originalSubmission(Installment anchor, Currency currency,
            String amount, String tripAmount, String rate) {
        PaymentSubmission submission = buildLegacySubmission(anchor, createBankAccount(currency), amount, tripAmount);
        submission.setPaymentCurrency(currency);
        submission.setCalculationVersion("2");
        if (rate != null) {
            submission.setExchangeRate(new BigDecimal(rate));
            submission.setExchangeRateScale(new BigDecimal(rate).scale());
            submission.setExchangeRateRequestedDate(BUSINESS_TODAY);
            submission.setExchangeRateEffectiveDate(BUSINESS_TODAY);
            submission.setExchangeRateSource("original-source");
            submission.setExchangeRateProvider("original-provider");
            submission.setExchangeRateProviderTimestamp("original-time");
        }
        return paymentSubmissionRepository.saveAndFlush(submission);
    }

    private String immutableSubmission(Long id) {
        return jdbc.queryForObject("SELECT (to_jsonb(p) - 'status')::text FROM payment_submissions p WHERE id = ?", String.class, id);
    }

    @Test
    void legacyCurrencyRepresentationChangeRefusesPartialButAllowsExactPersistedTripTotal() {
        PaymentFixture fixture = createPaymentFixture("legacy-currency-representation", Currency.USD);
        List<Installment> installments = createThreeHundredPending(fixture);
        PaymentSubmission submission = originalSubmission(installments.getFirst(), Currency.ARS, "200000.00", "200.00", "1000.00");
        submission.setCalculationVersion("v1");
        paymentSubmissionRepository.saveAndFlush(submission);
        assertThrows(IllegalStateException.class, () -> paymentService.reviewPayment(submission.getId(),
                new ReviewPaymentDTO(new BigDecimal("100.00"), Currency.USD, "Partial legacy representation change"), REVIEWER));
        assertPendingWithoutOutcomes(submission.getId(), installments);
        paymentService.reviewPayment(submission.getId(),
                new ReviewPaymentDTO(new BigDecimal("200.00"), Currency.USD, "Full persisted trip total confirmed"), REVIEWER);
        assertEquals(new BigDecimal("200.00"), singleOutcome(paymentSubmissionRepository.findByIdWithContext(submission.getId()).orElseThrow(),
                PaymentOutcomeStatus.APPROVED).getAmountInTripCurrency());
        org.mockito.Mockito.verifyNoInteractions(exchangeRateService);
    }

    @Test
    void rejectedRoundingIsCappedAtOriginalReportedCentsAndRetainsTripDelta() {
        PaymentFixture fixture = createPaymentFixture("rejected-original-cap", Currency.USD);
        List<Installment> installments = createThreeHundredPending(fixture);
        // Persisted history is authoritative; the rejected amount cannot grow
        // past its original cents even if its frozen inverse yields more.
        PaymentSubmission submission = originalSubmission(installments.getFirst(), Currency.ARS, "0.01", "2.00", "0.01");
        paymentService.reviewPayment(submission.getId(), new ReviewPaymentDTO(new BigDecimal("0.01"), Currency.USD, "Confirmed USD credit"), REVIEWER);
        PaymentSubmission reviewed = paymentSubmissionRepository.findByIdWithContext(submission.getId()).orElseThrow();
        PaymentOutcome rejected = singleOutcome(reviewed, PaymentOutcomeStatus.REJECTED);
        assertEquals(new BigDecimal("0.01"), rejected.getReportedAmount());
        assertEquals(new BigDecimal("1.99"), rejected.getAmountInTripCurrency());
        assertEquals(new BigDecimal("2.00"), rejected.getAmountInTripCurrency().add(singleOutcome(reviewed, PaymentOutcomeStatus.APPROVED).getAmountInTripCurrency()));
        org.mockito.Mockito.verifyNoInteractions(exchangeRateService);
    }

    @Test
    void reviewPayment_equalAmount_approvesWithoutObservation() {
        PaymentFixture fixture = createPaymentFixture("correction-equal", Currency.ARS);
        List<Installment> installments = createThreeHundredPending(fixture);
        BankAccount bankAccount = createBankAccount(Currency.ARS);

        PaymentSubmissionDTO registered = registerArsPayment(
                installments.get(0), "300.00", bankAccount, fixture.user().getEmail());

        PaymentSubmissionDTO reviewed = paymentService.reviewPayment(
                registered.submissionId(), new ReviewPaymentDTO(new BigDecimal("300.00"), Currency.ARS, null), REVIEWER);

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
        assertEquals(Currency.ARS, approved.getCurrency());
        assertEquals(null, approved.getExchangeRate());
        org.mockito.Mockito.verifyNoInteractions(exchangeRateService);
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
                new ReviewPaymentDTO(new BigDecimal("240.00"), Currency.ARS, "El comprobante corresponde a $240.00 reales"),
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
                new ReviewPaymentDTO(new BigDecimal("300.00"), Currency.ARS, "  El depósito bancario acreditado fue de $300.00  "),
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

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = { " \t " })
    void reviewPayment_upwardCorrectionWithoutObservation_approves(String observation) {
        PaymentFixture fixture = createPaymentFixture("correction-up-noobs", Currency.ARS);
        List<Installment> installments = createThreeHundredPending(fixture);
        BankAccount bankAccount = createBankAccount(Currency.ARS);

        PaymentSubmissionDTO registered = registerArsPayment(
                installments.get(0), "240.00", bankAccount, fixture.user().getEmail());

        String original = immutableSubmission(registered.submissionId());
        PaymentSubmissionDTO reviewed = paymentService.reviewPayment(
                registered.submissionId(), new ReviewPaymentDTO(new BigDecimal("300.00"), Currency.ARS, observation), REVIEWER);
        assertEquals("APPROVED", reviewed.status().name());
        PaymentSubmission persisted = paymentSubmissionRepository.findByIdWithContext(registered.submissionId()).orElseThrow();
        PaymentOutcome approved = singleOutcome(persisted, PaymentOutcomeStatus.APPROVED);
        assertEquals(null, approved.getAdminObservation());
        assertEquals(new BigDecimal("300.00"), approved.getReportedAmount());
        assertEquals(new BigDecimal("300.00"), allocationTripTotal(approved));
        assertEquals(new BigDecimal("300.00"), totalPaid(installments));
        assertEquals(1, persisted.getOutcomes().size());
        assertEquals(original, immutableSubmission(registered.submissionId()));
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = { " \t " })
    void reviewPayment_downwardCorrectionWithoutObservation_partiallyApproves(String observation) {
        PaymentFixture fixture = createPaymentFixture("correction-down-noobs", Currency.ARS);
        List<Installment> installments = createThreeHundredPending(fixture);
        BankAccount bankAccount = createBankAccount(Currency.ARS);

        PaymentSubmissionDTO registered = registerArsPayment(
                installments.get(0), "300.00", bankAccount, fixture.user().getEmail());

        String original = immutableSubmission(registered.submissionId());
        PaymentSubmissionDTO reviewed = paymentService.reviewPayment(
                registered.submissionId(), new ReviewPaymentDTO(new BigDecimal("240.00"), Currency.ARS, observation), REVIEWER);
        assertEquals("PARTIALLY_APPROVED", reviewed.status().name());
        PaymentSubmission persisted = paymentSubmissionRepository.findByIdWithContext(registered.submissionId()).orElseThrow();
        PaymentOutcome approved = singleOutcome(persisted, PaymentOutcomeStatus.APPROVED);
        PaymentOutcome rejected = singleOutcome(persisted, PaymentOutcomeStatus.REJECTED);
        assertEquals(null, approved.getAdminObservation());
        assertEquals(null, rejected.getAdminObservation());
        assertEquals(new BigDecimal("240.00"), approved.getReportedAmount());
        assertEquals(new BigDecimal("60.00"), rejected.getReportedAmount());
        assertEquals(new BigDecimal("300.00"), approved.getAmountInTripCurrency().add(rejected.getAmountInTripCurrency()));
        assertEquals(new BigDecimal("240.00"), allocationTripTotal(approved));
        assertEquals(new BigDecimal("240.00"), totalPaid(installments));
        assertEquals(original, immutableSubmission(registered.submissionId()));
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
                new ReviewPaymentDTO(new BigDecimal("400.00"), Currency.ARS, "Corrección que excede el saldo"),
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
                new ReviewPaymentDTO(new BigDecimal("0.30"), Currency.USD, "El banco acreditó USD 0.30"),
                REVIEWER);

        assertEquals("APPROVED", reviewed.status().name());
        assertEquals(0, reviewed.reportedAmount().compareTo(new BigDecimal("0.24")));
        assertEquals(0, reviewed.approvedAmount().compareTo(new BigDecimal("0.30")));
        assertEquals(0, reviewed.rejectedAmount().compareTo(BigDecimal.ZERO));

        PaymentSubmission persisted = paymentSubmissionRepository
                .findByIdWithContext(registered.submissionId()).orElseThrow();
        PaymentOutcome approved = singleOutcome(persisted, PaymentOutcomeStatus.APPROVED);
        assertEquals(0, approved.getReportedAmount().compareTo(new BigDecimal("0.30")));
        assertEquals(PaymentOutcomeSnapshot.fromSubmission(persisted), PaymentOutcomeSnapshot.fromOutcome(approved));
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
                legacy.getId(), new ReviewPaymentDTO(new BigDecimal("300.00"), Currency.ARS, adminReason), REVIEWER);

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
                legacy.getId(), new ReviewPaymentDTO(new BigDecimal("240.00"), Currency.ARS, adminReason), REVIEWER);

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
                registered.submissionId(), new ReviewPaymentDTO(new BigDecimal("99999999.99"), Currency.ARS, null), REVIEWER);

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
                new ReviewPaymentDTO(new BigDecimal("100000000.00"), Currency.ARS, "Intento sobre el máximo persistible"),
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
                new ReviewPaymentDTO(new BigDecimal("240.00"), Currency.ARS, "x".repeat(500)),
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
                new ReviewPaymentDTO(new BigDecimal("240.00"), Currency.ARS, "x".repeat(501)),
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
                        .content("{\"approvedAmount\": 240.00, \"approvedCurrency\": \"ARS\", \"adminObservation\": \""
                                + "x".repeat(501) + "\"}"))
                .andExpect(status().isBadRequest());

        assertPendingWithoutOutcomes(registered.submissionId(), installments);
    }

    @Test
    void reviewPayment_legacyUpwardCorrectionWith500Chars_persistsReasonVerbatim() {
        PaymentFixture fixture = createPaymentFixture("correction-legacy-500-up", Currency.ARS);
        List<Installment> installments = createThreeHundredPending(fixture);
        BankAccount bankAccount = createBankAccount(Currency.ARS);
        PaymentSubmission legacy = buildLegacySubmission(
                installments.get(0), bankAccount, "240.00", "240.00");
        String reason = "m".repeat(500);

        PaymentSubmissionDTO reviewed = paymentService.reviewPayment(
                legacy.getId(), new ReviewPaymentDTO(new BigDecimal("300.00"), Currency.ARS, reason), REVIEWER);

        assertEquals("APPROVED", reviewed.status().name());
        assertEquals(reason, reviewed.adminObservation());
        PaymentSubmission persisted = paymentSubmissionRepository
                .findByIdWithContext(legacy.getId()).orElseThrow();
        PaymentOutcome approved = singleOutcome(persisted, PaymentOutcomeStatus.APPROVED);
        assertEquals(reason, approved.getAdminObservation());
        assertEquals(500, approved.getAdminObservation().length());
        assertEquals(0, totalPaid(installments).compareTo(new BigDecimal("300.00")));
    }

    @Test
    void reviewPayment_legacyDownwardCorrectionWith500Chars_showsReasonDeterministically() {
        PaymentFixture fixture = createPaymentFixture("correction-legacy-500-down", Currency.ARS);
        List<Installment> installments = createThreeHundredPending(fixture);
        BankAccount bankAccount = createBankAccount(Currency.ARS);
        PaymentSubmission legacy = buildLegacySubmission(
                installments.get(0), bankAccount, "300.00", "300.00");
        String reason = "m".repeat(500);

        PaymentSubmissionDTO reviewed = paymentService.reviewPayment(
                legacy.getId(), new ReviewPaymentDTO(new BigDecimal("240.00"), Currency.ARS, reason), REVIEWER);

        assertEquals("PARTIALLY_APPROVED", reviewed.status().name());
        assertEquals(reason, reviewed.adminObservation());
        PaymentSubmission persisted = paymentSubmissionRepository
                .findByIdWithContext(legacy.getId()).orElseThrow();
        assertEquals(reason, singleOutcome(persisted, PaymentOutcomeStatus.APPROVED).getAdminObservation());
        assertEquals(reason, singleOutcome(persisted, PaymentOutcomeStatus.REJECTED).getAdminObservation());
        assertEquals(0, totalPaid(installments).compareTo(new BigDecimal("240.00")));
    }

    @Test
    void reviewPayment_legacyWithoutCorrection_keepsTechnicalNote() {
        PaymentFixture fixture = createPaymentFixture("correction-legacy-nocorr", Currency.ARS);
        List<Installment> installments = createThreeHundredPending(fixture);
        BankAccount bankAccount = createBankAccount(Currency.ARS);
        PaymentSubmission legacy = buildLegacySubmission(
                installments.get(0), bankAccount, "300.00", "300.00");

        PaymentSubmissionDTO reviewed = paymentService.reviewPayment(
                legacy.getId(), new ReviewPaymentDTO(new BigDecimal("300.00"), Currency.ARS, null), REVIEWER);

        assertEquals("APPROVED", reviewed.status().name());
        PaymentSubmission persisted = paymentSubmissionRepository
                .findByIdWithContext(legacy.getId()).orElseThrow();
        assertEquals("Aprobación conciliada con los valores históricos v1 persistidos",
                singleOutcome(persisted, PaymentOutcomeStatus.APPROVED).getAdminObservation());
        assertEquals(0, totalPaid(installments).compareTo(new BigDecimal("300.00")));
    }

    @Test
    void reviewPayment_convertedOverflow_rejectsWithoutSideEffects() {
        PaymentFixture fixture = createPaymentFixture("correction-converted-overflow", Currency.ARS);
        List<Installment> installments = createTwoLargePending(fixture);
        BankAccount bankAccount = createBankAccount(Currency.USD);
        PaymentSubmission submission = buildCrossCurrencySubmission(
                installments.get(0), bankAccount, "100.00", "1200", "120000.00");

        // 100,001.00 USD is persistible as source, but converts to 120,001,200.00 ARS.
        assertThrows(IllegalStateException.class, () -> paymentService.reviewPayment(
                submission.getId(),
                new ReviewPaymentDTO(
                        new BigDecimal("100001.00"), Currency.USD, "Diferencia de cambio a favor del cliente"),
                REVIEWER));

        assertPendingWithoutOutcomes(submission.getId(), installments);
    }

    @Test
    void registerPayment_sourceAboveMaxMoney_rejectsBeforePersisting() {
        PaymentFixture fixture = createPaymentFixture("correction-register-over-max", Currency.ARS);
        List<Installment> installments = createThreeHundredPending(fixture);
        BankAccount bankAccount = createBankAccount(Currency.ARS);

        IllegalArgumentException error = assertThrows(IllegalArgumentException.class, () ->
                paymentService.registerPayment(
                        installments.get(0).getId(),
                        new BigDecimal("100000000.00"),
                        BUSINESS_TODAY,
                        Currency.ARS,
                        PaymentMethod.BANK_TRANSFER,
                        bankAccount.getId(),
                        null,
                        fixture.user().getEmail()));

        assertTrue(error.getMessage().contains("must not exceed"));
        assertEquals(0, paymentSubmissionRepository.count());
        assertEquals(0, totalPaid(installments).compareTo(BigDecimal.ZERO));
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
                new ReviewPaymentDTO(new BigDecimal("300.00"), Currency.ARS, "El depósito bancario acreditado fue de $300.00"),
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

    private List<Installment> createTwoLargePending(PaymentFixture fixture) {
        Installment first = createInstallment(
                fixture.trip(), fixture.user(), fixture.student(), 1, "75000000.00", InstallmentStatus.YELLOW);
        Installment second = createInstallment(
                fixture.trip(), fixture.user(), fixture.student(), 2, "75000000.00", InstallmentStatus.YELLOW);
        return List.of(first, second);
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

    private PaymentSubmission buildCrossCurrencySubmission(
            Installment anchor,
            BankAccount bankAccount,
            String reportedAmount,
            String exchangeRate,
            String amountInTripCurrency) {
        PaymentSubmission submission = new PaymentSubmission();
        submission.setTrip(anchor.getTrip());
        submission.setUser(anchor.getUser());
        submission.setStudent(anchor.getStudent());
        submission.setAnchorInstallment(anchor);
        submission.setBankAccount(bankAccount);
        submission.setReportedAmount(new BigDecimal(reportedAmount));
        submission.setPaymentCurrency(Currency.USD);
        submission.setExchangeRate(new BigDecimal(exchangeRate));
        submission.setExchangeRateScale(new BigDecimal(exchangeRate).scale());
        submission.setAmountInTripCurrency(new BigDecimal(amountInTripCurrency));
        submission.setReportedPaymentDate(BUSINESS_TODAY);
        submission.setPaymentMethod(PaymentMethod.BANK_TRANSFER);
        submission.setStatus(PaymentSubmissionStatus.PENDING);
        submission.setFileKey("cross-currency-test-receipt");
        submission.setCalculationVersion("2");
        submission.setExchangeRateRequestedDate(BUSINESS_TODAY);
        submission.setExchangeRateEffectiveDate(BUSINESS_TODAY);
        submission.setExchangeRateSource("test-frozen-source");
        submission.setExchangeRateProvider("test-frozen-provider");
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
