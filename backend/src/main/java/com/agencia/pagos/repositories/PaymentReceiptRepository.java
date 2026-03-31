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

    void deleteByInstallmentIdIn(List<Long> installmentIds);

    List<PaymentReceipt> findByInstallmentIdAndStatus(Long installmentId, ReceiptStatus status);

    List<PaymentReceipt> findByInstallmentUserId(Long userId);

    @Query("""
        SELECT p
        FROM PaymentReceipt p
        JOIN FETCH p.installment i
        JOIN FETCH i.trip
        JOIN FETCH i.user
        LEFT JOIN FETCH i.student
        LEFT JOIN FETCH p.bankAccount
        LEFT JOIN FETCH p.batch
        WHERE i.user.id = :userId
        ORDER BY p.reportedPaymentDate DESC, p.id DESC
        """)
    List<PaymentReceipt> findByInstallmentUserIdWithContext(@Param("userId") Long userId);

    @Query("""
        SELECT p
        FROM PaymentReceipt p
        JOIN FETCH p.installment i
        LEFT JOIN FETCH i.student
        LEFT JOIN FETCH p.batch
        LEFT JOIN FETCH p.bankAccount
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
        LEFT JOIN FETCH p.batch
        WHERE p.status = :status
        ORDER BY COALESCE(p.batch.id, p.id) DESC, p.id ASC
        """)
    List<PaymentReceipt> findByStatusWithContext(@Param("status") ReceiptStatus status);

    @Query("""
        SELECT p
        FROM PaymentReceipt p
        JOIN FETCH p.installment i
        JOIN FETCH i.trip
        JOIN FETCH i.user
        LEFT JOIN FETCH i.student
        LEFT JOIN FETCH p.bankAccount
        LEFT JOIN FETCH p.batch
        WHERE p.batch.id IN :batchIds
        ORDER BY p.batch.id DESC, i.installmentNumber ASC, p.id ASC
        """)
    List<PaymentReceipt> findByBatchIdInWithContext(@Param("batchIds") List<Long> batchIds);

    boolean existsByInstallmentIdAndStatus(Long installmentId, ReceiptStatus status);

    @Query("""
        SELECT CASE WHEN COUNT(p) > 0 THEN true ELSE false END
        FROM PaymentReceipt p
        JOIN p.installment i
        WHERE p.status = :status
          AND i.trip.id = :tripId
          AND i.user.id = :userId
          AND (
            (:studentId IS NULL AND i.student IS NULL)
            OR i.student.id = :studentId
          )
        """)
    boolean existsByTripIdAndUserIdAndStudentIdAndStatus(
            @Param("tripId") Long tripId,
            @Param("userId") Long userId,
            @Param("studentId") Long studentId,
            @Param("status") ReceiptStatus status
    );

    @Query("""
        SELECT DISTINCT p.batch.id
        FROM PaymentReceipt p
        WHERE p.batch IS NOT NULL
          AND p.installment.trip.id = :tripId
        """)
    List<Long> findDistinctBatchIdsByInstallmentTripId(@Param("tripId") Long tripId);
}
