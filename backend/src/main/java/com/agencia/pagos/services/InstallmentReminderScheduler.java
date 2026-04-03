package com.agencia.pagos.services;

import com.agencia.pagos.entities.Installment;
import com.agencia.pagos.entities.InstallmentReminderNotification;
import com.agencia.pagos.entities.InstallmentReminderNotificationType;
import com.agencia.pagos.entities.InstallmentStatus;
import com.agencia.pagos.entities.ReceiptStatus;
import com.agencia.pagos.entities.Role;
import com.agencia.pagos.entities.user.User;
import com.agencia.pagos.repositories.InstallmentRepository;
import com.agencia.pagos.repositories.InstallmentReminderNotificationRepository;
import com.agencia.pagos.repositories.PaymentReceiptRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

@Service
@ConditionalOnProperty(value = "app.notifications.installments.enabled", havingValue = "true", matchIfMissing = true)
public class InstallmentReminderScheduler {

    private static final Logger LOGGER = LoggerFactory.getLogger(InstallmentReminderScheduler.class);
    private static final ZoneId BUSINESS_ZONE = ZoneId.of("America/Argentina/Buenos_Aires");

    private final InstallmentRepository installmentRepository;
    private final PaymentReceiptRepository paymentReceiptRepository;
    private final InstallmentReminderNotificationRepository installmentReminderNotificationRepository;
    private final InstallmentStatusResolver installmentStatusResolver;
    private final EmailService emailService;

    @Autowired
    public InstallmentReminderScheduler(
            InstallmentRepository installmentRepository,
            PaymentReceiptRepository paymentReceiptRepository,
            InstallmentReminderNotificationRepository installmentReminderNotificationRepository,
            InstallmentStatusResolver installmentStatusResolver,
            EmailService emailService
    ) {
        this.installmentRepository = installmentRepository;
        this.paymentReceiptRepository = paymentReceiptRepository;
        this.installmentReminderNotificationRepository = installmentReminderNotificationRepository;
        this.installmentStatusResolver = installmentStatusResolver;
        this.emailService = emailService;
    }

    @Scheduled(
            cron = "${app.notifications.installments.cron}",
            zone = "${app.notifications.installments.zone}"
    )
    @Transactional
    public void sendDailyInstallmentReminders() {
        if (!emailService.isDeliveryConfigured()) {
            LOGGER.info("Skipping installment reminder emails because Brevo delivery is not fully configured");
            return;
        }

        List<Installment> installments = installmentRepository.findAllWithUserAndTrip();
        if (installments.isEmpty()) {
            return;
        }

        List<Long> installmentIds = installments.stream()
                .map(Installment::getId)
                .toList();

        Map<Long, ReceiptStatus> latestReceiptStatusByInstallmentId = installmentIds.isEmpty()
                ? Map.of()
                : paymentReceiptRepository.findByInstallmentIdIn(installmentIds).stream()
                .collect(Collectors.toMap(
                        receipt -> receipt.getInstallment().getId(),
                        receipt -> receipt.getStatus(),
                        (existing, ignored) -> existing
                ));

        Set<ReminderNotificationKey> sentReminderKeys = installmentIds.isEmpty()
                ? Set.of()
                : installmentReminderNotificationRepository.findByInstallmentIdIn(installmentIds).stream()
                .map(notification -> new ReminderNotificationKey(
                        notification.getInstallment().getId(),
                        notification.getType()
                ))
                .collect(Collectors.toSet());

        LocalDate today = LocalDate.now(BUSINESS_ZONE);
        Map<Long, UserReminderBundle> remindersByUserId = new LinkedHashMap<>();

        for (Installment installment : installments) {
            User user = installment.getUser();
            if (user == null || user.getRole() != Role.USER) {
                continue;
            }

            BigDecimal remainingAmount = getRemainingAmount(installment);
            if (remainingAmount.compareTo(BigDecimal.ZERO) <= 0) {
                continue;
            }

            ReceiptStatus latestReceiptStatus = latestReceiptStatusByInstallmentId.get(installment.getId());
            if (latestReceiptStatus == ReceiptStatus.PENDING) {
                continue;
            }

            List<InstallmentReminderNotificationType> reminderTypes = classifyInstallment(installment, today);
            if (reminderTypes.isEmpty()) {
                continue;
            }

            for (InstallmentReminderNotificationType reminderType : reminderTypes) {
                ReminderNotificationKey reminderKey = new ReminderNotificationKey(
                        installment.getId(),
                        reminderType
                );
                if (sentReminderKeys.contains(reminderKey)) {
                    continue;
                }

                UserReminderBundle bundle = remindersByUserId.computeIfAbsent(
                        user.getId(),
                        ignored -> new UserReminderBundle(user)
                );

                bundle.pendingReminders().add(new PendingReminder(
                        installment,
                        reminderType,
                        new EmailService.InstallmentReminderMailItem(
                                installment.getTrip().getName(),
                                installment.getInstallmentNumber(),
                                installment.getDueDate(),
                                remainingAmount,
                                installment.getTrip().getCurrency(),
                                toReminderKind(reminderType)
                        )
                ));
            }
        }

        remindersByUserId.values().forEach(bundle -> {
            List<EmailService.InstallmentReminderMailItem> sortedItems = bundle.pendingReminders().stream()
                    .map(PendingReminder::mailItem)
                    .sorted(Comparator
                            .comparing(EmailService.InstallmentReminderMailItem::kind)
                            .thenComparing(EmailService.InstallmentReminderMailItem::dueDate)
                            .thenComparing(EmailService.InstallmentReminderMailItem::tripName)
                            .thenComparing(EmailService.InstallmentReminderMailItem::installmentNumber))
                    .toList();

            emailService.sendInstallmentReminder(
                    bundle.user().getEmail(),
                    bundle.user().getName(),
                    sortedItems
            );

            installmentReminderNotificationRepository.saveAll(
                    bundle.pendingReminders().stream()
                            .map(reminder -> InstallmentReminderNotification.builder()
                                    .installment(reminder.installment())
                                    .type(reminder.type())
                                    .sentOn(today)
                                    .build())
                            .toList()
            );
        });

        if (!remindersByUserId.isEmpty()) {
            LOGGER.info("Sent installment reminder emails to {} users", remindersByUserId.size());
        }
    }

    private List<InstallmentReminderNotificationType> classifyInstallment(Installment installment, LocalDate today) {
        int yellowWarningDays = installment.getTrip().getYellowWarningDays() == null
                ? 0
                : installment.getTrip().getYellowWarningDays();

        InstallmentStatus effectiveStatus = installmentStatusResolver.computeEffective(
                installment.getStatus(),
                installment.getDueDate(),
                yellowWarningDays,
                installment.getPaidAmount(),
                installment.getTotalDue()
        );
        if (effectiveStatus == InstallmentStatus.RED
                || effectiveStatus == InstallmentStatus.RETROACTIVE) {
            List<InstallmentReminderNotificationType> reminderTypes = new ArrayList<>();
            reminderTypes.add(InstallmentReminderNotificationType.OVERDUE);
            if (!today.isBefore(installment.getDueDate().plusDays(7))) {
                reminderTypes.add(InstallmentReminderNotificationType.OVERDUE_7_DAYS);
            }
            return reminderTypes;
        }

        LocalDate yellowWindowEnd = today.plusDays(Math.max(0, yellowWarningDays));
        if (!installment.getDueDate().isAfter(yellowWindowEnd)) {
            return List.of(InstallmentReminderNotificationType.DUE_SOON);
        }

        return List.of();
    }

    private EmailService.ReminderKind toReminderKind(InstallmentReminderNotificationType reminderType) {
        if (reminderType == InstallmentReminderNotificationType.OVERDUE) {
            return EmailService.ReminderKind.OVERDUE;
        }
        if (reminderType == InstallmentReminderNotificationType.OVERDUE_7_DAYS) {
            return EmailService.ReminderKind.OVERDUE_7_DAYS;
        }
        return EmailService.ReminderKind.DUE_SOON;
    }

    private BigDecimal getRemainingAmount(Installment installment) {
        BigDecimal totalDue = installment.getTotalDue() == null ? BigDecimal.ZERO : installment.getTotalDue();
        BigDecimal paidAmount = installment.getPaidAmount() == null ? BigDecimal.ZERO : installment.getPaidAmount();
        BigDecimal remainingAmount = totalDue.subtract(paidAmount);
        return remainingAmount.max(BigDecimal.ZERO);
    }

    private record UserReminderBundle(User user, List<PendingReminder> pendingReminders) {
        private UserReminderBundle(User user) {
            this(user, new ArrayList<>());
        }
    }

    private record PendingReminder(
            Installment installment,
            InstallmentReminderNotificationType type,
            EmailService.InstallmentReminderMailItem mailItem
    ) {
    }

    private record ReminderNotificationKey(
            Long installmentId,
            InstallmentReminderNotificationType type
    ) {
    }
}
