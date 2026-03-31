package com.agencia.pagos.dtos.response;

import com.agencia.pagos.entities.ReceiptStatus;

import java.math.BigDecimal;
import java.time.LocalDate;

public record PendingPaymentReviewLineDTO(
        Long receiptId,
        ReceiptStatus status,
        BigDecimal reportedAmount,
        BigDecimal amountInTripCurrency,
        Long installmentId,
        Integer installmentNumber,
        LocalDate installmentDueDate,
        BigDecimal installmentTotalDue,
        String adminObservation
) {
}
