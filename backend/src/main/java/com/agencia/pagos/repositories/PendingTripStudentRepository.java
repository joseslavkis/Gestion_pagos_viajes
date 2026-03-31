package com.agencia.pagos.repositories;

import com.agencia.pagos.entities.PendingTripStudent;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Collection;
import java.util.List;

public interface PendingTripStudentRepository extends JpaRepository<PendingTripStudent, Long> {

    boolean existsByStudentDni(String studentDni);

    @Query("""
        SELECT p
        FROM PendingTripStudent p
        JOIN FETCH p.trip
        WHERE p.studentDni = :studentDni
        ORDER BY p.trip.id
        """)
    List<PendingTripStudent> findByStudentDniWithTrip(@Param("studentDni") String studentDni);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("""
        SELECT p
        FROM PendingTripStudent p
        JOIN FETCH p.trip
        WHERE p.studentDni = :studentDni
        ORDER BY p.trip.id
        """)
    List<PendingTripStudent> findByStudentDniWithTripForUpdate(@Param("studentDni") String studentDni);

    @Query("""
        SELECT p
        FROM PendingTripStudent p
        WHERE p.trip.id = :tripId
          AND p.studentDni IN :studentDnis
        """)
    List<PendingTripStudent> findByTripIdAndStudentDniIn(
        @Param("tripId") Long tripId,
        @Param("studentDnis") Collection<String> studentDnis
    );

    List<PendingTripStudent> findByTripIdOrderByStudentDniAsc(Long tripId);

    List<PendingTripStudent> findByTripIdAndStudentDni(Long tripId, String studentDni);

    void deleteByTripId(Long tripId);

    void deleteByTripIdAndStudentDni(Long tripId, String studentDni);
}
