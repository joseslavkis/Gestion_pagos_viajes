package com.agencia.pagos.payment.dto;

import jakarta.validation.constraints.NotNull;

public record BankAccountActiveDTO(@NotNull Boolean active) {
}
