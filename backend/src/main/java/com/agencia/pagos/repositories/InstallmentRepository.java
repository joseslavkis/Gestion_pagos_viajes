package com.agencia.pagos.repositories;

import com.agencia.pagos.entities.Installment;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;

public interface InstallmentRepository extends JpaRepository<Installment, Long> {

    /**
     * Recupera todas las cuotas de un viaje con su {@code user} ya inicializado via JOIN FETCH,
     * evitando el problema N+1 al generar reportes/plantillas pesadas.
     */
    @Query("SELECT i FROM Installment i JOIN FETCH i.user WHERE i.trip.id = :tripId")
    List<Installment> findByTripIdWithUsers(@Param("tripId") Long tripId);
}

