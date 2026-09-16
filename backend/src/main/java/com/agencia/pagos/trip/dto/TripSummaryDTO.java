package com.agencia.pagos.trip.dto;

import com.agencia.pagos.shared.money.Currency;

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
