package com.agencia.pagos;

import com.agencia.pagos.services.TripService;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;
import java.lang.reflect.Method;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TripServiceSplitAmountTest {

    private final TripService tripService = new TripService(null, null, null);

    @Test
    void splitAmount_divisionExacta_reparteMontosIguales() {
        List<BigDecimal> result = invokeSplitAmount(new BigDecimal("120000.00"), 12);

        assertNotNull(result);
        assertEquals(12, result.size());
        assertEquals(new BigDecimal("10000.00"), result.get(0));
        assertEquals(new BigDecimal("10000.00"), result.get(11));
    }

    @Test
    void splitAmount_conResto_asignaCentavosALaUltimaCuota() {
        List<BigDecimal> result = invokeSplitAmount(new BigDecimal("100.00"), 3);

        assertNotNull(result);
        assertEquals(3, result.size());
        assertEquals(new BigDecimal("33.33"), result.get(0));
        assertEquals(new BigDecimal("33.33"), result.get(1));
        assertEquals(new BigDecimal("33.34"), result.get(2));
    }

    @Test
    void splitAmount_cuotaUnica_retornaTotalCompleto() {
        List<BigDecimal> result = invokeSplitAmount(new BigDecimal("999.99"), 1);

        assertNotNull(result);
        assertEquals(1, result.size());
        assertEquals(new BigDecimal("999.99"), result.get(0));
    }

    @Test
    void splitAmount_montoMenorQueCantidad_generaCerosYUltimaConResto() {
        List<BigDecimal> result = invokeSplitAmount(new BigDecimal("0.05"), 10);

        assertNotNull(result);
        assertEquals(10, result.size());
        assertEquals(new BigDecimal("0.00"), result.get(0));
        assertEquals(new BigDecimal("0.05"), result.get(9));
    }

    @Test
    void splitAmount_nuncaPierdeCentavos_laSumaCoincideConTotal() {
        BigDecimal total = new BigDecimal("12345.67");
        List<BigDecimal> result = invokeSplitAmount(total, 12);

        assertNotNull(result);
        assertEquals(12, result.size());

        BigDecimal sum = result.stream().reduce(BigDecimal.ZERO, BigDecimal::add);
        assertEquals(0, sum.compareTo(total));
        assertTrue(result.get(11).compareTo(result.get(0)) >= 0);
    }

    @SuppressWarnings("unchecked")
    private List<BigDecimal> invokeSplitAmount(BigDecimal total, int count) {
        try {
            Method method = TripService.class.getDeclaredMethod("splitAmount", BigDecimal.class, int.class);
            method.setAccessible(true);
            return (List<BigDecimal>) method.invoke(tripService, total, count);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }
}
