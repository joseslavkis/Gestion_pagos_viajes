package com.agencia.pagos.dtos.response;

import com.agencia.pagos.entities.ReceiptStatus;

import java.math.BigDecimal;
import java.time.LocalDate;

public record PaymentBatchInstallmentDTO(
        Long receiptId,
        Long installmentId,
        Integer installmentNumber,
        LocalDate dueDate,
        BigDecimal totalDue,
        BigDecimal paidAmount,
        BigDecimal remainingAmount,
        BigDecimal reportedAmount,
        BigDecimal amountInTripCurrency,
        ReceiptStatus status
) {
}
