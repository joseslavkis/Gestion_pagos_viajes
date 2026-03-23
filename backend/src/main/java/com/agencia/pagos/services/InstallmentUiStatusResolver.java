package com.agencia.pagos.services;

import com.agencia.pagos.entities.InstallmentStatus;
import com.agencia.pagos.entities.InstallmentUiStatusCode;
import com.agencia.pagos.entities.ReceiptStatus;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.time.ZoneId;
import java.math.BigDecimal;

@Component
public class InstallmentUiStatusResolver {

    private static final ZoneId BUSINESS_ZONE =
            ZoneId.of("America/Argentina/Buenos_Aires");

    public InstallmentUiStatus resolve(
            InstallmentStatus effectiveInstallmentStatus,
            ReceiptStatus latestReceiptStatus,
            LocalDate dueDate,
            int yellowWarningDays,
            BigDecimal paidAmount,
            BigDecimal totalDue
    ) {
        if (latestReceiptStatus == ReceiptStatus.REJECTED) {
            return build(InstallmentUiStatusCode.RECEIPT_REJECTED);
        }

        if (latestReceiptStatus == ReceiptStatus.PENDING) {
            return build(InstallmentUiStatusCode.UNDER_REVIEW);
        }

        if (effectiveInstallmentStatus == InstallmentStatus.RETROACTIVE) {
            return build(InstallmentUiStatusCode.RETROACTIVE_DEBT);
        }

        if (effectiveInstallmentStatus == InstallmentStatus.RED) {
            return build(InstallmentUiStatusCode.OVERDUE);
        }

        if (effectiveInstallmentStatus == InstallmentStatus.GREEN) {
            boolean fullyCovered = paidAmount != null
                    && totalDue != null
                    && paidAmount.compareTo(totalDue) >= 0;
            if (latestReceiptStatus == ReceiptStatus.APPROVED && fullyCovered) {
                return build(InstallmentUiStatusCode.PAID);
            }
            return build(InstallmentUiStatusCode.UP_TO_DATE);
        }

        if (isDueSoon(dueDate, yellowWarningDays)) {
            return build(InstallmentUiStatusCode.DUE_SOON);
        }

        return build(InstallmentUiStatusCode.UP_TO_DATE);
    }

    private boolean isDueSoon(LocalDate dueDate, int yellowWarningDays) {
        if (dueDate == null) {
            return false;
        }
        LocalDate today = LocalDate.now(BUSINESS_ZONE);
        long daysUntilDue = dueDate.toEpochDay() - today.toEpochDay();
        return daysUntilDue <= Math.max(0, yellowWarningDays);
    }

    private InstallmentUiStatus build(InstallmentUiStatusCode code) {
        return switch (code) {
            case PAID -> new InstallmentUiStatus(code, "Pagada", "green");
            case UP_TO_DATE -> new InstallmentUiStatus(code, "Al día", "green");
            case UNDER_REVIEW -> new InstallmentUiStatus(code, "En revisión", "yellow");
            case DUE_SOON -> new InstallmentUiStatus(code, "Vence pronto", "yellow");
            case OVERDUE -> new InstallmentUiStatus(code, "Vencida", "red");
            case RECEIPT_REJECTED -> new InstallmentUiStatus(code, "Comprobante rechazado", "red");
            case RETROACTIVE_DEBT -> new InstallmentUiStatus(code, "Deuda retroactiva", "red");
        };
    }
}
