package com.agencia.pagos.trip;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.junit.jupiter.api.Assertions.assertEquals;

class InstallmentTest {

    @Test
    void recalculateTotalDue_usesCapitalAmountOnly() {
        Installment installment = new Installment();
        installment.setCapitalAmount(new BigDecimal("1000.00"));

        installment.recalculateTotalDue();

        assertEquals(new BigDecimal("1000.00"), installment.getTotalDue());
    }
}
