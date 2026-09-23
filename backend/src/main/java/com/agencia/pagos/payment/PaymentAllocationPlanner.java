package com.agencia.pagos.payment;

import com.agencia.pagos.shared.money.Currency;
import com.agencia.pagos.trip.Installment;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

@Component
public class PaymentAllocationPlanner {

    private record TripAllocation(
            Installment installment,
            int allocationOrder,
            BigDecimal remainingAmount,
            BigDecimal amountInTripCurrency
    ) {
    }

    private record ReportedShare(int index, BigInteger remainder) {
    }

    public record PlannedAllocation(
            Installment installment,
            int allocationOrder,
            BigDecimal remainingAmount,
            BigDecimal reportedAmount,
            BigDecimal amountInTripCurrency
    ) {
    }

    public record PlanResult(
            Currency tripCurrency,
            Currency paymentCurrency,
            BigDecimal reportedAmount,
            BigDecimal maxAllowedAmount,
            BigDecimal exchangeRate,
            BigDecimal totalPendingAmountInTripCurrency,
            BigDecimal amountInTripCurrency,
            List<PlannedAllocation> allocations
    ) {
    }

    public record PaymentLimit(BigDecimal balanceInTripCurrency, BigDecimal maxAllowedAmount) {
    }

    private final PaymentMoneyPolicy moneyPolicy;

    public PaymentAllocationPlanner() {
        this(new PaymentMoneyPolicy());
    }

    @Autowired
    public PaymentAllocationPlanner(PaymentMoneyPolicy moneyPolicy) {
        this.moneyPolicy = moneyPolicy;
    }

    public PlanResult plan(
            List<Installment> installments,
            BigDecimal reportedAmount,
            Currency paymentCurrency,
            BigDecimal exchangeRate
    ) {
        return plan(installments, reportedAmount, paymentCurrency, exchangeRate, null);
    }

    public PlanResult plan(
            List<Installment> installments,
            BigDecimal reportedAmount,
            Currency paymentCurrency,
            BigDecimal exchangeRate,
            PaymentLimit paymentLimit
    ) {
        if (installments == null || installments.isEmpty()) {
            throw new IllegalArgumentException("Debe haber al menos una cuota seleccionada");
        }
        BigDecimal normalizedReportedAmount = moneyPolicy.requirePositiveMoney(
                reportedAmount, "reportedAmount");

        Currency tripCurrency = installments.getFirst().getTrip().getCurrency();
        BigDecimal totalPendingAmountInTripCurrency = installments.stream()
                .map(this::getRemainingAmount)
                .reduce(BigDecimal.ZERO, BigDecimal::add)
                .setScale(PaymentMoneyPolicy.MONEY_SCALE, RoundingMode.UNNECESSARY);
        BigDecimal balanceLimit = paymentLimit == null
                ? totalPendingAmountInTripCurrency
                : moneyPolicy.requireMoney(paymentLimit.balanceInTripCurrency(), "balanceLimitInTripCurrency");
        if (balanceLimit.signum() < 0 || balanceLimit.compareTo(totalPendingAmountInTripCurrency) > 0) {
            throw new IllegalArgumentException("balanceLimitInTripCurrency must be within the pending balance");
        }
        BigDecimal maxAllowedAmount = paymentLimit == null
                ? moneyPolicy.maxAllowedPaymentAmount(
                        balanceLimit, tripCurrency, paymentCurrency, exchangeRate)
                : moneyPolicy.requireMoney(paymentLimit.maxAllowedAmount(), "maxAllowedAmount");
        if (maxAllowedAmount.signum() < 0) {
            throw new IllegalArgumentException("maxAllowedAmount must not be negative");
        }
        BigDecimal amountInTripCurrency = moneyPolicy.convertPaymentToTripCurrency(
                normalizedReportedAmount,
                tripCurrency,
                paymentCurrency,
                exchangeRate
        );

        if (normalizedReportedAmount.compareTo(maxAllowedAmount) > 0
                || amountInTripCurrency.compareTo(balanceLimit) > 0) {
            throw new PaymentBalanceExceededException(
                    maxAllowedAmount,
                    amountInTripCurrency.subtract(balanceLimit)
                            .setScale(PaymentMoneyPolicy.MONEY_SCALE, RoundingMode.UNNECESSARY)
            );
        }
        if (amountInTripCurrency.signum() <= 0) {
            throw new IllegalArgumentException("El monto informado es demasiado bajo para imputarse");
        }

        List<TripAllocation> tripAllocations = allocateTripCurrency(
                installments, amountInTripCurrency);
        List<PlannedAllocation> allocations = distributeReportedAmount(
                tripAllocations, normalizedReportedAmount, amountInTripCurrency);
        PlanResult result = new PlanResult(
                tripCurrency,
                paymentCurrency,
                normalizedReportedAmount,
                maxAllowedAmount,
                exchangeRate,
                totalPendingAmountInTripCurrency,
                amountInTripCurrency,
                List.copyOf(allocations)
        );
        assertConservation(result);
        return result;
    }

    private List<TripAllocation> allocateTripCurrency(
            List<Installment> installments,
            BigDecimal amountInTripCurrency
    ) {
        List<TripAllocation> allocations = new ArrayList<>();
        BigDecimal remainingTripAmount = amountInTripCurrency;
        int allocationOrder = 1;
        for (Installment installment : installments) {
            if (remainingTripAmount.signum() <= 0) {
                break;
            }
            BigDecimal installmentRemainingAmount = getRemainingAmount(installment);
            if (installmentRemainingAmount.signum() <= 0) {
                continue;
            }

            BigDecimal allocatedTripAmount = remainingTripAmount.compareTo(installmentRemainingAmount) <= 0
                    ? remainingTripAmount
                    : installmentRemainingAmount;
            allocations.add(new TripAllocation(
                    installment,
                    allocationOrder++,
                    installmentRemainingAmount,
                    allocatedTripAmount
            ));
            remainingTripAmount = remainingTripAmount.subtract(allocatedTripAmount)
                    .setScale(PaymentMoneyPolicy.MONEY_SCALE, RoundingMode.UNNECESSARY);
        }
        if (remainingTripAmount.signum() != 0) {
            throw new IllegalStateException("FIN-001: no se pudo imputar el monto convertido completo");
        }
        return allocations;
    }

    private List<PlannedAllocation> distributeReportedAmount(
            List<TripAllocation> tripAllocations,
            BigDecimal reportedAmount,
            BigDecimal amountInTripCurrency
    ) {
        BigInteger reportedCents = toCents(reportedAmount);
        BigInteger tripCents = toCents(amountInTripCurrency);
        List<BigInteger> allocatedReportedCents = new ArrayList<>();
        List<ReportedShare> shares = new ArrayList<>();
        BigInteger distributedCents = BigInteger.ZERO;

        for (int index = 0; index < tripAllocations.size(); index++) {
            BigInteger allocationTripCents = toCents(tripAllocations.get(index).amountInTripCurrency());
            BigInteger[] quotientAndRemainder = reportedCents.multiply(allocationTripCents)
                    .divideAndRemainder(tripCents);
            allocatedReportedCents.add(quotientAndRemainder[0]);
            distributedCents = distributedCents.add(quotientAndRemainder[0]);
            shares.add(new ReportedShare(index, quotientAndRemainder[1]));
        }

        BigInteger residualCents = reportedCents.subtract(distributedCents);
        shares.sort(Comparator
                .comparing(ReportedShare::remainder, Comparator.reverseOrder())
                .thenComparing(ReportedShare::index));
        for (int offset = 0; offset < residualCents.intValueExact(); offset++) {
            int allocationIndex = shares.get(offset).index();
            allocatedReportedCents.set(
                    allocationIndex,
                    allocatedReportedCents.get(allocationIndex).add(BigInteger.ONE)
            );
        }

        List<PlannedAllocation> allocations = new ArrayList<>();
        for (int index = 0; index < tripAllocations.size(); index++) {
            TripAllocation tripAllocation = tripAllocations.get(index);
            allocations.add(new PlannedAllocation(
                    tripAllocation.installment(),
                    tripAllocation.allocationOrder(),
                    tripAllocation.remainingAmount(),
                    new BigDecimal(allocatedReportedCents.get(index), PaymentMoneyPolicy.MONEY_SCALE),
                    tripAllocation.amountInTripCurrency()
            ));
        }
        return allocations;
    }

    public void assertConservation(PlanResult plan) {
        BigDecimal reportedTotal = plan.allocations().stream()
                .map(PlannedAllocation::reportedAmount)
                .reduce(BigDecimal.ZERO, BigDecimal::add)
                .setScale(PaymentMoneyPolicy.MONEY_SCALE, RoundingMode.UNNECESSARY);
        BigDecimal tripTotal = plan.allocations().stream()
                .map(PlannedAllocation::amountInTripCurrency)
                .reduce(BigDecimal.ZERO, BigDecimal::add)
                .setScale(PaymentMoneyPolicy.MONEY_SCALE, RoundingMode.UNNECESSARY);

        if (reportedTotal.compareTo(plan.reportedAmount()) != 0
                || tripTotal.compareTo(plan.amountInTripCurrency()) != 0) {
            throw new IllegalStateException("FIN-001: el plan no conserva ambos totales monetarios");
        }
        for (PlannedAllocation allocation : plan.allocations()) {
            if (allocation.reportedAmount().signum() < 0
                    || allocation.amountInTripCurrency().signum() < 0
                    || allocation.amountInTripCurrency().compareTo(allocation.remainingAmount()) > 0) {
                throw new IllegalStateException("FIN-001: el plan contiene una imputación inválida");
            }
        }
    }

    public BigDecimal convertTripToPaymentCurrency(
            BigDecimal amountInTripCurrency,
            Currency tripCurrency,
            Currency paymentCurrency,
            BigDecimal exchangeRate
    ) {
        return moneyPolicy.convertTripToPaymentCurrency(
                amountInTripCurrency, tripCurrency, paymentCurrency, exchangeRate);
    }

    public BigDecimal convertPaymentToTripCurrency(
            BigDecimal reportedAmount,
            Currency tripCurrency,
            Currency paymentCurrency,
            BigDecimal exchangeRate
    ) {
        return moneyPolicy.convertPaymentToTripCurrency(
                reportedAmount, tripCurrency, paymentCurrency, exchangeRate);
    }

    public BigDecimal getRemainingAmount(Installment installment) {
        BigDecimal totalDue = installment.getTotalDue() == null ? BigDecimal.ZERO : installment.getTotalDue();
        BigDecimal paidAmount = installment.getPaidAmount() == null ? BigDecimal.ZERO : installment.getPaidAmount();
        BigDecimal remainingAmount = totalDue.subtract(paidAmount);
        return remainingAmount.signum() < 0
                ? BigDecimal.ZERO.setScale(PaymentMoneyPolicy.MONEY_SCALE)
                : remainingAmount.setScale(PaymentMoneyPolicy.MONEY_SCALE, RoundingMode.HALF_UP);
    }

    private BigInteger toCents(BigDecimal amount) {
        return amount.movePointRight(PaymentMoneyPolicy.MONEY_SCALE).toBigIntegerExact();
    }
}
