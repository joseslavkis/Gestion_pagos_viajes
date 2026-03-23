package com.agencia.pagos.dtos.response;

import com.agencia.pagos.entities.Currency;

public record BankAccountDTO(
        Long id,
        String bankName,
        String accountLabel,
        String accountHolder,
        String accountNumber,
        String taxId,
        String cbu,
        String alias,
        Currency currency,
        boolean active,
        Integer displayOrder
) {
}
