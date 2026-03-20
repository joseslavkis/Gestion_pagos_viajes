package com.agencia.pagos.repositories;

import com.agencia.pagos.entities.Installment;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;

public interface InstallmentRepository extends JpaRepository<Installment, Long> {

    @Query(value = """
        SELECT
        u.id AS user_id,
        u.name,
        u.lastname,
        u.phone,
        u.email,
        u.student_name,
        u.school_name,
        u.course_name
        FROM trip_user tu
        JOIN users u ON u.id = tu.user_id
        WHERE tu.trip_id = :tripId
          AND u.active = true
          AND (
            :search IS NULL
            OR TRIM(:search) = ''
            OR LOWER(u.name) LIKE LOWER('%' || :search || '%')
            OR LOWER(u.lastname) LIKE LOWER('%' || :search || '%')
            OR LOWER(u.email) LIKE LOWER('%' || :search || '%')
          )
        ORDER BY
        CASE WHEN :order = 'asc' AND :sortBy = 'name' THEN u.name END ASC,
        CASE WHEN :order = 'asc' AND :sortBy = 'email' THEN u.email END ASC,
        CASE WHEN :order = 'asc' AND (:sortBy = 'lastname' OR :sortBy IS NULL OR TRIM(:sortBy) = '') THEN u.lastname END ASC,
        CASE WHEN :order = 'desc' AND :sortBy = 'name' THEN u.name END DESC,
        CASE WHEN :order = 'desc' AND :sortBy = 'email' THEN u.email END DESC,
        CASE WHEN :order = 'desc' AND (:sortBy = 'lastname' OR :sortBy IS NULL OR TRIM(:sortBy) = '') THEN u.lastname END DESC,
        u.id ASC
        LIMIT :size OFFSET :offset
        """, nativeQuery = true)
    List<Object[]> findPagedUsersForSpreadsheet(
        @Param("tripId") Long tripId,
        @Param("search") String search,
        @Param("sortBy") String sortBy,
        @Param("order") String order,
        @Param("size") int size,
        @Param("offset") int offset
    );

    @Query(value = """
        SELECT COUNT(*)
        FROM trip_user tu
        JOIN users u ON u.id = tu.user_id
        WHERE tu.trip_id = :tripId
          AND u.active = true
          AND (
            :search IS NULL
            OR TRIM(:search) = ''
            OR LOWER(u.name) LIKE LOWER('%' || :search || '%')
            OR LOWER(u.lastname) LIKE LOWER('%' || :search || '%')
            OR LOWER(u.email) LIKE LOWER('%' || :search || '%')
          )
        """, nativeQuery = true)
    long countFilteredUsersForSpreadsheet(
        @Param("tripId") Long tripId,
        @Param("search") String search
    );

    @Query("SELECT i FROM Installment i JOIN FETCH i.user WHERE i.trip.id = :tripId AND i.user.id IN :userIds")
    List<Installment> findInstallmentsByTripAndUserIds(
        @Param("tripId") Long tripId,
        @Param("userIds") List<Long> userIds
    );

    /**
     * Recupera todas las cuotas de un viaje con su {@code user} ya inicializado via JOIN FETCH,
     * evitando el problema N+1 al generar reportes/plantillas pesadas.
     */
    @Query("SELECT i FROM Installment i JOIN FETCH i.user WHERE i.trip.id = :tripId")
    List<Installment> findByTripIdWithUsers(@Param("tripId") Long tripId);
}

