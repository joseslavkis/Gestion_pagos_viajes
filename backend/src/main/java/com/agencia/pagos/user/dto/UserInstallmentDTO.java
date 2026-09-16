package com.agencia.pagos.user.dto;

import com.agencia.pagos.trip.InstallmentStatus;
import com.agencia.pagos.trip.InstallmentUiStatusCode;
import com.agencia.pagos.payment.ReceiptStatus;
import com.agencia.pagos.shared.money.Currency;

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
