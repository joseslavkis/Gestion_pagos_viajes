package com.agencia.pagos.payment.dto;

import com.agencia.pagos.shared.money.Currency;
import com.agencia.pagos.payment.PaymentMethod;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

public record PaymentBatchDTO(
        Long batchId,
        BigDecimal reportedAmount,
        Currency paymentCurrency,
        BigDecimal exchangeRate,
        BigDecimal amountInTripCurrency,
        LocalDate reportedPaymentDate,
        PaymentMethod paymentMethod,
        Long bankAccountId,
        String bankAccountDisplayName,
        String bankAccountAlias,
        List<PaymentBatchInstallmentDTO> installments
) {
}
