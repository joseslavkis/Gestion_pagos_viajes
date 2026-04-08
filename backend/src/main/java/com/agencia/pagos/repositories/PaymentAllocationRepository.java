package com.agencia.pagos.repositories;

import com.agencia.pagos.entities.PaymentAllocation;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;

public interface PaymentAllocationRepository extends JpaRepository<PaymentAllocation, Long> {

    @Modifying
    @Query("""
        DELETE
        FROM PaymentAllocation a
        WHERE a.outcome.submission.trip.id = :tripId
        """)
    void deleteByTripId(@Param("tripId") Long tripId);

    @Query("""
        SELECT a
        FROM PaymentAllocation a
        JOIN FETCH a.installment i
        JOIN FETCH a.outcome o
        JOIN FETCH o.submission s
        LEFT JOIN FETCH s.bankAccount
        WHERE i.id = :installmentId
        ORDER BY s.reportedPaymentDate DESC, a.id DESC
        """)
    List<PaymentAllocation> findByInstallmentIdWithContext(@Param("installmentId") Long installmentId);
}
