package com.agencia.pagos.payment.dto;

public enum PaymentCalculationStatus {
    READY,
    AMOUNT_EXCEEDS_BALANCE,
    UNPAYABLE,
    QUOTE_UNAVAILABLE,
    EXPIRED
}
