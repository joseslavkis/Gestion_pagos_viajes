package com.agencia.pagos.user.dto;

import jakarta.validation.constraints.NotNull;

public record TokenDTO(
        @NotNull String accessToken,
        String refreshToken
) {
}
