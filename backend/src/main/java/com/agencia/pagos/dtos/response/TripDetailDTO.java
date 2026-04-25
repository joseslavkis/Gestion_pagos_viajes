package com.agencia.pagos.dtos.response;

import com.agencia.pagos.entities.Currency;

import java.math.BigDecimal;
import java.time.LocalDate;

public record TripDetailDTO(
        Long id,
        String name,
        BigDecimal totalAmount,
        BigDecimal firstInstallmentAmount,
        Integer installmentsCount,
        Integer dueDay,
        Integer yellowWarningDays,
        BigDecimal fixedFineAmount,
        Boolean retroactiveActive,
        Currency currency,
        LocalDate firstDueDate,
        Integer assignedUsersCount,
        Integer assignedParticipantsCount
) {}
