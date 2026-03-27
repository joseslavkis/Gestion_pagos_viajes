package com.agencia.pagos.repositories;

import com.agencia.pagos.entities.user.User;
import com.agencia.pagos.entities.Role;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

public interface UserRepository extends JpaRepository<User, Long> {
    Optional<User> findByEmail(String email);

    boolean existsByDni(String dni);

    /** Recupera todos los usuarios cuyos IDs están en la colección en una sola consulta SQL. */
    List<User> findAllByIdIn(Collection<Long> ids);

    @Query("""
        SELECT u
        FROM User u
        WHERE u.role = :role
          AND (
            LOWER(u.name) LIKE LOWER(CONCAT('%', :query, '%'))
            OR LOWER(u.lastname) LIKE LOWER(CONCAT('%', :query, '%'))
            OR LOWER(u.email) LIKE LOWER(CONCAT('%', :query, '%'))
            OR COALESCE(u.dni, '') LIKE CONCAT('%', :query, '%')
          )
        ORDER BY LOWER(u.lastname), LOWER(u.name), LOWER(u.email)
        """)
    List<User> searchByRoleAndQuery(
        @Param("role") Role role,
        @Param("query") String query,
        Pageable pageable
    );
}
