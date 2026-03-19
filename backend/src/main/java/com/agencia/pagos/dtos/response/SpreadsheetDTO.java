package com.agencia.pagos.dtos.response;

import java.util.List;

public record SpreadsheetDTO(
        String tripName,
        Integer installmentsCount,
        Integer page,
        Long totalElements,
        List<SpreadsheetRowDTO> rows
) {
}

