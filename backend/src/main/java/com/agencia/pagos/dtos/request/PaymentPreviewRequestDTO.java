package com.agencia.pagos.dtos.request;

import com.agencia.pagos.entities.Currency;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

import java.time.LocalDate;

public record PaymentPreviewRequestDTO(
        @NotNull Long anchorInstallmentId,
        @NotNull @Positive java.math.BigDecimal reportedAmount,
        @NotNull LocalDate reportedPaymentDate,
        @NotNull Currency paymentCurrency
) {
}
