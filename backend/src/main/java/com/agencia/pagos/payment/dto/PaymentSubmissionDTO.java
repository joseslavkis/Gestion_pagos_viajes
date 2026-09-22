package com.agencia.pagos.payment.dto;

import com.agencia.pagos.shared.money.Currency;
import com.agencia.pagos.payment.PaymentHistoryStatus;
import com.agencia.pagos.payment.PaymentMethod;
import com.fasterxml.jackson.databind.annotation.JsonSerialize;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

public record PaymentSubmissionDTO(
        Long submissionId,
        PaymentHistoryStatus status,
        @JsonSerialize(using = CanonicalDecimalSerializer.class) BigDecimal reportedAmount,
        @JsonSerialize(using = CanonicalDecimalSerializer.class) BigDecimal approvedAmount,
        @JsonSerialize(using = CanonicalDecimalSerializer.class) BigDecimal rejectedAmount,
        Currency paymentCurrency,
        @JsonSerialize(using = CanonicalDecimalSerializer.class) BigDecimal exchangeRate,
        @JsonSerialize(using = CanonicalDecimalSerializer.class) BigDecimal amountInTripCurrency,
        @JsonSerialize(using = CanonicalDecimalSerializer.class) BigDecimal approvedAmountInTripCurrency,
        LocalDate reportedPaymentDate,
        LocalDate quoteRequestedDate,
        LocalDate quoteEffectiveDate,
        String quoteSource,
        String quoteProvider,
        String quoteProviderTimestamp,
        String calculationVersion,
        PaymentMethod paymentMethod,
        String fileKey,
        String adminObservation,
        Long bankAccountId,
        String bankAccountDisplayName,
        String bankAccountAlias,
        Long tripId,
        String tripName,
        Currency tripCurrency,
        Long studentId,
        String studentName,
        String studentDni,
        List<PaymentBatchInstallmentDTO> installments
) {
}
