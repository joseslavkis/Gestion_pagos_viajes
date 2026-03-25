package com.agencia.pagos.dtos.response;

import com.agencia.pagos.entities.Currency;
import com.agencia.pagos.entities.PaymentMethod;
import com.agencia.pagos.entities.ReceiptStatus;

import java.math.BigDecimal;
import java.time.LocalDate;

public record PendingPaymentReviewDTO(
        Long receiptId,
        ReceiptStatus status,
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
        Long installmentId,
        Integer installmentNumber,
        LocalDate installmentDueDate,
        BigDecimal installmentTotalDue,
        Long tripId,
        String tripName,
        Currency tripCurrency,
        Long userId,
        String userName,
        String userLastname,
        String userEmail,
        String studentName,
        String studentDni
) {
}
