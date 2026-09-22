package com.agencia.pagos.payment;

import java.math.BigDecimal;

public class PaymentBalanceExceededException extends IllegalArgumentException {

    private final BigDecimal maxAllowedAmount;
    private final BigDecimal residualInTripCurrency;

    public PaymentBalanceExceededException(
            BigDecimal maxAllowedAmount,
            BigDecimal residualInTripCurrency
    ) {
        super("FIN-001: el monto convertido supera el saldo imputable; maxAllowedAmount="
                + maxAllowedAmount.toPlainString()
                + "; residualInTripCurrency="
                + residualInTripCurrency.toPlainString());
        this.maxAllowedAmount = maxAllowedAmount;
        this.residualInTripCurrency = residualInTripCurrency;
    }

    public BigDecimal maxAllowedAmount() {
        return maxAllowedAmount;
    }

    public BigDecimal residualInTripCurrency() {
        return residualInTripCurrency;
    }
}
