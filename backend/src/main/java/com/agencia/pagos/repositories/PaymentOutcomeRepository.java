package com.agencia.pagos.repositories;

import com.agencia.pagos.entities.PaymentOutcome;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface PaymentOutcomeRepository extends JpaRepository<PaymentOutcome, Long> {

    @Modifying
    @Query("""
        DELETE
        FROM PaymentOutcome o
        WHERE o.submission.trip.id = :tripId
        """)
    void deleteByTripId(@Param("tripId") Long tripId);
}
