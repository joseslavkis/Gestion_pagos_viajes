package com.agencia.pagos.dtos.request;

import jakarta.validation.constraints.NotNull;

import java.math.BigDecimal;

public record ReviewPaymentDTO(
        @NotNull BigDecimal approvedAmount,
        String adminObservation
) {
}
