package com.agencia.pagos.repositories;

import com.agencia.pagos.entities.Trip;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface TripRepository extends JpaRepository<Trip, Long> {

    @Query("SELECT t FROM Trip t LEFT JOIN FETCH t.assignedUsers WHERE t.id = :id")
    Optional<Trip> findByIdWithUsers(@Param("id") Long id);

    @Query("SELECT t FROM Trip t LEFT JOIN FETCH t.assignedUsers")
    List<Trip> findAllWithUsers();

    /**
     * Atomic pessimistic-write lock of a Trip row. Intentionally avoids {@code JOIN FETCH} so
     * Hibernate issues a single {@code SELECT ... FOR UPDATE} on the Trip row only, with no
     * follow-on locks on the joined {@code assignedUsers} association (which would otherwise
     * trigger HHH000444 and could deadlock against other transactions holding those users).
     * The {@code assignedUsers} collection is lazy-loaded on demand by the surrounding
     * {@code @Transactional} service.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT t FROM Trip t WHERE t.id = :id")
    Optional<Trip> findByIdForUpdate(@Param("id") Long id);
}
