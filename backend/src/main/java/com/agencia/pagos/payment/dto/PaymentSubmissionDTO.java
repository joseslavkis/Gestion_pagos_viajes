package com.agencia.pagos.payment.dto;

import com.agencia.pagos.shared.money.Currency;
import com.agencia.pagos.payment.PaymentHistoryStatus;
import com.agencia.pagos.payment.PaymentMethod;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

public record PaymentSubmissionDTO(
        Long submissionId,
        PaymentHistoryStatus status,
        BigDecimal reportedAmount,
        BigDecimal approvedAmount,
        BigDecimal rejectedAmount,
        Currency paymentCurrency,
        BigDecimal exchangeRate,
        BigDecimal amountInTripCurrency,
        BigDecimal approvedAmountInTripCurrency,
        LocalDate reportedPaymentDate,
        LocalDate quoteRequestedDate,
        LocalDate quoteEffectiveDate,
        String quoteSource,
        String quoteProviderTimestamp,
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
