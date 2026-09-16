package com.agencia.pagos.payment.dto;

import jakarta.validation.constraints.NotNull;

import java.math.BigDecimal;

public record ReviewPaymentDTO(
        @NotNull BigDecimal approvedAmount,
        String adminObservation
) {
}
