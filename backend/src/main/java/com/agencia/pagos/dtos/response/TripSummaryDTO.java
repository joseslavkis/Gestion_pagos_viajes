package com.agencia.pagos.dtos.response;

import com.agencia.pagos.entities.Currency;

import java.math.BigDecimal;

public record TripSummaryDTO(
        Long id,
        String name,
        BigDecimal totalAmount,
        BigDecimal firstInstallmentAmount,
        Currency currency,
        Integer installmentsCount,
        Integer assignedUsersCount,
        Integer assignedParticipantsCount
) {}
