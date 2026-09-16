package com.agencia.pagos.trip.dto;

public record BulkAssignResultDTO(
        String status,
        String message,
        Integer assignedCount,
        Integer pendingCount
) {}
