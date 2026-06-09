package com.agencia.pagos.dtos.response;

import java.util.List;

public record SpreadsheetReceiptPageDTO(
        String tripName,
        int page,
        int size,
        long totalElements,
        int totalPages,
        List<SpreadsheetReceiptRowDTO> content
) {
}
