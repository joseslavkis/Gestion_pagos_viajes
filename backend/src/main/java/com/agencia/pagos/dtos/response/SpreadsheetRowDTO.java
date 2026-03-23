package com.agencia.pagos.dtos.response;

import java.util.List;

public record SpreadsheetRowDTO(
        Long userId,
        Long studentId,
        String name,
        String lastname,
        String phone,
        String email,
        String studentName,
        String studentDni,
        String schoolName,
        String courseName,
        Boolean userCompleted,
        List<SpreadsheetRowInstallmentDTO> installments
) {
}
