package com.agencia.pagos.dtos.response;

import com.agencia.pagos.entities.Currency;
import com.agencia.pagos.entities.PaymentMethod;

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
