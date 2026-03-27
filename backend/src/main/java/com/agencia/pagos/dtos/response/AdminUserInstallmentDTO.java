package com.agencia.pagos.dtos.response;

import com.agencia.pagos.entities.Currency;
import com.agencia.pagos.entities.InstallmentStatus;
import com.agencia.pagos.entities.InstallmentUiStatusCode;
import com.agencia.pagos.entities.ReceiptStatus;

import java.math.BigDecimal;
import java.time.LocalDate;

public record AdminUserInstallmentDTO(
        Long tripId,
        String tripName,
        Currency tripCurrency,
        Long studentId,
        String studentName,
        String studentDni,
        String schoolName,
        String courseName,
        Long installmentId,
        Integer installmentNumber,
        LocalDate dueDate,
        BigDecimal totalDue,
        BigDecimal paidAmount,
        InstallmentStatus installmentStatus,
        ReceiptStatus latestReceiptStatus,
        InstallmentUiStatusCode uiStatusCode,
        String uiStatusLabel,
        String uiStatusTone,
        String latestReceiptObservation
) {
}
