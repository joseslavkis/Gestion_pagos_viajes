package com.agencia.pagos.user.dto;

import com.agencia.pagos.shared.money.Currency;
import com.agencia.pagos.trip.InstallmentStatus;
import com.agencia.pagos.trip.InstallmentUiStatusCode;
import com.agencia.pagos.payment.ReceiptStatus;

import java.math.BigDecimal;
import java.time.LocalDate;

public record AdminUserInstallmentDTO(
        Long tripId,
        String tripName,
        Currency tripCurrency,
        Long studentId,
        String studentName,
        String studentDni,
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
