package com.agencia.pagos.repositories;

import com.agencia.pagos.entities.Installment;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;
import java.util.List;

public interface InstallmentRepository extends JpaRepository<Installment, Long> {

  List<Installment> findByUserId(Long userId);

    void deleteByTripId(Long tripId);

    @Query("SELECT i FROM Installment i JOIN FETCH i.trip WHERE i.id = :id")
    Optional<Installment> findByIdWithTrip(@Param("id") Long id);

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

    boolean existsByTripIdAndUserId(Long tripId, Long userId);

    @Query("SELECT COUNT(DISTINCT i.student.id) FROM Installment i WHERE i.trip.id = :tripId AND i.student IS NOT NULL")
    long countDistinctStudentsByTripId(@Param("tripId") Long tripId);
}
