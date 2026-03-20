package com.agencia.pagos.dtos.request;

import com.agencia.pagos.entities.PaymentMethod;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

import java.math.BigDecimal;
import java.time.LocalDate;

public record RegisterPaymentDTO(
        @NotNull Long installmentId,
        @NotNull @Positive BigDecimal reportedAmount,
        @NotNull LocalDate reportedPaymentDate,
        @NotNull PaymentMethod paymentMethod
) {
}
