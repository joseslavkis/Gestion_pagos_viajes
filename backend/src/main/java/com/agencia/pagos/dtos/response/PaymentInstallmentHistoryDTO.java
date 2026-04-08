package com.agencia.pagos.dtos.response;

import com.agencia.pagos.entities.Currency;
import com.agencia.pagos.entities.PaymentHistoryStatus;
import com.agencia.pagos.entities.PaymentMethod;

import java.math.BigDecimal;
import java.time.LocalDate;

public record PaymentInstallmentHistoryDTO(
        Long id,
        Long submissionId,
        Long installmentId,
        Integer installmentNumber,
        BigDecimal reportedAmount,
        Currency paymentCurrency,
        BigDecimal exchangeRate,
        BigDecimal amountInTripCurrency,
        LocalDate reportedPaymentDate,
        PaymentMethod paymentMethod,
        PaymentHistoryStatus status,
        String fileKey,
        String adminObservation,
        Long bankAccountId,
        String bankAccountDisplayName,
        String bankAccountAlias
) {
}
