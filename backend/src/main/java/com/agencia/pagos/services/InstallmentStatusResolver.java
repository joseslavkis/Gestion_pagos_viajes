package com.agencia.pagos.services;

import com.agencia.pagos.entities.InstallmentStatus;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.time.ZoneId;

@Component
public class InstallmentStatusResolver {

    private static final ZoneId BUSINESS_ZONE =
            ZoneId.of("America/Argentina/Buenos_Aires");

    public InstallmentStatus computeEffective(
            InstallmentStatus storedStatus,
            LocalDate dueDate,
            int yellowWarningDays
    ) {
        // Cuota realmente pagada: aprobacion de comprobante persiste GREEN.
        // No recalcular por fecha porque ya esta saldada.
        if (storedStatus == InstallmentStatus.GREEN) {
            return InstallmentStatus.GREEN;
        }

        if (storedStatus == InstallmentStatus.RETROACTIVE) {
            return InstallmentStatus.RETROACTIVE;
        }

        LocalDate today = LocalDate.now(BUSINESS_ZONE);

        if (dueDate.isBefore(today)) {
            return InstallmentStatus.RED;
        }

        long daysUntilDue = dueDate.toEpochDay() - today.toEpochDay();
        int safe = Math.max(0, yellowWarningDays);

        if (daysUntilDue <= safe) {
            return InstallmentStatus.YELLOW;
        }

        // Cuota futura lejana: pendiente sin urgencia.
        // Se mantiene en YELLOW para distinguirla de GREEN (pagada).
        return InstallmentStatus.YELLOW;
    }
}
