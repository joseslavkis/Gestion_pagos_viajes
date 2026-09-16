package com.agencia.pagos.payment.dto;

import com.agencia.pagos.shared.money.Currency;
import com.agencia.pagos.payment.PaymentMethod;
import com.agencia.pagos.payment.ReceiptStatus;

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
        String adminObservation,
        Long bankAccountId,
        String bankAccountDisplayName,
        String bankAccountAlias
) {
}
