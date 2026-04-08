package com.agencia.pagos.dtos.response;

import com.agencia.pagos.entities.Currency;
import com.agencia.pagos.entities.PaymentHistoryStatus;
import com.agencia.pagos.entities.PaymentMethod;

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
