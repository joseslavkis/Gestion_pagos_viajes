package com.agencia.pagos.dtos.request;

import jakarta.validation.constraints.*;

import java.util.function.Function;

import com.agencia.pagos.entities.Role;
import com.agencia.pagos.entities.user.User;
import com.agencia.pagos.entities.user.UserCredentials;

public record UserCreateDTO(
        @NotNull @Email String email,
        @NotNull @Size(min = 8) String password,
        @NotNull String name,
        @NotNull String lastname,
        @NotNull String dni,
        String phone,
        String studentName,
        String schoolName,
        String courseName
) implements UserCredentials {
    public User asUser(Function<String, String> encryptPassword) {
        return asUser(encryptPassword, Role.USER);
    }

    public User asUser(Function<String, String> encryptPassword, Role role) {
        User u = new User(name, encryptPassword.apply(password), email, lastname, role);
        u.setDni(dni);
        u.setPhone(phone);
        u.setStudentName(studentName);
        u.setSchoolName(schoolName);
        u.setCourseName(courseName);
        return u;
    }
}