package com.agencia.pagos.dtos.request;

import com.agencia.pagos.entities.Currency;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

public record BankAccountUpdateDTO(
        @NotBlank String bankName,
        @NotBlank String accountLabel,
        @NotBlank String accountHolder,
        @NotBlank String accountNumber,
        @NotBlank String taxId,
        @NotBlank String cbu,
        @NotBlank String alias,
        @NotNull Currency currency,
        @Min(0) Integer displayOrder
) {
}
