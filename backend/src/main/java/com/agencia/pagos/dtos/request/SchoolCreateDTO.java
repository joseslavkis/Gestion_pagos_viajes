package com.agencia.pagos.dtos.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record SchoolCreateDTO(
        @NotBlank @Size(min = 2, max = 150) String name
) {
}
