package com.agencia.pagos;

import com.agencia.pagos.entities.InstallmentStatus;
import com.agencia.pagos.services.TripService;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.ZoneId;

import static org.junit.jupiter.api.Assertions.assertEquals;

class TripServiceComputeEffectiveStatusTest {

    private static final ZoneId BUSINESS_ZONE = ZoneId.of("America/Argentina/Buenos_Aires");

    private final TripService tripService = new TripService(null, null, null);

    @Test
    void cuotaTotalmenteCubierta_esGreenAunqueStoredStatusSeaYellow() {
        LocalDate today = LocalDate.now(BUSINESS_ZONE);

        InstallmentStatus result = invokeComputeEffectiveStatus(
                InstallmentStatus.YELLOW,
                today.plusDays(30),
                10,
                new BigDecimal("200.00"),
                new BigDecimal("200.00")
        );

        assertEquals(InstallmentStatus.GREEN, result);
    }

    @Test
    void retroactiveSinCubrir_noCambiaAunqueLaFechaSeaFutura() {
        LocalDate today = LocalDate.now(BUSINESS_ZONE);

        InstallmentStatus result = invokeComputeEffectiveStatus(
                InstallmentStatus.RETROACTIVE,
                today.plusDays(90),
                10,
                BigDecimal.ZERO,
                new BigDecimal("200.00")
        );

        assertEquals(InstallmentStatus.RETROACTIVE, result);
    }

    @Test
    void vencidaSinCubrir_esRed() {
        LocalDate today = LocalDate.now(BUSINESS_ZONE);

        InstallmentStatus result = invokeComputeEffectiveStatus(
                InstallmentStatus.YELLOW,
                today.minusDays(1),
                10,
                BigDecimal.ZERO,
                new BigDecimal("200.00")
        );

        assertEquals(InstallmentStatus.RED, result);
    }

    @Test
    void futuraDentroDeLaVentanaAmarilla_permaneceYellow() {
        LocalDate today = LocalDate.now(BUSINESS_ZONE);

        InstallmentStatus result = invokeComputeEffectiveStatus(
                InstallmentStatus.YELLOW,
                today.plusDays(5),
                10,
                new BigDecimal("50.00"),
                new BigDecimal("200.00")
        );

        assertEquals(InstallmentStatus.YELLOW, result);
    }

    @Test
    void futuraLejanaSinCubrir_permaneceYellow() {
        LocalDate today = LocalDate.now(BUSINESS_ZONE);

        InstallmentStatus result = invokeComputeEffectiveStatus(
                InstallmentStatus.YELLOW,
                today.plusDays(60),
                10,
                BigDecimal.ZERO,
                new BigDecimal("200.00")
        );

        assertEquals(InstallmentStatus.YELLOW, result);
    }

    private InstallmentStatus invokeComputeEffectiveStatus(
            InstallmentStatus storedStatus,
            LocalDate dueDate,
            int yellowWarningDays,
            BigDecimal paidAmount,
            BigDecimal totalDue
    ) {
        try {
            Method method = TripService.class.getDeclaredMethod(
                    "computeEffectiveStatus",
                    InstallmentStatus.class,
                    LocalDate.class,
                    int.class,
                    BigDecimal.class,
                    BigDecimal.class
            );
            method.setAccessible(true);
            return (InstallmentStatus) method.invoke(
                    tripService,
                    storedStatus,
                    dueDate,
                    yellowWarningDays,
                    paidAmount,
                    totalDue
            );
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }
}
