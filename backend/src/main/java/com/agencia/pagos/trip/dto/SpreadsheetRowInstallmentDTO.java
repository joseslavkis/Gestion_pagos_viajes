package com.agencia.pagos.trip.dto;

import com.agencia.pagos.trip.InstallmentStatus;
import com.agencia.pagos.trip.InstallmentUiStatusCode;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.math.BigDecimal;
import java.time.LocalDate;

public record SpreadsheetRowInstallmentDTO(
        Long id,
        Integer installmentNumber,
        LocalDate dueDate,
        BigDecimal capitalAmount,
        BigDecimal retroactiveAmount,
        BigDecimal totalDue,
        BigDecimal paidAmount,
        InstallmentStatus status,
        InstallmentUiStatusCode uiStatusCode,
        String uiStatusLabel,
        String uiStatusTone
) {
        // Rollout compatibility shim (temporary):
        // legacy clients expect this property on the response even though the
        // fine logic has been retired. Exposed only for transport compatibility;
        // it must never be used by the new business code. The persisted
        // `totalDue` remains the capital amount. Remove once all clients are
        // upgraded.
        @JsonProperty("fineAmount")
        public BigDecimal legacyFineAmount() {
                return BigDecimal.ZERO;
        }
}
