package com.agencia.pagos.payment;

import com.agencia.pagos.payment.PaymentBatch;
import org.springframework.data.jpa.repository.JpaRepository;

public interface PaymentBatchRepository extends JpaRepository<PaymentBatch, Long> {
}
