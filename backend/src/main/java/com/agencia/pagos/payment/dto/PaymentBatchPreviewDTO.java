package com.agencia.pagos.payment.dto;

import com.agencia.pagos.shared.money.Currency;

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
        LocalDate quoteRequestedDate,
        LocalDate quoteEffectiveDate,
        String quoteSource,
        String quoteProviderTimestamp,
        String previewToken,
        List<PaymentBatchInstallmentDTO> installments
) {
}
