package com.agencia.pagos.repositories;

import com.agencia.pagos.entities.PaymentReceipt;
import com.agencia.pagos.entities.ReceiptStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;

public interface PaymentReceiptRepository extends JpaRepository<PaymentReceipt, Long> {

    List<PaymentReceipt> findByInstallmentId(Long installmentId);

    void deleteByInstallmentTripId(Long tripId);

    List<PaymentReceipt> findByInstallmentIdAndStatus(Long installmentId, ReceiptStatus status);

    List<PaymentReceipt> findByInstallmentUserId(Long userId);

    @Query("""
        SELECT p
        FROM PaymentReceipt p
        JOIN FETCH p.installment i
        LEFT JOIN FETCH i.student
        WHERE i.id IN :installmentIds
        ORDER BY p.id DESC
        """)
    List<PaymentReceipt> findByInstallmentIdIn(@Param("installmentIds") List<Long> installmentIds);

    @Query("""
        SELECT p
        FROM PaymentReceipt p
        JOIN FETCH p.installment i
        JOIN FETCH i.trip
        JOIN FETCH i.user
        LEFT JOIN FETCH i.student
        LEFT JOIN FETCH p.bankAccount
        WHERE p.status = :status
        ORDER BY p.id DESC
        """)
    List<PaymentReceipt> findByStatusWithContext(@Param("status") ReceiptStatus status);

    boolean existsByInstallmentIdAndStatus(Long installmentId, ReceiptStatus status);
}
