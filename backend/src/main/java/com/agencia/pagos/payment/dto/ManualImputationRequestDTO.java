package com.agencia.pagos.payment.dto;

import com.agencia.pagos.shared.money.Currency;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;
import java.time.LocalDate;

public record ManualImputationRequestDTO(
        @NotNull Long anchorInstallmentId,
        @NotNull BigDecimal reportedAmount,
        @NotNull Currency paymentCurrency,
        @NotNull LocalDate reportedPaymentDate,
        @NotNull String previewToken,
        @Size(max = 500, message = "El motivo no puede superar los 500 caracteres") String reason
) {
}
