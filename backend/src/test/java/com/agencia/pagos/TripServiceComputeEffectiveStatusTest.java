package com.agencia.pagos;

import com.agencia.pagos.entities.InstallmentStatus;
import com.agencia.pagos.services.TripService;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.time.LocalDate;
import java.time.ZoneId;

import static org.junit.jupiter.api.Assertions.assertEquals;

class TripServiceComputeEffectiveStatusTest {

    private static final ZoneId BUSINESS_ZONE = ZoneId.of("America/Argentina/Buenos_Aires");
    private final TripService tripService = new TripService(null, null, null);

    @Test
    void futuroLejano_esYellow() {
        LocalDate today = LocalDate.now(BUSINESS_ZONE);

        InstallmentStatus result = invokeComputeEffectiveStatus(
                InstallmentStatus.YELLOW,
                today.plusDays(60),
                10
        );

        assertEquals(InstallmentStatus.YELLOW, result);
    }

    @Test
    void dentroVentanaAmarilla_esYellow() {
        LocalDate today = LocalDate.now(BUSINESS_ZONE);

        InstallmentStatus result = invokeComputeEffectiveStatus(
                InstallmentStatus.YELLOW,
                today.plusDays(5),
                10
        );

        assertEquals(InstallmentStatus.YELLOW, result);
    }

    @Test
    void exactamenteBordeVentana_esYellow() {
        LocalDate today = LocalDate.now(BUSINESS_ZONE);

        InstallmentStatus result = invokeComputeEffectiveStatus(
                InstallmentStatus.YELLOW,
                today.plusDays(10),
                10
        );

        assertEquals(InstallmentStatus.YELLOW, result);
    }

    @Test
    void vencidoAyer_esRed() {
        LocalDate today = LocalDate.now(BUSINESS_ZONE);

        InstallmentStatus result = invokeComputeEffectiveStatus(
                InstallmentStatus.YELLOW,
                today.minusDays(1),
                10
        );

        assertEquals(InstallmentStatus.RED, result);
    }

    @Test
    void retroactive_noCambia_sinImportarFecha() {
        LocalDate today = LocalDate.now(BUSINESS_ZONE);

        InstallmentStatus result = invokeComputeEffectiveStatus(
                InstallmentStatus.RETROACTIVE,
                today.plusDays(90),
                10
        );

        assertEquals(InstallmentStatus.RETROACTIVE, result);
    }

    @Test
    void yellowWarningDaysCero_comportamientoLimite() {
        LocalDate today = LocalDate.now(BUSINESS_ZONE);

        InstallmentStatus tomorrowResult = invokeComputeEffectiveStatus(
                InstallmentStatus.YELLOW,
                today.plusDays(1),
                0
        );
        InstallmentStatus todayResult = invokeComputeEffectiveStatus(
                InstallmentStatus.YELLOW,
                today,
                0
        );
        InstallmentStatus yesterdayResult = invokeComputeEffectiveStatus(
                InstallmentStatus.YELLOW,
                today.minusDays(1),
                0
        );

        assertEquals(InstallmentStatus.YELLOW, tomorrowResult);
        assertEquals(InstallmentStatus.YELLOW, todayResult);
        assertEquals(InstallmentStatus.RED, yesterdayResult);
    }

    private InstallmentStatus invokeComputeEffectiveStatus(
            InstallmentStatus storedStatus,
            LocalDate dueDate,
            int yellowWarningDays
    ) {
        try {
            Method method = TripService.class.getDeclaredMethod(
                    "computeEffectiveStatus",
                    InstallmentStatus.class,
                    LocalDate.class,
                    int.class
            );
            method.setAccessible(true);
            return (InstallmentStatus) method.invoke(tripService, storedStatus, dueDate, yellowWarningDays);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }
}
