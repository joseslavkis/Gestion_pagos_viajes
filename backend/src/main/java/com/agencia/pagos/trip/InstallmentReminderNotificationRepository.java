package com.agencia.pagos.trip;

import com.agencia.pagos.trip.InstallmentReminderNotification;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface InstallmentReminderNotificationRepository
        extends JpaRepository<InstallmentReminderNotification, Long> {

    void deleteByInstallmentTripId(Long tripId);

    void deleteByInstallmentIdIn(List<Long> installmentIds);

    List<InstallmentReminderNotification> findByInstallmentIdIn(List<Long> installmentIds);
}
