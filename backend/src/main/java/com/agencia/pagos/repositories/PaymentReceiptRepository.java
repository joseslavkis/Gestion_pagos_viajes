package com.agencia.pagos.repositories;

import com.agencia.pagos.entities.PaymentReceipt;
import com.agencia.pagos.entities.ReceiptStatus;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface PaymentReceiptRepository extends JpaRepository<PaymentReceipt, Long> {

    List<PaymentReceipt> findByInstallmentId(Long installmentId);

    List<PaymentReceipt> findByInstallmentIdAndStatus(Long installmentId, ReceiptStatus status);

    List<PaymentReceipt> findByInstallmentUserId(Long userId);

    boolean existsByInstallmentIdAndStatus(Long installmentId, ReceiptStatus status);
}
