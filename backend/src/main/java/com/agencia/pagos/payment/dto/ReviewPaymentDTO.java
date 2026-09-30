package com.agencia.pagos.payment.dto;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;

public record ReviewPaymentDTO(
        @NotNull BigDecimal approvedAmount,
        @Size(max = 500) String adminObservation
) {
}
