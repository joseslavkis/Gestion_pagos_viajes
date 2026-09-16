package com.agencia.pagos.trip;

import com.agencia.pagos.trip.PendingTripStudent;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Collection;
import java.util.List;

public interface PendingTripStudentRepository extends JpaRepository<PendingTripStudent, Long> {

    boolean existsByStudentDni(String studentDni);

    /**
     * Serializes every transaction that creates or claims a pending assignment for one DNI.
     * The lock is transaction-scoped and requires the PostgreSQL database used by the application.
     */
    @Query(value = "SELECT pg_advisory_xact_lock(hashtextextended(CAST(:studentDni AS text), 0))", nativeQuery = true)
    void lockStudentDni(@Param("studentDni") String studentDni);

    @Query("""
        SELECT p
        FROM PendingTripStudent p
        JOIN FETCH p.trip
        WHERE p.studentDni = :studentDni
        ORDER BY p.trip.id
        """)
    List<PendingTripStudent> findByStudentDniWithTrip(@Param("studentDni") String studentDni);

    /**
     * Scoped pessimistic-locking counterpart of {@link #findByStudentDniWithTrip(String)} restricted
     * to a known set of trip IDs. This is the only pending-row lock that is safe to acquire once
     * every trip in the provided set has already been locked by the calling transaction — locking
     * a pending row whose {@code trip_id} is not yet locked by us would invert the Trip →
     * PendingTripStudent ordering and reintroduce the deadlock with {@code unassignStudentByDni}.
     *
     * <p>Used by signup/materialization and by the {@code lockCandidateTripsForStudentDni}
     * helper to safely lock exactly the rows whose Trip lock is already held.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("""
        SELECT p
        FROM PendingTripStudent p
        JOIN FETCH p.trip
        WHERE p.studentDni = :studentDni
          AND p.trip.id IN :tripIds
        ORDER BY p.trip.id
        """)
    List<PendingTripStudent> findByStudentDniAndTripIdInWithTripForUpdate(
            @Param("studentDni") String studentDni,
            @Param("tripIds") Collection<Long> tripIds
    );

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
