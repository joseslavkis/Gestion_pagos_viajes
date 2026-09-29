package com.agencia.pagos.payment;

import com.agencia.pagos.config.storage.PaymentAttachmentStorageProperties;
import com.agencia.pagos.payment.PaymentSubmission;
import com.agencia.pagos.payment.PaymentSubmissionRepository;
import com.agencia.pagos.payment.storage.PaymentAttachmentStorageService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

@Service
public class PaymentAttachmentCleanupScheduler {

    private static final Logger LOGGER = LoggerFactory.getLogger(PaymentAttachmentCleanupScheduler.class);

    private final PaymentSubmissionRepository paymentSubmissionRepository;
    private final PaymentAttachmentStorageService paymentAttachmentStorageService;
    private final PaymentAttachmentStorageProperties properties;
    private final Clock clock;

    @Autowired
    public PaymentAttachmentCleanupScheduler(
            PaymentSubmissionRepository paymentSubmissionRepository,
            PaymentAttachmentStorageService paymentAttachmentStorageService,
            PaymentAttachmentStorageProperties properties
    ) {
        this(paymentSubmissionRepository, paymentAttachmentStorageService, properties, Clock.systemDefaultZone());
    }

    public PaymentAttachmentCleanupScheduler(
            PaymentSubmissionRepository paymentSubmissionRepository,
            PaymentAttachmentStorageService paymentAttachmentStorageService,
            PaymentAttachmentStorageProperties properties,
            Clock clock
    ) {
        this.paymentSubmissionRepository = paymentSubmissionRepository;
        this.paymentAttachmentStorageService = paymentAttachmentStorageService;
        this.properties = properties;
        this.clock = clock;
    }

    @Scheduled(
            cron = "${app.storage.receipts.cleanup.cron}",
            zone = "${app.storage.receipts.cleanup.zone}"
    )
    @Transactional
    public void deleteExpiredReceipts() {
        if (properties.getProvider() != PaymentAttachmentStorageProperties.Provider.FILESYSTEM) {
            return;
        }

        if (!properties.getCleanup().isEnabled()) {
            return;
        }

        long retentionDays = properties.getCleanup().getRetentionDays();
        if (retentionDays <= 0) {
            LOGGER.warn("Skipping payment attachment cleanup because retentionDays={} is invalid", retentionDays);
            return;
        }

        LocalDateTime cutoff = LocalDateTime.now(clock).minusDays(retentionDays);
        int batchSize = Math.max(1, properties.getCleanup().getBatchSize());

        List<PaymentSubmission> expiredSubmissions = paymentSubmissionRepository.findExpiredWithStoredFileKey(
                cutoff,
                PageRequest.of(0, batchSize, Sort.by(Sort.Direction.ASC, "createdAt", "id"))
        );

        if (expiredSubmissions.isEmpty()) {
            return;
        }

        List<PaymentSubmission> cleanedSubmissions = new ArrayList<>();

        for (PaymentSubmission submission : expiredSubmissions) {
            boolean changed = false;
            String legacyKey = submission.getFileKey();
            boolean legacyHasChild = submission.getAttachments().stream()
                    .anyMatch(attachment -> attachment.getFileKey().equals(legacyKey));
            for (var iterator = submission.getAttachments().iterator(); iterator.hasNext();) {
                PaymentSubmissionAttachment attachment = iterator.next();
                if (paymentAttachmentStorageService.deleteReceipt(attachment.getFileKey())) {
                    iterator.remove();
                    changed = true;
                } else {
                    LOGGER.warn("Could not delete expired payment attachment '{}'; retaining its reference", attachment.getFileKey());
                }
            }
            if (legacyHasChild) {
                if (changed) {
                    submission.setFileKey(submission.getAttachments().isEmpty()
                            ? "" : submission.getAttachments().get(0).getFileKey());
                }
            } else if (legacyKey != null && !legacyKey.isBlank()) {
                if (paymentAttachmentStorageService.deleteReceipt(legacyKey)) {
                    submission.setFileKey("");
                    changed = true;
                } else {
                    LOGGER.warn("Could not delete expired legacy payment attachment '{}'; retaining its reference", legacyKey);
                }
            }
            if (changed) {
                cleanedSubmissions.add(submission);
            }
        }

        if (cleanedSubmissions.isEmpty()) {
            return;
        }

        paymentSubmissionRepository.saveAll(cleanedSubmissions);
        paymentSubmissionRepository.flush();

        LOGGER.info(
                "Deleted {} expired payment attachment(s) older than {} day(s)",
                cleanedSubmissions.size(),
                retentionDays
        );
    }
}
