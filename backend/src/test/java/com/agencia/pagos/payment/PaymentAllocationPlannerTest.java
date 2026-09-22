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
