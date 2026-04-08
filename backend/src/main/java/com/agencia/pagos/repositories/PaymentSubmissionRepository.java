package com.agencia.pagos.repositories;

import com.agencia.pagos.entities.PaymentSubmission;
import com.agencia.pagos.entities.PaymentSubmissionStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface PaymentSubmissionRepository extends JpaRepository<PaymentSubmission, Long> {

    void deleteByTripId(Long tripId);

    @Query("""
        SELECT p
        FROM PaymentSubmission p
        JOIN FETCH p.trip
        JOIN FETCH p.user
        LEFT JOIN FETCH p.student
        JOIN FETCH p.anchorInstallment
        LEFT JOIN FETCH p.bankAccount
        LEFT JOIN FETCH p.outcomes o
        LEFT JOIN FETCH o.allocations a
        LEFT JOIN FETCH a.installment
        WHERE p.id = :id
        """)
    Optional<PaymentSubmission> findByIdWithContext(@Param("id") Long id);

    @Query("""
        SELECT p
        FROM PaymentSubmission p
        JOIN FETCH p.trip
        JOIN FETCH p.user
        LEFT JOIN FETCH p.student
        JOIN FETCH p.anchorInstallment
        LEFT JOIN FETCH p.bankAccount
        LEFT JOIN FETCH p.outcomes o
        LEFT JOIN FETCH o.allocations a
        LEFT JOIN FETCH a.installment
        WHERE p.user.id = :userId
        ORDER BY p.reportedPaymentDate DESC, p.id DESC
        """)
    List<PaymentSubmission> findByUserIdWithContext(@Param("userId") Long userId);

    @Query("""
        SELECT p
        FROM PaymentSubmission p
        JOIN FETCH p.trip
        JOIN FETCH p.user
        LEFT JOIN FETCH p.student
        JOIN FETCH p.anchorInstallment
        LEFT JOIN FETCH p.bankAccount
        LEFT JOIN FETCH p.outcomes o
        LEFT JOIN FETCH o.allocations a
        LEFT JOIN FETCH a.installment
        WHERE p.status = :status
        ORDER BY p.reportedPaymentDate DESC, p.id DESC
        """)
    List<PaymentSubmission> findByStatusWithContext(@Param("status") PaymentSubmissionStatus status);

    @Query("""
        SELECT CASE WHEN COUNT(p) > 0 THEN true ELSE false END
        FROM PaymentSubmission p
        WHERE p.status = :status
          AND p.trip.id = :tripId
          AND p.user.id = :userId
          AND (
            (:studentId IS NULL AND p.student IS NULL)
            OR p.student.id = :studentId
          )
        """)
    boolean existsByTripIdAndUserIdAndStudentIdAndStatus(
            @Param("tripId") Long tripId,
            @Param("userId") Long userId,
            @Param("studentId") Long studentId,
            @Param("status") PaymentSubmissionStatus status
    );

    @Query("""
        SELECT p
        FROM PaymentSubmission p
        LEFT JOIN FETCH p.outcomes o
        WHERE p.trip.id = :tripId
          AND p.user.id = :userId
          AND (
            (:studentId IS NULL AND p.student IS NULL)
            OR p.student.id = :studentId
          )
        ORDER BY p.id DESC
        """)
    List<PaymentSubmission> findByTripIdAndUserIdAndStudentIdOrderByNewest(
            @Param("tripId") Long tripId,
            @Param("userId") Long userId,
            @Param("studentId") Long studentId
    );
}
