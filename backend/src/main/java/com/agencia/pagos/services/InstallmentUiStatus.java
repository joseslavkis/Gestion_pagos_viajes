package com.agencia.pagos.services;

import com.agencia.pagos.entities.InstallmentUiStatusCode;

public record InstallmentUiStatus(
        InstallmentUiStatusCode code,
        String label,
        String tone
) {
}
