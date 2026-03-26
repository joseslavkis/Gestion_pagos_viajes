package com.agencia.pagos.dtos.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

public record ResetPasswordDTO(
        @NotBlank String token,
        @NotBlank
        @Size(min = 8)
        @Pattern(
                regexp = "^(?=.*[a-z])(?=.*[A-Z])(?=.*\\d)(?=.*[@$!%*?&]).{8,}$",
                message = "La contraseña debe incluir mayúscula, minúscula, número y símbolo"
        ) String newPassword
) {
}
