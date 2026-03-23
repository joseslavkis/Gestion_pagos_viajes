package com.agencia.pagos.dtos.request;

import jakarta.validation.constraints.NotNull;

public record BankAccountActiveDTO(@NotNull Boolean active) {
}
