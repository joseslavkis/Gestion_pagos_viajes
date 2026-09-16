package com.agencia.pagos.payment.dto;

import com.agencia.pagos.payment.PaymentHistoryStatus;
import com.agencia.pagos.shared.money.Currency;
import com.agencia.pagos.payment.PaymentMethod;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

public record PendingPaymentReviewDTO(
        Long submissionId,
        PaymentHistoryStatus status,
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
