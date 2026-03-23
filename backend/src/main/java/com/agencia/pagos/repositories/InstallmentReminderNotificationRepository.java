package com.agencia.pagos.repositories;

import com.agencia.pagos.entities.InstallmentReminderNotification;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface InstallmentReminderNotificationRepository
        extends JpaRepository<InstallmentReminderNotification, Long> {

    void deleteByInstallmentTripId(Long tripId);

    List<InstallmentReminderNotification> findByInstallmentIdIn(List<Long> installmentIds);
}
