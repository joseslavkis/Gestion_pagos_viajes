package com.agencia.pagos.dtos.internal;

import java.math.BigDecimal;
import java.time.LocalDate;

public record SpreadsheetReceiptRowDTO(
        Integer installmentNumber,
        LocalDate installmentDueDate,
        String studentLastname,
        String studentName,
        String studentDni,
        LocalDate reportedPaymentDate,
        String paymentMethod,
        BigDecimal reportedAmount,
        String paymentCurrency,
        BigDecimal exchangeRate,
        BigDecimal amountInTripCurrency,
        String status,
        String adminObservation
) {
}
