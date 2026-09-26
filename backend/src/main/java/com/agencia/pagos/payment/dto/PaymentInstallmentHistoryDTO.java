package com.agencia.pagos.payment.dto;

import com.agencia.pagos.shared.money.Currency;
import com.agencia.pagos.payment.PaymentHistoryStatus;
import com.agencia.pagos.payment.PaymentMethod;
import com.fasterxml.jackson.databind.annotation.JsonSerialize;

import java.math.BigDecimal;
import java.time.LocalDate;

public record PaymentInstallmentHistoryDTO(
        Long id,
        Long submissionId,
        Long installmentId,
        Integer installmentNumber,
        @JsonSerialize(using = CanonicalDecimalSerializer.class) BigDecimal reportedAmount,
        Currency paymentCurrency,
        @JsonSerialize(using = CanonicalDecimalSerializer.class) BigDecimal exchangeRate,
        @JsonSerialize(using = CanonicalDecimalSerializer.class) BigDecimal amountInTripCurrency,
        LocalDate reportedPaymentDate,
        LocalDate quoteRequestedDate,
        LocalDate quoteEffectiveDate,
        String quoteSource,
        String quoteProvider,
        String quoteProviderTimestamp,
        String calculationVersion,
        PaymentMethod paymentMethod,
        PaymentHistoryStatus status,
        String fileKey,
        String adminObservation,
        Long bankAccountId,
        String bankAccountDisplayName,
        String bankAccountAlias
) {
}
