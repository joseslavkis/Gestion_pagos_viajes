package com.agencia.pagos;

import com.agencia.pagos.services.TripInstallmentAmountCalculator;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class TripInstallmentAmountCalculatorTest {

    private final TripInstallmentAmountCalculator calculator = new TripInstallmentAmountCalculator();

    @Test
    void calculate_customFirstInstallment_usesSameRoundedUpAmountForEveryRemainingInstallment() {
        List<BigDecimal> result = calculator.calculate(new BigDecimal("1000.00"), new BigDecimal("300.00"), 4);

        assertEquals(List.of(
                new BigDecimal("300.00"),
                new BigDecimal("234.00"),
                new BigDecimal("234.00"),
                new BigDecimal("234.00")
        ), result);
    }

    @Test
    void calculate_doesNotCompensateRemainderInLastInstallment() {
        List<BigDecimal> result = calculator.calculate(new BigDecimal("100.00"), new BigDecimal("10.00"), 4);

        assertEquals(List.of(
                new BigDecimal("10.00"),
                new BigDecimal("30.00"),
                new BigDecimal("30.00"),
                new BigDecimal("30.00")
        ), result);
    }

    @Test
    void calculate_singleInstallment_returnsFirstInstallment() {
        List<BigDecimal> result = calculator.calculate(new BigDecimal("999.99"), new BigDecimal("999.99"), 1);

        assertEquals(List.of(new BigDecimal("999.99")), result);
    }

    @Test
    void calculate_rejectsFirstInstallmentGreaterThanTotal() {
        IllegalArgumentException error = assertThrows(
                IllegalArgumentException.class,
                () -> calculator.calculate(new BigDecimal("100.00"), new BigDecimal("100.01"), 1)
        );

        assertEquals("La primera cuota no puede superar el monto total del viaje", error.getMessage());
    }

    @Test
    void calculate_rejectsFirstInstallmentEqualToTotalWhenThereAreRemainingInstallments() {
        IllegalArgumentException error = assertThrows(
                IllegalArgumentException.class,
                () -> calculator.calculate(new BigDecimal("100.00"), new BigDecimal("100.00"), 2)
        );

        assertEquals("La primera cuota debe ser menor al monto total cuando el viaje tiene más de una cuota", error.getMessage());
    }
}
