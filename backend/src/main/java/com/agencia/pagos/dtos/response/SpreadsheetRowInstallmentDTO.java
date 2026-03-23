package com.agencia.pagos.dtos.response;

import com.agencia.pagos.entities.InstallmentStatus;
import com.agencia.pagos.entities.InstallmentUiStatusCode;

import java.math.BigDecimal;
import java.time.LocalDate;

public record SpreadsheetRowInstallmentDTO(
        Long id,
        Integer installmentNumber,
        LocalDate dueDate,
        BigDecimal capitalAmount,
        BigDecimal retroactiveAmount,
        BigDecimal fineAmount,
        BigDecimal totalDue,
        BigDecimal paidAmount,
        InstallmentStatus status,
        InstallmentUiStatusCode uiStatusCode,
        String uiStatusLabel,
        String uiStatusTone
) {
}
