package com.agencia.pagos.services;

import com.agencia.pagos.entities.Currency;
import com.agencia.pagos.entities.Installment;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.List;

@Component
public class PaymentAllocationPlanner {

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

    public PlanResult plan(
            List<Installment> installments,
            BigDecimal reportedAmount,
            Currency paymentCurrency,
            BigDecimal exchangeRate
    ) {
        if (installments == null || installments.isEmpty()) {
            throw new IllegalArgumentException("Debe haber al menos una cuota seleccionada");
        }
        if (reportedAmount == null || reportedAmount.compareTo(BigDecimal.ZERO) <= 0) {
            throw new IllegalArgumentException("El monto informado debe ser mayor a 0");
        }

        Currency tripCurrency = installments.get(0).getTrip().getCurrency();
        BigDecimal totalPendingAmountInTripCurrency = installments.stream()
                .map(this::getRemainingAmount)
                .reduce(BigDecimal.ZERO, BigDecimal::add)
                .setScale(2, RoundingMode.HALF_UP);

        BigDecimal maxAllowedAmount = convertTripToPaymentCurrency(
                totalPendingAmountInTripCurrency,
                tripCurrency,
                paymentCurrency,
                exchangeRate
        );

        if (reportedAmount.compareTo(maxAllowedAmount) > 0) {
            throw new IllegalStateException("El monto informado supera el saldo pendiente total de esta inscripción");
        }

        BigDecimal amountInTripCurrency = convertPaymentToTripCurrency(
                reportedAmount,
                tripCurrency,
                paymentCurrency,
                exchangeRate
        );

        if (amountInTripCurrency.compareTo(BigDecimal.ZERO) <= 0) {
            throw new IllegalArgumentException("El monto informado es demasiado bajo para imputarse");
        }

        List<PlannedAllocation> allocations = new ArrayList<>();
        BigDecimal remainingTripAmount = amountInTripCurrency;
        BigDecimal accumulatedReported = BigDecimal.ZERO;
        int allocationOrder = 1;
        for (Installment installment : installments) {
            if (remainingTripAmount.compareTo(BigDecimal.ZERO) <= 0) {
                break;
            }

            BigDecimal installmentRemainingAmount = getRemainingAmount(installment);
            if (installmentRemainingAmount.compareTo(BigDecimal.ZERO) <= 0) {
                continue;
            }

            BigDecimal allocatedTripAmount = installmentRemainingAmount.min(remainingTripAmount)
                    .setScale(2, RoundingMode.HALF_UP);

            BigDecimal allocatedReportedAmount;
            if (paymentCurrency == tripCurrency) {
                allocatedReportedAmount = allocatedTripAmount;
            } else if (remainingTripAmount.compareTo(allocatedTripAmount) == 0) {
                allocatedReportedAmount = reportedAmount.subtract(accumulatedReported).setScale(2, RoundingMode.HALF_UP);
            } else {
                allocatedReportedAmount = convertTripToPaymentCurrency(
                        allocatedTripAmount,
                        tripCurrency,
                        paymentCurrency,
                        exchangeRate
                );
                accumulatedReported = accumulatedReported.add(allocatedReportedAmount);
            }

            allocations.add(new PlannedAllocation(
                    installment,
                    allocationOrder++,
                    installmentRemainingAmount,
                    allocatedReportedAmount,
                    allocatedTripAmount
            ));
            remainingTripAmount = remainingTripAmount.subtract(allocatedTripAmount).setScale(2, RoundingMode.HALF_UP);
        }

        return new PlanResult(
                tripCurrency,
                paymentCurrency,
                reportedAmount.setScale(2, RoundingMode.HALF_UP),
                maxAllowedAmount,
                exchangeRate,
                totalPendingAmountInTripCurrency,
                amountInTripCurrency,
                allocations
        );
    }

    public BigDecimal convertTripToPaymentCurrency(
            BigDecimal amountInTripCurrency,
            Currency tripCurrency,
            Currency paymentCurrency,
            BigDecimal exchangeRate
    ) {
        if (tripCurrency == paymentCurrency) {
            return amountInTripCurrency.setScale(2, RoundingMode.HALF_UP);
        }

        if (exchangeRate == null || exchangeRate.compareTo(BigDecimal.ZERO) <= 0) {
            throw new IllegalArgumentException("Debe informarse un tipo de cambio válido");
        }

        if (tripCurrency == Currency.USD && paymentCurrency == Currency.ARS) {
            return amountInTripCurrency.multiply(exchangeRate).setScale(2, RoundingMode.HALF_UP);
        }

        if (tripCurrency == Currency.ARS && paymentCurrency == Currency.USD) {
            return amountInTripCurrency.divide(exchangeRate, 2, RoundingMode.HALF_UP);
        }

        throw new IllegalStateException("Conversión de moneda no soportada");
    }

    public BigDecimal convertPaymentToTripCurrency(
            BigDecimal reportedAmount,
            Currency tripCurrency,
            Currency paymentCurrency,
            BigDecimal exchangeRate
    ) {
        if (tripCurrency == paymentCurrency) {
            return reportedAmount.setScale(2, RoundingMode.HALF_UP);
        }

        if (exchangeRate == null || exchangeRate.compareTo(BigDecimal.ZERO) <= 0) {
            throw new IllegalArgumentException("Debe informarse un tipo de cambio válido");
        }

        if (tripCurrency == Currency.USD && paymentCurrency == Currency.ARS) {
            return reportedAmount.divide(exchangeRate, 2, RoundingMode.HALF_UP);
        }

        if (tripCurrency == Currency.ARS && paymentCurrency == Currency.USD) {
            return reportedAmount.multiply(exchangeRate).setScale(2, RoundingMode.HALF_UP);
        }

        throw new IllegalStateException("Conversión de moneda no soportada");
    }

    public BigDecimal getRemainingAmount(Installment installment) {
        BigDecimal totalDue = installment.getTotalDue() == null ? BigDecimal.ZERO : installment.getTotalDue();
        BigDecimal paidAmount = installment.getPaidAmount() == null ? BigDecimal.ZERO : installment.getPaidAmount();
        return totalDue.subtract(paidAmount).max(BigDecimal.ZERO).setScale(2, RoundingMode.HALF_UP);
    }
}
