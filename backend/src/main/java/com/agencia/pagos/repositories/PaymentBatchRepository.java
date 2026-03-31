package com.agencia.pagos.repositories;

import com.agencia.pagos.entities.PaymentBatch;
import org.springframework.data.jpa.repository.JpaRepository;

public interface PaymentBatchRepository extends JpaRepository<PaymentBatch, Long> {
}
