package com.agencia.pagos.payment.dto;

import com.agencia.pagos.payment.PaymentHistoryStatus;
import com.agencia.pagos.shared.money.Currency;
import com.agencia.pagos.payment.PaymentMethod;
import com.fasterxml.jackson.databind.annotation.JsonSerialize;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

public record PendingPaymentReviewDTO(
        Long submissionId,
        PaymentHistoryStatus status,
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
        String fileKey,
        Long bankAccountId,
        String bankAccountDisplayName,
        String bankAccountAlias,
        Long tripId,
        String tripName,
        Currency tripCurrency,
        Long userId,
        String userName,
        String userLastname,
        String userEmail,
        String studentName,
        String studentDni,
        List<PaymentBatchInstallmentDTO> allocations
) {
}
