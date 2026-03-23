package com.agencia.pagos;

import com.agencia.pagos.entities.Currency;
import com.agencia.pagos.entities.Installment;
import com.agencia.pagos.entities.InstallmentReminderNotification;
import com.agencia.pagos.entities.InstallmentReminderNotificationType;
import com.agencia.pagos.entities.InstallmentStatus;
import com.agencia.pagos.entities.PaymentReceipt;
import com.agencia.pagos.entities.PaymentMethod;
import com.agencia.pagos.entities.ReceiptStatus;
import com.agencia.pagos.entities.Role;
import com.agencia.pagos.entities.Trip;
import com.agencia.pagos.entities.user.User;
import com.agencia.pagos.repositories.InstallmentRepository;
import com.agencia.pagos.repositories.InstallmentReminderNotificationRepository;
import com.agencia.pagos.repositories.PaymentReceiptRepository;
import com.agencia.pagos.services.EmailService;
import com.agencia.pagos.services.InstallmentReminderScheduler;
import com.agencia.pagos.services.InstallmentStatusResolver;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class InstallmentReminderSchedulerTest {

    private static final ZoneId BUSINESS_ZONE = ZoneId.of("America/Argentina/Buenos_Aires");

    @Mock
    private InstallmentRepository installmentRepository;

    @Mock
    private PaymentReceiptRepository paymentReceiptRepository;

    @Mock
    private InstallmentReminderNotificationRepository installmentReminderNotificationRepository;

    @Mock
    private EmailService emailService;

    private final InstallmentStatusResolver installmentStatusResolver = new InstallmentStatusResolver();

    @Test
    void sendDailyInstallmentReminders_envNoConfigurado_noEnviaCorreos() {
        InstallmentReminderScheduler scheduler = new InstallmentReminderScheduler(
                installmentRepository,
                paymentReceiptRepository,
                installmentReminderNotificationRepository,
                installmentStatusResolver,
                emailService
        );

        when(emailService.isDeliveryConfigured()).thenReturn(false);

        scheduler.sendDailyInstallmentReminders();

        verify(installmentRepository, never()).findAllWithUserAndTrip();
        verify(emailService, never()).sendInstallmentReminder(anyString(), anyString(), anyList());
    }

    @Test
    void sendDailyInstallmentReminders_enviaUnResumenConCuotasPorVencerYVencidas() {
        InstallmentReminderScheduler scheduler = new InstallmentReminderScheduler(
                installmentRepository,
                paymentReceiptRepository,
                installmentReminderNotificationRepository,
                installmentStatusResolver,
                emailService
        );

        LocalDate today = LocalDate.now(BUSINESS_ZONE);
        User user = buildUser("padre@example.com", "Jose");
        Trip trip = buildTrip("Bariloche 2026", 5);

        Installment dueSoon = buildInstallment(1L, user, trip, 1, today.plusDays(3), "100.00", "0.00", InstallmentStatus.YELLOW);
        Installment overdue = buildInstallment(2L, user, trip, 2, today.minusDays(1), "100.00", "20.00", InstallmentStatus.YELLOW);
        Installment withPendingReceipt = buildInstallment(3L, user, trip, 3, today.plusDays(2), "80.00", "0.00", InstallmentStatus.YELLOW);
        Installment farFuture = buildInstallment(4L, user, trip, 4, today.plusDays(30), "120.00", "0.00", InstallmentStatus.YELLOW);

        PaymentReceipt pendingReceipt = PaymentReceipt.builder()
                .id(40L)
                .installment(withPendingReceipt)
                .reportedAmount(new BigDecimal("80.00"))
                .reportedPaymentDate(today)
                .paymentCurrency(Currency.ARS)
                .paymentMethod(PaymentMethod.BANK_TRANSFER)
                .status(ReceiptStatus.PENDING)
                .fileKey("")
                .build();

        when(emailService.isDeliveryConfigured()).thenReturn(true);
        when(installmentRepository.findAllWithUserAndTrip()).thenReturn(List.of(
                dueSoon,
                overdue,
                withPendingReceipt,
                farFuture
        ));
        when(paymentReceiptRepository.findByInstallmentIdIn(List.of(1L, 2L, 3L, 4L)))
                .thenReturn(List.of(pendingReceipt));
        when(installmentReminderNotificationRepository.findByInstallmentIdIn(List.of(1L, 2L, 3L, 4L)))
                .thenReturn(List.of());

        scheduler.sendDailyInstallmentReminders();

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<EmailService.InstallmentReminderMailItem>> itemsCaptor = ArgumentCaptor.forClass(List.class);

        verify(emailService).sendInstallmentReminder(
                org.mockito.ArgumentMatchers.eq("padre@example.com"),
                org.mockito.ArgumentMatchers.eq("Jose"),
                itemsCaptor.capture()
        );

        List<EmailService.InstallmentReminderMailItem> items = itemsCaptor.getValue();
        assertEquals(2, items.size());
        assertTrue(items.stream().anyMatch(item ->
                item.installmentNumber().equals(1)
                        && item.kind() == EmailService.ReminderKind.DUE_SOON
                        && item.remainingAmount().compareTo(new BigDecimal("100.00")) == 0
        ));
        assertTrue(items.stream().anyMatch(item ->
                item.installmentNumber().equals(2)
                        && item.kind() == EmailService.ReminderKind.OVERDUE
                        && item.remainingAmount().compareTo(new BigDecimal("80.00")) == 0
        ));
    }

    @Test
    void sendDailyInstallmentReminders_noReenviaSiYaSeAvisoElEstadoAmarillo() {
        InstallmentReminderScheduler scheduler = new InstallmentReminderScheduler(
                installmentRepository,
                paymentReceiptRepository,
                installmentReminderNotificationRepository,
                installmentStatusResolver,
                emailService
        );

        LocalDate today = LocalDate.now(BUSINESS_ZONE);
        User user = buildUser("padre@example.com", "Jose");
        Trip trip = buildTrip("Bariloche 2026", 5);
        Installment dueSoon = buildInstallment(1L, user, trip, 1, today.plusDays(2), "100.00", "0.00", InstallmentStatus.YELLOW);
        InstallmentReminderNotification existingNotification = InstallmentReminderNotification.builder()
                .id(10L)
                .installment(dueSoon)
                .type(InstallmentReminderNotificationType.DUE_SOON)
                .sentOn(today.minusDays(1))
                .build();

        when(emailService.isDeliveryConfigured()).thenReturn(true);
        when(installmentRepository.findAllWithUserAndTrip()).thenReturn(List.of(dueSoon));
        when(paymentReceiptRepository.findByInstallmentIdIn(List.of(1L))).thenReturn(List.of());
        when(installmentReminderNotificationRepository.findByInstallmentIdIn(List.of(1L)))
                .thenReturn(List.of(existingNotification));

        scheduler.sendDailyInstallmentReminders();

        verify(emailService, never()).sendInstallmentReminder(anyString(), anyString(), anyList());
        verify(installmentRepository, times(1)).findAllWithUserAndTrip();
    }

    @Test
    void sendDailyInstallmentReminders_enviaUnaVezEnAmarilloYOtraCuandoPasaAVencida() {
        InstallmentReminderScheduler scheduler = new InstallmentReminderScheduler(
                installmentRepository,
                paymentReceiptRepository,
                installmentReminderNotificationRepository,
                installmentStatusResolver,
                emailService
        );

        LocalDate today = LocalDate.now(BUSINESS_ZONE);
        User user = buildUser("padre@example.com", "Jose");
        Trip trip = buildTrip("Bariloche 2026", 5);
        Installment installment = buildInstallment(1L, user, trip, 1, today.plusDays(2), "100.00", "0.00", InstallmentStatus.YELLOW);

        InstallmentReminderNotification dueSoonNotification = InstallmentReminderNotification.builder()
                .id(10L)
                .installment(installment)
                .type(InstallmentReminderNotificationType.DUE_SOON)
                .sentOn(today)
                .build();

        when(emailService.isDeliveryConfigured()).thenReturn(true);
        when(installmentRepository.findAllWithUserAndTrip()).thenReturn(List.of(installment), List.of(installment));
        when(paymentReceiptRepository.findByInstallmentIdIn(List.of(1L))).thenReturn(List.of(), List.of());
        when(installmentReminderNotificationRepository.findByInstallmentIdIn(List.of(1L)))
                .thenReturn(List.of(), List.of(dueSoonNotification));

        scheduler.sendDailyInstallmentReminders();

        installment.setDueDate(today.minusDays(1));
        scheduler.sendDailyInstallmentReminders();

        verify(emailService, times(2)).sendInstallmentReminder(
                org.mockito.ArgumentMatchers.eq("padre@example.com"),
                org.mockito.ArgumentMatchers.eq("Jose"),
                org.mockito.ArgumentMatchers.anyList()
        );
        verify(installmentReminderNotificationRepository, times(2)).saveAll(org.mockito.ArgumentMatchers.anyList());
    }

    private User buildUser(String email, String name) {
        User user = new User();
        user.setRole(Role.USER);
        user.setName(name);
        user.setLastname("Test");
        user.setPassword("secret");
        user.setActive(true);
        setField(user, "email", email);
        return user;
    }

    private Trip buildTrip(String name, int yellowWarningDays) {
        Trip trip = new Trip();
        trip.setName(name);
        trip.setCurrency(Currency.ARS);
        trip.setYellowWarningDays(yellowWarningDays);
        trip.setDueDay(10);
        trip.setInstallmentsCount(4);
        trip.setFixedFineAmount(BigDecimal.ZERO);
        trip.setRetroactiveActive(false);
        trip.setFirstDueDate(LocalDate.now(BUSINESS_ZONE));
        trip.setTotalAmount(new BigDecimal("400.00"));
        return trip;
    }

    private Installment buildInstallment(
            Long id,
            User user,
            Trip trip,
            int installmentNumber,
            LocalDate dueDate,
            String totalDue,
            String paidAmount,
            InstallmentStatus status
    ) {
        Installment installment = new Installment();
        installment.setId(id);
        installment.setUser(user);
        installment.setTrip(trip);
        installment.setInstallmentNumber(installmentNumber);
        installment.setDueDate(dueDate);
        installment.setCapitalAmount(new BigDecimal(totalDue));
        installment.setFineAmount(BigDecimal.ZERO);
        installment.setTotalDue(new BigDecimal(totalDue));
        installment.setPaidAmount(new BigDecimal(paidAmount));
        installment.setStatus(status);
        return installment;
    }

    private void setField(Object target, String fieldName, Object value) {
        try {
            java.lang.reflect.Field field = target.getClass().getDeclaredField(fieldName);
            field.setAccessible(true);
            field.set(target, value);
        } catch (ReflectiveOperationException exception) {
            throw new RuntimeException(exception);
        }
    }
}
