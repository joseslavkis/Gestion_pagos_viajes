package com.agencia.pagos.dtos.response;

import com.agencia.pagos.entities.InstallmentStatus;
import com.agencia.pagos.entities.ReceiptStatus;

import java.math.BigDecimal;
import java.time.LocalDate;

public record UserInstallmentDTO(
        Long tripId,
        Long installmentId,
        Integer installmentNumber,
        LocalDate dueDate,
        BigDecimal totalDue,
        InstallmentStatus installmentStatus,
        ReceiptStatus latestReceiptStatus,
        String latestReceiptObservation,
        Boolean userCompletedTrip
) {
}