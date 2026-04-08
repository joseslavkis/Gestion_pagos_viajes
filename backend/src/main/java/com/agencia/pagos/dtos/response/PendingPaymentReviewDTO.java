package com.agencia.pagos.dtos.response;

import com.agencia.pagos.entities.PaymentHistoryStatus;
import com.agencia.pagos.entities.Currency;
import com.agencia.pagos.entities.PaymentMethod;

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
