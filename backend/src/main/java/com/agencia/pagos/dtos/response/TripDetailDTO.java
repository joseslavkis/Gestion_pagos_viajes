package com.agencia.pagos.dtos.response;

import java.math.BigDecimal;
import java.time.LocalDate;

public record TripDetailDTO(
        Long id,
        String name,
        BigDecimal totalAmount,
        Integer installmentsCount,
        Integer dueDay,
        Integer yellowWarningDays,
        BigDecimal fixedFineAmount,
        Boolean retroactiveActive,
        LocalDate firstDueDate,
        Integer assignedUsersCount
) {}
