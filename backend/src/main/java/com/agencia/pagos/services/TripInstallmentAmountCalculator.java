package com.agencia.pagos.services;

import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.List;

@Component
public class TripInstallmentAmountCalculator {

    private static final int MONEY_SCALE = 2;

    public List<BigDecimal> calculate(
            BigDecimal totalAmount,
            BigDecimal firstInstallmentAmount,
            int installmentsCount
    ) {
        validateInputs(totalAmount, firstInstallmentAmount, installmentsCount);

        BigDecimal normalizedFirstInstallment = normalizeMoney(firstInstallmentAmount);
        if (installmentsCount == 1) {
            return List.of(normalizedFirstInstallment);
        }

        BigDecimal remainingAmount = normalizeMoney(totalAmount).subtract(normalizedFirstInstallment);
        BigDecimal remainingInstallmentAmount = roundUpInstallment(
                remainingAmount.divide(BigDecimal.valueOf(installmentsCount - 1L), 10, RoundingMode.CEILING)
        );

        List<BigDecimal> amounts = new ArrayList<>(installmentsCount);
        amounts.add(normalizedFirstInstallment);
        for (int index = 1; index < installmentsCount; index++) {
            amounts.add(remainingInstallmentAmount);
        }
        return amounts;
    }

    public BigDecimal calculateRemainingInstallmentAmount(
            BigDecimal totalAmount,
            BigDecimal firstInstallmentAmount,
            int installmentsCount
    ) {
        return calculate(totalAmount, firstInstallmentAmount, installmentsCount)
                .get(Math.min(1, installmentsCount - 1));
    }

    public BigDecimal normalizeFirstInstallmentAmount(
            BigDecimal totalAmount,
            BigDecimal firstInstallmentAmount,
            int installmentsCount
    ) {
        validateInputs(totalAmount, firstInstallmentAmount, installmentsCount);
        return normalizeMoney(firstInstallmentAmount);
    }

    private void validateInputs(
            BigDecimal totalAmount,
            BigDecimal firstInstallmentAmount,
            int installmentsCount
    ) {
        if (totalAmount == null) {
            throw new IllegalArgumentException("El monto total del viaje es obligatorio");
        }
        if (firstInstallmentAmount == null) {
            throw new IllegalArgumentException("El monto de la primera cuota es obligatorio");
        }
        if (installmentsCount < 1) {
            throw new IllegalArgumentException("La cantidad de cuotas debe ser mayor a cero");
        }

        BigDecimal normalizedTotal = normalizeMoney(totalAmount);
        BigDecimal normalizedFirstInstallment = normalizeMoney(firstInstallmentAmount);

        if (normalizedTotal.compareTo(BigDecimal.ZERO) <= 0) {
            throw new IllegalArgumentException("El monto total del viaje debe ser mayor a cero");
        }
        if (normalizedFirstInstallment.compareTo(BigDecimal.ZERO) <= 0) {
            throw new IllegalArgumentException("La primera cuota debe ser mayor a cero");
        }
        if (normalizedFirstInstallment.compareTo(normalizedTotal) > 0) {
            throw new IllegalArgumentException("La primera cuota no puede superar el monto total del viaje");
        }
        if (installmentsCount > 1 && normalizedFirstInstallment.compareTo(normalizedTotal) >= 0) {
            throw new IllegalArgumentException("La primera cuota debe ser menor al monto total cuando el viaje tiene más de una cuota");
        }
    }

    private BigDecimal normalizeMoney(BigDecimal amount) {
        return amount.setScale(MONEY_SCALE, RoundingMode.HALF_UP);
    }

    private BigDecimal roundUpInstallment(BigDecimal amount) {
        return amount.setScale(0, RoundingMode.CEILING).setScale(MONEY_SCALE, RoundingMode.UNNECESSARY);
    }
}
