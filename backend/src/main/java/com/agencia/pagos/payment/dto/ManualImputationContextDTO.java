package com.agencia.pagos.payment.dto;

import com.agencia.pagos.shared.money.Currency;
import com.fasterxml.jackson.databind.annotation.JsonSerialize;

import java.math.BigDecimal;

public record ManualImputationContextDTO(
        boolean eligible,
        Long selectedInstallmentId,
        Long firstPayableInstallmentId,
        Integer firstPayableInstallmentNumber,
        @JsonSerialize(using = CanonicalDecimalSerializer.class) BigDecimal anchorRemainingAmount,
        @JsonSerialize(using = CanonicalDecimalSerializer.class) BigDecimal totalRemainingAmountInTripCurrency,
        Currency tripCurrency,
        boolean hasPendingReview,
        String message
) {
}
