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
        if (storedStatus == InstallmentStatus.RETROACTIVE) {
            return InstallmentStatus.RETROACTIVE;
        }
        // Cuota pagada (status GREEN persistido por aprobacion de comprobante)
        // no debe recalcularse por fecha - ya esta pagada
        if (storedStatus == InstallmentStatus.GREEN) {
            return InstallmentStatus.GREEN;
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
        return InstallmentStatus.GREEN;
    }
}
