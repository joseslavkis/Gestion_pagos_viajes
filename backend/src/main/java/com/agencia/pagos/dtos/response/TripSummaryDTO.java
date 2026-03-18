package com.agencia.pagos.dtos.response;

import java.math.BigDecimal;

public record TripSummaryDTO(
        Long id,
        String name,
        BigDecimal totalAmount,
        Integer installmentsCount,
        Integer assignedUsersCount
) {}
