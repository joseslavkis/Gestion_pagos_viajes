package com.agencia.pagos.payment.dto;

import com.agencia.pagos.shared.money.Currency;
import jakarta.validation.constraints.NotNull;

import java.math.BigDecimal;
import java.time.LocalDate;

public record PaymentCalculationRequestDTO(
        @NotNull Long anchorInstallmentId,
        @NotNull Currency paymentCurrency,
        @NotNull LocalDate reportedPaymentDate,
        @NotNull PaymentCalculationIntent intent,
        BigDecimal reportedAmount,
        String previewToken
) {
}
