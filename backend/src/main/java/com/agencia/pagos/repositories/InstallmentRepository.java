package com.agencia.pagos.repositories;

import com.agencia.pagos.entities.Installment;
import org.springframework.data.jpa.repository.JpaRepository;

public interface InstallmentRepository extends JpaRepository<Installment, Long> {
}
