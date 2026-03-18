package com.agencia.pagos.dtos.request;

import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

public record UserUpdateDTO (
        @Size(min = 2, max = 100) String name,
        @Size(min = 2, max = 100) String lastname,
        @Size(min = 8) @Pattern(
            regexp = "^(?=.*[a-z])(?=.*[A-Z])(?=.*\\d)(?=.*[@$!%*?&]).{8,}$",
            message = "Password must have at least one uppercase, one lowercase, one digit, and one special character"
        ) String password
) {

}
