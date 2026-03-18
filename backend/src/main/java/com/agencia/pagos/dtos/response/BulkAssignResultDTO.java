package com.agencia.pagos.dtos.response;

public record BulkAssignResultDTO(
        String status,
        String message,
        Integer assignedCount
) {}
