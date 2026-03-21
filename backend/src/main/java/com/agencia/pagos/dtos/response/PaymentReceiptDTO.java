package com.agencia.pagos.dtos.response;

import com.agencia.pagos.entities.Currency;
import com.agencia.pagos.entities.PaymentMethod;
import com.agencia.pagos.entities.ReceiptStatus;

import java.math.BigDecimal;
import java.time.LocalDate;

public record PaymentReceiptDTO(
        Long id,
        Long installmentId,
        Integer installmentNumber,
        BigDecimal reportedAmount,
        Currency paymentCurrency,
        BigDecimal exchangeRate,
        BigDecimal amountInTripCurrency,
        LocalDate reportedPaymentDate,
        PaymentMethod paymentMethod,
        ReceiptStatus status,
        String fileKey,
        String adminObservation
) {
}
