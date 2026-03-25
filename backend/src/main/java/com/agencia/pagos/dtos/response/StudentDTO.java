package com.agencia.pagos.dtos.response;

public record StudentDTO(
        Long id,
        String name,
        String dni,
        String schoolName,
        String courseName
) {
}
