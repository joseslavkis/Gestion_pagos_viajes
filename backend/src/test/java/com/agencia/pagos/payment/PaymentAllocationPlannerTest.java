package com.agencia.pagos.payment;

import com.agencia.pagos.shared.money.Currency;
import com.agencia.pagos.trip.Installment;
import com.agencia.pagos.trip.InstallmentStatus;
import com.agencia.pagos.trip.Trip;
import com.agencia.pagos.payment.PaymentAllocationPlanner;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertIterableEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PaymentAllocationPlannerTest {

    private final PaymentAllocationPlanner planner = new PaymentAllocationPlanner();

    @Test
    void remainingBalanceRejectsSubcentPersistedAmountsInsteadOfRoundingThem() {
        Installment installment = buildInstallment(1, "100.00", "0.001");

        assertThrows(IllegalArgumentException.class, () -> planner.getRemainingAmount(installment));
    }

    @Test
    void plan_reparteMontoLibreSecuencialmenteConUltimaCuotaParcial() {
        List<Installment> installments = List.of(
                buildInstallment(1, "100.00", "0.00"),
                buildInstallment(2, "100.00", "0.00"),
                buildInstallment(3, "100.00", "0.00")
        );

        PaymentAllocationPlanner.PlanResult result = planner.plan(
                installments,
                new BigDecimal("250.00"),
                Currency.ARS,
                null
        );

        assertEquals(new BigDecimal("300.00"), result.maxAllowedAmount());
        assertEquals(new BigDecimal("300.00"), result.totalPendingAmountInTripCurrency());
        assertEquals(new BigDecimal("250.00"), result.amountInTripCurrency());
        assertEquals(3, result.allocations().size());
        assertEquals(new BigDecimal("100.00"), result.allocations().get(0).amountInTripCurrency());
        assertEquals(new BigDecimal("100.00"), result.allocations().get(1).amountInTripCurrency());
        assertEquals(new BigDecimal("50.00"), result.allocations().get(2).amountInTripCurrency());
    }

    @Test
    void plan_respetaSaldoYaPagadoAntesDeImputar() {
        List<Installment> installments = List.of(
                buildInstallment(1, "100.00", "40.00"),
                buildInstallment(2, "100.00", "0.00"),
                buildInstallment(3, "100.00", "0.00")
        );

        PaymentAllocationPlanner.PlanResult result = planner.plan(
                installments,
                new BigDecimal("160.00"),
                Currency.ARS,
                null
        );

        assertEquals(2, result.allocations().size());
        assertEquals(new BigDecimal("60.00"), result.allocations().get(0).amountInTripCurrency());
        assertEquals(new BigDecimal("100.00"), result.allocations().get(1).amountInTripCurrency());
    }

    @Test
    void plan_fallaSiElMontoInformadoSuperaElSaldoTotal() {
        List<Installment> installments = List.of(
                buildInstallment(1, "100.00", "0.00"),
                buildInstallment(2, "100.00", "0.00")
        );

        PaymentBalanceExceededException error = assertThrows(
                PaymentBalanceExceededException.class,
                () -> planner.plan(installments, new BigDecimal("250.01"), Currency.ARS, null)
        );

        assertEquals(new BigDecimal("200.00"), error.maxAllowedAmount());
        assertEquals(new BigDecimal("50.01"), error.residualInTripCurrency());
    }

    @Test
    void remainingIntentLimitCapsAtTheAnchorWhileManualIntentUsesTheEnrollmentTotal() {
        List<Installment> installments = List.of(
                buildInstallment(1, "240.00", "0.00"),
                buildInstallment(2, "240.00", "0.00"),
                buildInstallment(3, "240.00", "0.00")
        );

        PaymentAllocationPlanner.PlanResult manualPlan = planner.plan(
                installments, new BigDecimal("500.00"), Currency.ARS, null);

        assertEquals(new BigDecimal("720.00"), manualPlan.totalPendingAmountInTripCurrency());
        assertIterableEquals(
                List.of(new BigDecimal("240.00"), new BigDecimal("240.00"), new BigDecimal("20.00")),
                manualPlan.allocations().stream()
                        .map(PaymentAllocationPlanner.PlannedAllocation::amountInTripCurrency)
                        .toList()
        );

        List<Installment> selectedScope = List.of(
                buildInstallment(1, "200.00", "0.00"),
                buildInstallment(2, "100.00", "0.00")
        );
        PaymentBalanceExceededException exceeded = assertThrows(
                PaymentBalanceExceededException.class,
                () -> planner.plan(
                        selectedScope,
                        new BigDecimal("66.67"),
                        Currency.USD,
                        new BigDecimal("3"),
                        new PaymentAllocationPlanner.PaymentLimit(
                                new BigDecimal("200.00"), new BigDecimal("66.66")))
        );

        assertEquals(new BigDecimal("66.66"), exceeded.maxAllowedAmount());
        assertEquals(new BigDecimal("0.01"), exceeded.residualInTripCurrency());
    }

    @Test
    void caseA_rejectsWhenRoundedTripAmountExceedsImputableBalance() {
        List<Installment> installments = List.of(buildInstallment(1, "100.00", "0.00"));

        PaymentBalanceExceededException error = assertThrows(
                PaymentBalanceExceededException.class,
                () -> planner.plan(installments, new BigDecimal("0.99"), Currency.USD, new BigDecimal("101.30"))
        );

        org.junit.jupiter.api.Assertions.assertAll(
                () -> assertEquals(new BigDecimal("0.98"), error.maxAllowedAmount()),
                () -> assertEquals(new BigDecimal("0.29"), error.residualInTripCurrency()),
                () -> org.junit.jupiter.api.Assertions.assertTrue(error.getMessage().contains("0.98")),
                () -> org.junit.jupiter.api.Assertions.assertTrue(error.getMessage().contains("0.29"))
        );
    }

    @Test
    void caseB_rejectsOneCentOverBalanceWithoutAllocations() {
        List<Installment> installments = List.of(
                buildInstallment(1, "100.00", "0.00"),
                buildInstallment(2, "100.00", "0.00")
        );

        PaymentBalanceExceededException error = assertThrows(
                PaymentBalanceExceededException.class,
                () -> planner.plan(installments, new BigDecimal("66.67"), Currency.USD, new BigDecimal("3"))
        );

        org.junit.jupiter.api.Assertions.assertAll(
                () -> assertEquals(new BigDecimal("66.66"), error.maxAllowedAmount()),
                () -> assertEquals(new BigDecimal("0.01"), error.residualInTripCurrency()),
                () -> org.junit.jupiter.api.Assertions.assertTrue(error.getMessage().contains("66.66")),
                () -> org.junit.jupiter.api.Assertions.assertTrue(error.getMessage().contains("0.01"))
        );
    }

    @Test
    void validResidualVectorConservesBothCurrenciesDeterministically() {
        List<Installment> installments = List.of(
                buildInstallment(1, "100.01", "0.00"),
                buildInstallment(2, "100.00", "0.00")
        );

        PaymentAllocationPlanner.PlanResult first = planner.plan(
                installments,
                new BigDecimal("66.67"),
                Currency.USD,
                new BigDecimal("3")
        );
        PaymentAllocationPlanner.PlanResult second = planner.plan(
                installments,
                new BigDecimal("66.67"),
                Currency.USD,
                new BigDecimal("3")
        );

        assertEquals(new BigDecimal("200.01"), sumTrip(first));
        assertEquals(new BigDecimal("66.67"), sumReported(first));
        assertIterableEquals(
                List.of(new BigDecimal("100.01"), new BigDecimal("100.00")),
                first.allocations().stream()
                        .map(PaymentAllocationPlanner.PlannedAllocation::amountInTripCurrency)
                        .toList()
        );
        assertIterableEquals(
                List.of(new BigDecimal("33.34"), new BigDecimal("33.33")),
                first.allocations().stream()
                        .map(PaymentAllocationPlanner.PlannedAllocation::reportedAmount)
                        .toList()
        );
        assertIterableEquals(
                first.allocations(),
                second.allocations()
        );
        org.junit.jupiter.api.Assertions.assertTrue(first.allocations().stream().allMatch(allocation ->
                allocation.amountInTripCurrency().signum() >= 0
                        && allocation.amountInTripCurrency().compareTo(allocation.remainingAmount()) <= 0));
    }

    @Test
    void caseCAndD_preserveThreeDecimalRateDuringConversion() {
        assertEquals(
                new BigDecimal("1234567.00"),
                planner.convertPaymentToTripCurrency(
                        new BigDecimal("1000.00"), Currency.ARS, Currency.USD, new BigDecimal("1234.567"))
        );
        assertEquals(
                new BigDecimal("1234564.00"),
                planner.convertPaymentToTripCurrency(
                        new BigDecimal("1000.00"), Currency.ARS, Currency.USD, new BigDecimal("1234.564"))
        );
    }

    @Test
    void defensiveConservationAssertionRejectsAnInvalidPlanBeforePersistence() {
        Installment installment = buildInstallment(1, "100.00", "0.00");
        PaymentAllocationPlanner.PlanResult invalid = new PaymentAllocationPlanner.PlanResult(
                Currency.ARS,
                Currency.USD,
                new BigDecimal("1.00"),
                new BigDecimal("1.00"),
                new BigDecimal("100.00"),
                new BigDecimal("100.00"),
                new BigDecimal("100.00"),
                List.of(new PaymentAllocationPlanner.PlannedAllocation(
                        installment,
                        1,
                        new BigDecimal("100.00"),
                        new BigDecimal("0.99"),
                        new BigDecimal("100.00")))
        );

        assertThrows(IllegalStateException.class, () -> planner.assertConservation(invalid));
    }

    @Test
    void seededMatrixConservesBothCurrenciesAcrossRatesPartialsLimitsAndInstallmentCounts() {
        Random random = new Random(20260919L);
        List<BigDecimal> rates = List.of(
                new BigDecimal("1015.50"),
                new BigDecimal("1234.567"),
                new BigDecimal("987.65432109")
        );

        for (int installmentCount = 1; installmentCount <= 60; installmentCount++) {
            Currency tripCurrency = installmentCount % 2 == 0 ? Currency.ARS : Currency.USD;
            Currency paymentCurrency = switch (installmentCount % 4) {
                case 0, 1 -> tripCurrency;
                default -> tripCurrency == Currency.ARS ? Currency.USD : Currency.ARS;
            };
            BigDecimal rate = tripCurrency == paymentCurrency
                    ? null
                    : rates.get(installmentCount % rates.size());
            List<Installment> installments = randomInstallments(
                    random, installmentCount, tripCurrency);
            BigDecimal totalBalance = installments.stream()
                    .map(planner::getRemainingAmount)
                    .reduce(BigDecimal.ZERO, BigDecimal::add)
                    .setScale(2);
            PaymentMoneyPolicy policy = new PaymentMoneyPolicy();
            BigDecimal limit = policy.maxAllowedPaymentAmount(
                    totalBalance, tripCurrency, paymentCurrency, rate);
            java.math.BigInteger limitCents = limit.movePointRight(2).toBigIntegerExact();
            java.math.BigInteger reportedCents = installmentCount % 3 == 0
                    ? limitCents
                    : limitCents.divide(java.math.BigInteger.TWO)
                            .max(java.math.BigInteger.ONE)
                            .min(limitCents);
            BigDecimal reportedAmount = new BigDecimal(reportedCents, 2);

            PaymentAllocationPlanner.PlanResult first = planner.plan(
                    installments, reportedAmount, paymentCurrency, rate);
            PaymentAllocationPlanner.PlanResult second = planner.plan(
                    installments, reportedAmount, paymentCurrency, rate);

            assertEquals(first.reportedAmount(), sumReported(first));
            assertEquals(first.amountInTripCurrency(), sumTrip(first));
            assertIterableEquals(first.allocations(), second.allocations());
            org.junit.jupiter.api.Assertions.assertTrue(first.allocations().stream().allMatch(allocation ->
                    allocation.reportedAmount().signum() >= 0
                            && allocation.amountInTripCurrency().signum() >= 0
                            && allocation.amountInTripCurrency().compareTo(allocation.remainingAmount()) <= 0));
            assertTrue(policy.convertPaymentToTripCurrency(
                            first.maxAllowedAmount(), tripCurrency, paymentCurrency, rate)
                    .compareTo(totalBalance) <= 0);
            assertTrue(policy.convertPaymentToTripCurrency(
                            first.maxAllowedAmount().add(new BigDecimal("0.01")),
                            tripCurrency,
                            paymentCurrency,
                            rate)
                    .compareTo(totalBalance) > 0);
        }
    }

    @Test
    void plan_acceptsMaxMoneySameCurrencyAndRejectsOneCentMoreAsBalanceExceeded() {
        List<Installment> installments = List.of(buildInstallment(1, "99999999.99", "0.00"));

        PaymentAllocationPlanner.PlanResult result = planner.plan(
                installments, new BigDecimal("99999999.99"), Currency.ARS, null);

        assertEquals(new BigDecimal("99999999.99"), result.reportedAmount());
        assertEquals(new BigDecimal("99999999.99"), result.amountInTripCurrency());

        PaymentBalanceExceededException exceeded = assertThrows(
                PaymentBalanceExceededException.class,
                () -> planner.plan(installments, new BigDecimal("100000000.00"), Currency.ARS, null));
        assertEquals(new BigDecimal("99999999.99"), exceeded.maxAllowedAmount());
    }

    @Test
    void plan_rejectsConvertedOverflowEvenWhenBalanceCoversIt_usdToArs() {
        List<Installment> installments = List.of(
                buildInstallment(1, "75000000.00", "0.00"),
                buildInstallment(2, "75000000.00", "0.00"));
        BigDecimal rate = new BigDecimal("1200");

        // 100,001.00 USD exceeds the derived persistible limit, so the planner
        // reports the capped maximum instead of an unpersistible plan.
        PaymentBalanceExceededException exceeded = assertThrows(
                PaymentBalanceExceededException.class,
                () -> planner.plan(installments, new BigDecimal("100001.00"), Currency.USD, rate));

        assertEquals(new BigDecimal("83333.33"), exceeded.maxAllowedAmount());
    }

    @Test
    void plan_rejectsUnpersistibleConvertedTotalAgainstGenerousCallerLimit_usdToArs() {
        List<Installment> installments = List.of(
                buildInstallment(1, "75000000.00", "0.00"),
                buildInstallment(2, "75000000.00", "0.00"));
        BigDecimal rate = new BigDecimal("1200");

        // 100,001.00 USD -> 120,001,200.00 ARS: fits the 150M balance but not
        // NUMERIC(10,2). With an over-generous caller limit the persistibility
        // invariant is the backstop that keeps the plan unpersistible-free.
        IllegalArgumentException error = assertThrows(
                IllegalArgumentException.class,
                () -> planner.plan(
                        installments,
                        new BigDecimal("100001.00"),
                        Currency.USD,
                        rate,
                        new PaymentAllocationPlanner.PaymentLimit(
                                new BigDecimal("150000000.00"), new BigDecimal("100001.00"))));

        assertEquals(IllegalArgumentException.class, error.getClass());
        assertTrue(error.getMessage().contains("amountInTripCurrency"));
    }

    @Test
    void plan_enforcesDerivedExactLimit_usdToArs() {
        List<Installment> installments = List.of(
                buildInstallment(1, "75000000.00", "0.00"),
                buildInstallment(2, "75000000.00", "0.00"));
        BigDecimal rate = new BigDecimal("1200");
        BigDecimal limit = new PaymentMoneyPolicy().maxAllowedPaymentAmount(
                new BigDecimal("150000000.00"), Currency.ARS, Currency.USD, rate);

        assertEquals(new BigDecimal("83333.33"), limit);
        PaymentAllocationPlanner.PlanResult result = planner.plan(
                installments, limit, Currency.USD, rate);
        assertEquals(limit, result.reportedAmount());
        assertTrue(result.amountInTripCurrency().compareTo(PaymentMoneyPolicy.MAX_MONEY) <= 0);

        // 83,333.34 USD -> 100,000,008.00 ARS: within the 150M balance, beyond NUMERIC(10,2).
        PaymentBalanceExceededException overLimit = assertThrows(
                PaymentBalanceExceededException.class,
                () -> planner.plan(
                        installments, limit.add(new BigDecimal("0.01")), Currency.USD, rate));
        assertEquals(limit, overLimit.maxAllowedAmount());
    }

    @Test
    void plan_rejectsConvertedOverflowWhenDivisionMultiplies_arsToUsd() {
        List<Installment> installments = List.of(
                buildInstallment(1, "75000000.00", "0.00", Currency.USD),
                buildInstallment(2, "75000000.00", "0.00", Currency.USD));
        BigDecimal rate = new BigDecimal("0.5");

        // 60,000,000.00 ARS / 0.5 -> 120,000,000.00 USD: fits the 150M balance
        // but not NUMERIC(10,2). The derived limit (49,999,999.99) rejects it first.
        PaymentBalanceExceededException exceeded = assertThrows(
                PaymentBalanceExceededException.class,
                () -> planner.plan(
                        installments, new BigDecimal("60000000.00"), Currency.ARS, rate));

        assertEquals(new BigDecimal("49999999.99"), exceeded.maxAllowedAmount());
    }

    @Test
    void plan_rejectsUnpersistibleConvertedTotalAgainstGenerousCallerLimit_arsToUsd() {
        List<Installment> installments = List.of(
                buildInstallment(1, "75000000.00", "0.00", Currency.USD),
                buildInstallment(2, "75000000.00", "0.00", Currency.USD));
        BigDecimal rate = new BigDecimal("0.5");

        // 60,000,000.00 ARS / 0.5 -> 120,000,000.00 USD: source fits, converted
        // does not. Same backstop as the USD->ARS direction.
        IllegalArgumentException error = assertThrows(
                IllegalArgumentException.class,
                () -> planner.plan(
                        installments,
                        new BigDecimal("60000000.00"),
                        Currency.ARS,
                        rate,
                        new PaymentAllocationPlanner.PaymentLimit(
                                new BigDecimal("150000000.00"), new BigDecimal("60000000.00"))));

        assertEquals(IllegalArgumentException.class, error.getClass());
        assertTrue(error.getMessage().contains("amountInTripCurrency"));
    }

    @Test
    void plan_enforcesDerivedExactLimit_arsToUsd() {
        List<Installment> installments = List.of(
                buildInstallment(1, "75000000.00", "0.00", Currency.USD),
                buildInstallment(2, "75000000.00", "0.00", Currency.USD));
        BigDecimal rate = new BigDecimal("0.5");
        BigDecimal limit = new PaymentMoneyPolicy().maxAllowedPaymentAmount(
                new BigDecimal("150000000.00"), Currency.USD, Currency.ARS, rate);

        assertEquals(new BigDecimal("49999999.99"), limit);
        PaymentAllocationPlanner.PlanResult result = planner.plan(
                installments, limit, Currency.ARS, rate);
        assertEquals(limit, result.reportedAmount());
        assertTrue(result.amountInTripCurrency().compareTo(PaymentMoneyPolicy.MAX_MONEY) <= 0);

        // 50,000,000.00 ARS / 0.5 -> 100,000,000.00 USD: beyond the derived limit.
        PaymentBalanceExceededException overLimit = assertThrows(
                PaymentBalanceExceededException.class,
                () -> planner.plan(
                        installments, limit.add(new BigDecimal("0.01")), Currency.ARS, rate));
        assertEquals(limit, overLimit.maxAllowedAmount());
    }

    @Test
    void plan_rejectsUnpersistibleSourceEvenWhenConvertedFits_arsToUsd() {
        List<Installment> installments = List.of(
                buildInstallment(1, "75000000.00", "0.00", Currency.USD),
                buildInstallment(2, "75000000.00", "0.00", Currency.USD));
        BigDecimal rate = new BigDecimal("1200");

        // 100,000,000.00 ARS overflows the source aggregate; converted (~83,333.33 USD) would fit.
        PaymentBalanceExceededException exceeded = assertThrows(
                PaymentBalanceExceededException.class,
                () -> planner.plan(
                        installments, new BigDecimal("100000000.00"), Currency.ARS, rate));

        assertEquals(new BigDecimal("99999999.99"), exceeded.maxAllowedAmount());
    }

    private BigDecimal sumTrip(PaymentAllocationPlanner.PlanResult result) {
        return result.allocations().stream()
                .map(PaymentAllocationPlanner.PlannedAllocation::amountInTripCurrency)
                .reduce(BigDecimal.ZERO, BigDecimal::add)
                .setScale(2);
    }

    private BigDecimal sumReported(PaymentAllocationPlanner.PlanResult result) {
        return result.allocations().stream()
                .map(PaymentAllocationPlanner.PlannedAllocation::reportedAmount)
                .reduce(BigDecimal.ZERO, BigDecimal::add)
                .setScale(2);
    }

    private Installment buildInstallment(int installmentNumber, String totalDue, String paidAmount) {
        return buildInstallment(installmentNumber, totalDue, paidAmount, Currency.ARS);
    }

    @Test
    void planFingerprint_bindsInstallmentIdsAndTripAmountsInAllocationOrder() {
        List<Installment> installments = List.of(
                buildInstallment(1, "100.00", "0.00"),
                buildInstallment(2, "100.00", "0.00"),
                buildInstallment(3, "100.00", "0.00")
        );
        installments.get(0).setId(11L);
        installments.get(1).setId(22L);
        installments.get(2).setId(33L);

        PaymentAllocationPlanner.PlanResult plan = planner.plan(
                installments, new BigDecimal("150.00"), Currency.ARS, null);

        assertEquals("11:100.00;22:50.00",
                PaymentAllocationPlanner.planFingerprint(plan));
    }

    @Test
    void planFingerprint_differsWhenAnyAllocationChanges() {
        List<Installment> installments = List.of(
                buildInstallment(1, "100.00", "0.00"),
                buildInstallment(2, "100.00", "0.00")
        );
        installments.get(0).setId(11L);
        installments.get(1).setId(22L);

        PaymentAllocationPlanner.PlanResult before = planner.plan(
                installments, new BigDecimal("150.00"), Currency.ARS, null);
        installments.get(0).setPaidAmount(new BigDecimal("50.00"));
        PaymentAllocationPlanner.PlanResult after = planner.plan(
                installments, new BigDecimal("150.00"), Currency.ARS, null);

        String beforeFingerprint = PaymentAllocationPlanner.planFingerprint(before);
        String afterFingerprint = PaymentAllocationPlanner.planFingerprint(after);
        assertEquals("11:100.00;22:50.00", beforeFingerprint);
        assertEquals("11:50.00;22:100.00", afterFingerprint);
        assertTrue(!beforeFingerprint.equals(afterFingerprint));
    }

    private List<Installment> randomInstallments(
            Random random,
            int installmentCount,
            Currency tripCurrency
    ) {
        List<Installment> installments = new ArrayList<>();
        for (int installmentNumber = 1; installmentNumber <= installmentCount; installmentNumber++) {
            int whole = tripCurrency == Currency.ARS
                    ? 1000 + random.nextInt(4001)
                    : 10 + random.nextInt(41);
            int cents = random.nextInt(100);
            BigDecimal totalDue = new BigDecimal(whole).add(BigDecimal.valueOf(cents, 2)).setScale(2);
            BigDecimal paidAmount = BigDecimal.valueOf(random.nextInt(whole * 25 + 1), 2).setScale(2);
            installments.add(buildInstallment(
                    installmentNumber,
                    totalDue.toPlainString(),
                    paidAmount.toPlainString(),
                    tripCurrency));
        }
        return installments;
    }

    private Installment buildInstallment(
            int installmentNumber,
            String totalDue,
            String paidAmount,
            Currency tripCurrency
    ) {
        Trip trip = new Trip();
        trip.setCurrency(tripCurrency);

        Installment installment = new Installment();
        installment.setTrip(trip);
        installment.setInstallmentNumber(installmentNumber);
        installment.setDueDate(LocalDate.now().plusDays(installmentNumber));
        installment.setCapitalAmount(new BigDecimal(totalDue));
        installment.setRetroactiveAmount(BigDecimal.ZERO);
        installment.setTotalDue(new BigDecimal(totalDue));
        installment.setPaidAmount(new BigDecimal(paidAmount));
        installment.setStatus(InstallmentStatus.YELLOW);
        return installment;
    }
}
