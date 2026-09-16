package com.agencia.pagos.payment.dto;

import com.agencia.pagos.shared.money.Currency;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

import java.math.BigDecimal;
import java.time.LocalDate;

public record PaymentPreviewRequestDTO(
        @NotNull Long anchorInstallmentId,
        @NotNull @Positive BigDecimal reportedAmount,
        @NotNull LocalDate reportedPaymentDate,
        @NotNull Currency paymentCurrency
) {
}
