package com.agencia.pagos.dtos.response;

import com.agencia.pagos.entities.InstallmentStatus;
import com.agencia.pagos.entities.InstallmentUiStatusCode;
import com.agencia.pagos.entities.ReceiptStatus;
import com.agencia.pagos.entities.Currency;

import java.math.BigDecimal;
import java.time.LocalDate;

public record UserInstallmentDTO(
        Long tripId,
        String tripName,
        Long studentId,
        String studentName,
        String studentDni,
        Long installmentId,
        Integer installmentNumber,
        LocalDate dueDate,
        BigDecimal totalDue,
        BigDecimal paidAmount,
        Integer yellowWarningDays,
        Currency tripCurrency,
        InstallmentStatus installmentStatus,
        ReceiptStatus latestReceiptStatus,
        InstallmentUiStatusCode uiStatusCode,
        String uiStatusLabel,
        String uiStatusTone,
        String latestReceiptObservation,
        Boolean userCompletedTrip
) {
}
