package com.agencia.pagos.dtos.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

public record StudentCreateDTO(
        @NotBlank @Size(min = 2, max = 100) String name,
        @NotBlank @Pattern(regexp = "^\\d{7,8}$", message = "Student DNI must have 7 or 8 digits") String dni,
        String schoolName,
        String courseName
) {
}
