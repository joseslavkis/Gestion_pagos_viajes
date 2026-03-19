package com.agencia.pagos.dtos.response;

import com.agencia.pagos.entities.InstallmentStatus;

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
        InstallmentStatus status
) {
}

