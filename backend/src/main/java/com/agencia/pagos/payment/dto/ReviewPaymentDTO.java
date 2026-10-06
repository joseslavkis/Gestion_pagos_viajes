package com.agencia.pagos.payment.dto;

import com.agencia.pagos.shared.money.Currency;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;

public record ReviewPaymentDTO(
        @NotNull(message = "Debe indicar el monto aprobado.") BigDecimal approvedAmount,
        @NotNull(message = "Debe seleccionar la moneda aprobada.") Currency approvedCurrency,
        @Size(max = 500, message = "La observación administrativa no puede superar los 500 caracteres.") String adminObservation
) {
}
