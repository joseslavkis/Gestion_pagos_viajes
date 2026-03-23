package com.agencia.pagos.dtos.request;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.util.List;
import java.util.function.Function;

import com.agencia.pagos.entities.Role;
import com.agencia.pagos.entities.user.User;
import com.agencia.pagos.entities.user.UserCredentials;

public record UserCreateDTO(
    @NotBlank @Email String email,
    @NotBlank @Size(min = 8) @Pattern(
            regexp = "^(?=.*[a-z])(?=.*[A-Z])(?=.*\\d)(?=.*[@$!%*?&]).{8,}$",
            message = "Password must have at least one uppercase, one lowercase, one digit, and one special character"
        ) String password,
    @NotBlank String name,
    @NotBlank String lastname,
    @NotBlank @Pattern(regexp = "^\\d{7,8}$", message = "DNI must have 7 or 8 digits") String dni,
    @NotBlank String phone,
    @NotEmpty @Size(max = 10) List<@Valid StudentCreateDTO> students
) implements UserCredentials {
    public User asUser(Function<String, String> encryptPassword) {
        return asUser(encryptPassword, Role.USER);
    }

    public User asUser(Function<String, String> encryptPassword, Role role) {
        User u = new User(name, encryptPassword.apply(password), email, lastname, role);
        u.setDni(dni);
        u.setPhone(phone);
        return u;
    }
}
