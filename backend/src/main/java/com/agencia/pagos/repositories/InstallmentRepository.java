package com.agencia.pagos.repositories;

import com.agencia.pagos.entities.Installment;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;
import java.util.List;

public interface InstallmentRepository extends JpaRepository<Installment, Long> {

  List<Installment> findByUserId(Long userId);

    void deleteByTripId(Long tripId);

    @Query("SELECT i FROM Installment i JOIN FETCH i.trip WHERE i.id = :id")
    Optional<Installment> findByIdWithTrip(@Param("id") Long id);

    @Query("SELECT i FROM Installment i JOIN FETCH i.trip JOIN FETCH i.user LEFT JOIN FETCH i.student WHERE i.id = :id")
    Optional<Installment> findByIdWithTripUserAndStudent(@Param("id") Long id);

    @Query("SELECT i FROM Installment i JOIN FETCH i.trip LEFT JOIN FETCH i.student WHERE i.user.id = :userId")
    List<Installment> findByUserIdWithTrip(@Param("userId") Long userId);

    @Query("""
        SELECT i
        FROM Installment i
        WHERE i.trip.id = :tripId
          AND i.user.id = :userId
          AND (
            (:studentId IS NULL AND i.student IS NULL)
            OR i.student.id = :studentId
          )
        """)
    List<Installment> findByTripIdAndUserIdAndStudentId(
        @Param("tripId") Long tripId,
        @Param("userId") Long userId,
        @Param("studentId") Long studentId
    );

    /**
     * Atomic pessimistic-write lock of the installment scope used by payment
     * registration/review/void. Intentionally avoids {@code JOIN FETCH} so Hibernate issues a
     * single {@code SELECT ... FOR UPDATE} instead of an unlocked select followed by
     * follow-on locks (HHH000444). Associations are loaded on demand by the surrounding
     * {@code @Transactional} service.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("""
        SELECT i
        FROM Installment i
        WHERE i.trip.id = :tripId
          AND i.user.id = :userId
          AND (
            (:studentId IS NULL AND i.student IS NULL)
            OR i.student.id = :studentId
          )
        ORDER BY i.installmentNumber ASC
        """)
    List<Installment> findByTripIdAndUserIdAndStudentIdForUpdate(
            @Param("tripId") Long tripId,
            @Param("userId") Long userId,
            @Param("studentId") Long studentId
    );

    /**
     * Recupera todas las cuotas de un viaje con su {@code user} ya inicializado via JOIN FETCH,
     * evitando el problema N+1 al generar reportes/plantillas pesadas.
     */
    @Query("SELECT i FROM Installment i JOIN FETCH i.user LEFT JOIN FETCH i.student JOIN FETCH i.trip WHERE i.trip.id = :tripId")
    List<Installment> findByTripIdWithUsers(@Param("tripId") Long tripId);

    @Query("SELECT i FROM Installment i JOIN FETCH i.user LEFT JOIN FETCH i.student JOIN FETCH i.trip")
    List<Installment> findAllWithUserAndTrip();

    boolean existsByStudentId(Long studentId);

    @Query("SELECT DISTINCT i.student.id FROM Installment i WHERE i.trip.id = :tripId AND i.student IS NOT NULL")
    List<Long> findAssignedStudentIdsByTripId(@Param("tripId") Long tripId);

    @Query("""
        SELECT i
        FROM Installment i
        JOIN FETCH i.user
        JOIN FETCH i.trip
        LEFT JOIN FETCH i.student
        WHERE i.trip.id = :tripId
          AND i.student IS NOT NULL
          AND i.student.dni = :studentDni
        ORDER BY i.installmentNumber ASC
        """)
    List<Installment> findByTripIdAndStudentDni(@Param("tripId") Long tripId, @Param("studentDni") String studentDni);

    /**
     * Atomic pessimistic-write lock of the installment scope used by administrative flows that
     * decide and act on the entire installment scope (trip + student DNI), such as student
     * unassignment. Acquires a {@code SELECT ... FOR UPDATE} so concurrent payment
     * registration/review paths on the same installments cannot interleave between the activity
     * check and the deletes. Intentionally avoids {@code JOIN FETCH} so Hibernate issues a
     * single atomic lock query rather than an unlocked select with follow-on locks (HHH000444);
     * associations are loaded on demand by the surrounding {@code @Transactional} service.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("""
        SELECT i
        FROM Installment i
        WHERE i.trip.id = :tripId
          AND i.student IS NOT NULL
          AND i.student.dni = :studentDni
        ORDER BY i.installmentNumber ASC
        """)
    List<Installment> findByTripIdAndStudentDniForUpdate(
            @Param("tripId") Long tripId,
            @Param("studentDni") String studentDni
    );

    boolean existsByTripIdAndUserId(Long tripId, Long userId);

    @Query("SELECT COUNT(DISTINCT i.student.id) FROM Installment i WHERE i.trip.id = :tripId AND i.student IS NOT NULL")
    long countDistinctStudentsByTripId(@Param("tripId") Long tripId);
}
