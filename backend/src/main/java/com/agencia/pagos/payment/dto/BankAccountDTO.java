package com.agencia.pagos.payment.dto;

import com.agencia.pagos.shared.money.Currency;

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
