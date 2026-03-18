package com.agencia.pagos.dtos.request;

import jakarta.validation.constraints.Size;

public record UserUpdateDTO (
        @Size(min = 2, max = 100) String name,
        @Size(min = 2, max = 100) String lastname,
        @Size(min = 8) String password
) {

}
