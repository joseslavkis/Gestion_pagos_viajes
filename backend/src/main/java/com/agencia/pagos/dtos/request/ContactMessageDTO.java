package com.agencia.pagos.dtos.request;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;

public record ContactMessageDTO(
        @NotBlank String name,
        @NotBlank @Email String email,
        @NotBlank String message
) {}

