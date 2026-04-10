package com.agencia.pagos.dtos.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

public record StudentCreateDTO(
        @NotBlank @Size(min = 2, max = 100) String name,
        @NotBlank @Size(min = 2, max = 100) String lastname,
        @NotBlank @Pattern(
                regexp = "^(?=.*\\d)[\\d.\\-\\s]{7,14}$",
                message = "Student DNI must have 7 or 8 digits (dots, dashes, and spaces are allowed)"
        ) String dni
) {
}
