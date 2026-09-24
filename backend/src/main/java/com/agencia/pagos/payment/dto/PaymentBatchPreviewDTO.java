package com.agencia.pagos.payment.dto;

import com.agencia.pagos.shared.money.Currency;
import com.fasterxml.jackson.databind.annotation.JsonSerialize;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

public record PaymentBatchPreviewDTO(
        Long anchorInstallmentId,
        Currency tripCurrency,
        Currency paymentCurrency,
        @JsonSerialize(using = CanonicalDecimalSerializer.class) BigDecimal reportedAmount,
        @JsonSerialize(using = CanonicalDecimalSerializer.class) BigDecimal maxAllowedAmount,
        @JsonSerialize(using = CanonicalDecimalSerializer.class) BigDecimal exchangeRate,
        @JsonSerialize(using = CanonicalDecimalSerializer.class) BigDecimal totalPendingAmountInTripCurrency,
        @JsonSerialize(using = CanonicalDecimalSerializer.class) BigDecimal amountInTripCurrency,
        LocalDate reportedPaymentDate,
        LocalDate quoteRequestedDate,
        LocalDate quoteEffectiveDate,
        String quoteSource,
        String quoteProvider,
        String quoteProviderTimestamp,
        String calculationVersion,
        String previewToken,
        List<PaymentBatchInstallmentDTO> installments
) {
}
