package com.agencia.pagos.repositories;

import com.agencia.pagos.entities.user.User;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

public interface UserRepository extends JpaRepository<User, Long> {
    Optional<User> findByEmail(String email);

    boolean existsByDni(String dni);

    /** Recupera todos los usuarios cuyos IDs están en la colección en una sola consulta SQL. */
    List<User> findAllByIdIn(Collection<Long> ids);
}
