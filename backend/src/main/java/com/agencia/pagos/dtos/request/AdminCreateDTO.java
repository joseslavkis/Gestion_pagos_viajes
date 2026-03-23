package com.agencia.pagos.dtos.request;

import com.agencia.pagos.entities.Role;
import com.agencia.pagos.entities.user.User;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.util.function.Function;

public record AdminCreateDTO(
        @NotBlank @Email String email,
        @NotBlank @Size(min = 8) @Pattern(
                regexp = "^(?=.*[a-z])(?=.*[A-Z])(?=.*\\d)(?=.*[@$!%*?&]).{8,}$",
                message = "Password must have at least one uppercase, one lowercase, one digit, and one special character"
        ) String password,
        @NotBlank String name,
        @NotBlank String lastname,
        @NotBlank @Pattern(regexp = "^\\d{7,8}$", message = "DNI must have 7 or 8 digits") String dni,
        String phone
) {
    public User asUser(Function<String, String> encryptPassword) {
        User user = new User(name, encryptPassword.apply(password), email, lastname, Role.ADMIN);
        user.setDni(dni);
        user.setPhone(phone);
        return user;
    }
}
