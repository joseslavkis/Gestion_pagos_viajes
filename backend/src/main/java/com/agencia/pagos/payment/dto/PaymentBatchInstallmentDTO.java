package com.agencia.pagos.payment.dto;

import com.agencia.pagos.payment.ReceiptStatus;
import com.fasterxml.jackson.databind.annotation.JsonSerialize;

import java.math.BigDecimal;
import java.time.LocalDate;

public record PaymentBatchInstallmentDTO(
        Long receiptId,
        Long installmentId,
        Integer installmentNumber,
        LocalDate dueDate,
        @JsonSerialize(using = CanonicalDecimalSerializer.class) BigDecimal totalDue,
        @JsonSerialize(using = CanonicalDecimalSerializer.class) BigDecimal paidAmount,
        @JsonSerialize(using = CanonicalDecimalSerializer.class) BigDecimal remainingAmount,
        @JsonSerialize(using = CanonicalDecimalSerializer.class) BigDecimal reportedAmount,
        @JsonSerialize(using = CanonicalDecimalSerializer.class) BigDecimal amountInTripCurrency,
        ReceiptStatus status
) {
}
