package com.agencia.pagos;

import com.agencia.pagos.config.storage.PaymentAttachmentStorageProperties;
import com.agencia.pagos.entities.PaymentSubmission;
import com.agencia.pagos.repositories.PaymentSubmissionRepository;
import com.agencia.pagos.services.PaymentAttachmentCleanupScheduler;
import com.agencia.pagos.services.storage.PaymentAttachmentStorageService;
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
