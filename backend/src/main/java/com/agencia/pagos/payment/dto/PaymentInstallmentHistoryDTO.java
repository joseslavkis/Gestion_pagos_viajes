package com.agencia.pagos.payment.dto;

import com.agencia.pagos.shared.money.Currency;
import com.agencia.pagos.payment.PaymentHistoryStatus;
import com.agencia.pagos.payment.PaymentMethod;

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
        LocalDate quoteRequestedDate,
        LocalDate quoteEffectiveDate,
        String quoteSource,
        String quoteProviderTimestamp,
        PaymentMethod paymentMethod,
        PaymentHistoryStatus status,
        String fileKey,
        String adminObservation,
        Long bankAccountId,
        String bankAccountDisplayName,
        String bankAccountAlias
) {
}
