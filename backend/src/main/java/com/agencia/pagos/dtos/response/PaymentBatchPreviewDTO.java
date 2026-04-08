package com.agencia.pagos.dtos.response;

import com.agencia.pagos.entities.Currency;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

public record PaymentBatchPreviewDTO(
        Long anchorInstallmentId,
        Currency tripCurrency,
        Currency paymentCurrency,
        BigDecimal reportedAmount,
        BigDecimal maxAllowedAmount,
        BigDecimal exchangeRate,
        BigDecimal totalPendingAmountInTripCurrency,
        BigDecimal amountInTripCurrency,
        LocalDate reportedPaymentDate,
        List<PaymentBatchInstallmentDTO> installments
) {
}
