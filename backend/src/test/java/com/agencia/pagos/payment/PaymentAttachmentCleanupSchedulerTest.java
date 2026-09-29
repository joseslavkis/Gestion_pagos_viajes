package com.agencia.pagos.payment;

import com.agencia.pagos.config.storage.PaymentAttachmentStorageProperties;
import com.agencia.pagos.payment.PaymentSubmission;
import com.agencia.pagos.payment.PaymentSubmissionRepository;
import com.agencia.pagos.payment.PaymentAttachmentCleanupScheduler;
import com.agencia.pagos.payment.storage.PaymentAttachmentStorageService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Pageable;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class PaymentAttachmentCleanupSchedulerTest {

    private static final Clock FIXED_CLOCK = Clock.fixed(Instant.parse("2026-04-09T18:00:00Z"), ZoneOffset.UTC);

    @Mock
    private PaymentSubmissionRepository paymentSubmissionRepository;

    @Mock
    private PaymentAttachmentStorageService paymentAttachmentStorageService;

    private PaymentAttachmentStorageProperties properties;

    @BeforeEach
    void setUp() {
        properties = new PaymentAttachmentStorageProperties();
        properties.setProvider(PaymentAttachmentStorageProperties.Provider.FILESYSTEM);
        properties.getCleanup().setEnabled(true);
        properties.getCleanup().setRetentionDays(365);
        properties.getCleanup().setBatchSize(50);
    }

    @Test
    void deleteExpiredReceipts_cleansOldAttachmentsAndClearsFileKey() {
        PaymentSubmission expiredSubmission = new PaymentSubmission();
        expiredSubmission.setFileKey("receipts/trip-1/user-2/student-1/old.png");
        expiredSubmission.setCreatedAt(LocalDateTime.of(2025, 4, 8, 0, 0));

        when(paymentSubmissionRepository.findExpiredWithStoredFileKey(
                eq(LocalDateTime.of(2025, 4, 9, 18, 0)),
                any(Pageable.class)
        )).thenReturn(List.of(expiredSubmission));
        when(paymentAttachmentStorageService.deleteReceipt(expiredSubmission.getFileKey())).thenReturn(true);

        PaymentAttachmentCleanupScheduler scheduler = new PaymentAttachmentCleanupScheduler(
                paymentSubmissionRepository,
                paymentAttachmentStorageService,
                properties,
                FIXED_CLOCK
        );

        scheduler.deleteExpiredReceipts();

        ArgumentCaptor<List<PaymentSubmission>> cleanedCaptor = ArgumentCaptor.forClass(List.class);

        verify(paymentSubmissionRepository).saveAll(cleanedCaptor.capture());
        verify(paymentSubmissionRepository).flush();

        List<PaymentSubmission> cleaned = cleanedCaptor.getValue();
        assertEquals(1, cleaned.size());
        assertTrue(cleaned.contains(expiredSubmission));
        assertEquals("", expiredSubmission.getFileKey());
    }

    @Test
    void deleteExpiredReceipts_preservesReferenceWhenDeleteFails() {
        PaymentSubmission expiredSubmission = new PaymentSubmission();
        expiredSubmission.setFileKey("receipts/trip-1/user-2/student-1/broken.png");
        expiredSubmission.setCreatedAt(LocalDateTime.of(2025, 4, 8, 0, 0));

        when(paymentSubmissionRepository.findExpiredWithStoredFileKey(
                eq(LocalDateTime.of(2025, 4, 9, 18, 0)),
                any(Pageable.class)
        )).thenReturn(List.of(expiredSubmission));
        when(paymentAttachmentStorageService.deleteReceipt(expiredSubmission.getFileKey())).thenReturn(false);

        PaymentAttachmentCleanupScheduler scheduler = new PaymentAttachmentCleanupScheduler(
                paymentSubmissionRepository,
                paymentAttachmentStorageService,
                properties,
                FIXED_CLOCK
        );

        scheduler.deleteExpiredReceipts();

        verify(paymentSubmissionRepository, never()).saveAll(any());
        verify(paymentSubmissionRepository, never()).flush();
        assertEquals("receipts/trip-1/user-2/student-1/broken.png", expiredSubmission.getFileKey());
    }

    @Test
    void deleteExpiredReceipts_doesNotRetryFailedChildThroughLegacyKey() {
        PaymentSubmission submission = expired("first.png", "first.png", "second.png");
        when(paymentSubmissionRepository.findExpiredWithStoredFileKey(any(), any(Pageable.class)))
                .thenReturn(List.of(submission));
        when(paymentAttachmentStorageService.deleteReceipt("first.png")).thenReturn(false, true);
        when(paymentAttachmentStorageService.deleteReceipt("second.png")).thenReturn(true);

        scheduler().deleteExpiredReceipts();

        verify(paymentAttachmentStorageService, times(1)).deleteReceipt("first.png");
        verify(paymentAttachmentStorageService, times(1)).deleteReceipt("second.png");
        assertEquals("first.png", submission.getFileKey());
        assertEquals(List.of(0), submission.getAttachments().stream().map(PaymentSubmissionAttachment::getPosition).toList());
        assertEquals(List.of("first.png"), submission.getAttachments().stream().map(PaymentSubmissionAttachment::getFileKey).toList());
        verify(paymentSubmissionRepository).saveAll(List.of(submission));
    }

    @Test
    void deleteExpiredReceipts_keepsSparsePositionsInUploadOrderForPartialFailure() {
        PaymentSubmission submission = expired("first.png", "first.png", "second.png", "third.png", "fourth.png");
        when(paymentSubmissionRepository.findExpiredWithStoredFileKey(any(), any(Pageable.class)))
                .thenReturn(List.of(submission));
        when(paymentAttachmentStorageService.deleteReceipt("first.png")).thenReturn(true);
        when(paymentAttachmentStorageService.deleteReceipt("second.png")).thenReturn(false);
        when(paymentAttachmentStorageService.deleteReceipt("third.png")).thenReturn(true);
        when(paymentAttachmentStorageService.deleteReceipt("fourth.png")).thenReturn(false);

        scheduler().deleteExpiredReceipts();

        assertEquals("second.png", submission.getFileKey());
        assertEquals(List.of(1, 3), submission.getAttachments().stream().map(PaymentSubmissionAttachment::getPosition).toList());
        assertEquals(List.of("second.png", "fourth.png"), submission.getAttachments().stream().map(PaymentSubmissionAttachment::getFileKey).toList());
        verify(paymentAttachmentStorageService, times(1)).deleteReceipt("second.png");
        verify(paymentSubmissionRepository).saveAll(List.of(submission));
    }

    @Test
    void deleteExpiredReceipts_deletesLegacyOnlyReferenceOnce() {
        PaymentSubmission submission = expired("legacy.png");
        when(paymentSubmissionRepository.findExpiredWithStoredFileKey(any(), any(Pageable.class)))
                .thenReturn(List.of(submission));
        when(paymentAttachmentStorageService.deleteReceipt("legacy.png")).thenReturn(true);

        scheduler().deleteExpiredReceipts();

        assertEquals("", submission.getFileKey());
        assertTrue(submission.getAttachments().isEmpty());
        verify(paymentAttachmentStorageService, times(1)).deleteReceipt("legacy.png");
        verify(paymentSubmissionRepository).saveAll(List.of(submission));
    }

    private PaymentSubmission expired(String legacyKey, String... children) {
        PaymentSubmission submission = new PaymentSubmission();
        submission.setFileKey(legacyKey);
        submission.setCreatedAt(LocalDateTime.of(2025, 4, 8, 0, 0));
        for (String child : children) {
            submission.addAttachment(child);
        }
        return submission;
    }

    private PaymentAttachmentCleanupScheduler scheduler() {
        return new PaymentAttachmentCleanupScheduler(paymentSubmissionRepository,
                paymentAttachmentStorageService, properties, FIXED_CLOCK);
    }

    @Test
    void deleteExpiredReceipts_skipsWhenCleanupIsDisabled() {
        properties.getCleanup().setEnabled(false);

        PaymentAttachmentCleanupScheduler scheduler = new PaymentAttachmentCleanupScheduler(
                paymentSubmissionRepository,
                paymentAttachmentStorageService,
                properties,
                FIXED_CLOCK
        );

        scheduler.deleteExpiredReceipts();

        verify(paymentSubmissionRepository, never()).findExpiredWithStoredFileKey(any(), any(Pageable.class));
    }

    @Test
    void deleteExpiredReceipts_skipsWhenProviderIsNotFilesystem() {
        properties.setProvider(PaymentAttachmentStorageProperties.Provider.INLINE);

        PaymentAttachmentCleanupScheduler scheduler = new PaymentAttachmentCleanupScheduler(
                paymentSubmissionRepository,
                paymentAttachmentStorageService,
                properties,
                FIXED_CLOCK
        );

        scheduler.deleteExpiredReceipts();

        verify(paymentSubmissionRepository, never()).findExpiredWithStoredFileKey(any(), any(Pageable.class));
    }
}
