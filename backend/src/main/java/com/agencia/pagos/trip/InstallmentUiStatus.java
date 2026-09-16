package com.agencia.pagos.trip;

import com.agencia.pagos.trip.InstallmentUiStatusCode;

public record InstallmentUiStatus(
        InstallmentUiStatusCode code,
        String label,
        String tone
) {
}
