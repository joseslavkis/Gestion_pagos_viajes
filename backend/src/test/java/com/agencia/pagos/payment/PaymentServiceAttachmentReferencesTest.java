package com.agencia.pagos.payment;

import com.agencia.pagos.payment.dto.PaymentInstallmentHistoryDTO;
import com.agencia.pagos.payment.dto.PaymentSubmissionDTO;
import com.agencia.pagos.payment.storage.PaymentAttachmentStorageService;
import com.agencia.pagos.trip.Installment;
import com.agencia.pagos.trip.Trip;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class PaymentServiceAttachmentReferencesTest {
    @Mock private PaymentAttachmentStorageService storage;
    @Mock private PaymentAllocationPlanner planner;
    @InjectMocks private PaymentService service;

    @Test
    void storedKeysAndResolverNullsNeverEnterReferences() {
        assertThat(references((String) null)).isEmpty();
        assertThat(references("  ")).isEmpty();
        verifyNoInteractions(storage);

        // An unstubbed storage resolver returns null.
        assertThat(references("legacy.png")).isEmpty();
        when(storage.resolveFileReference("legacy.png")).thenReturn("  ", "url/legacy.png");
        assertThat(references("legacy.png")).isEmpty();
        assertThat(references("legacy.png")).containsExactly("url/legacy.png");
    }

    @Test
    void nullSubmissionOrChildrenFallBackToLegacyWithoutDroppingChildOrder() {
        assertThat(references((PaymentSubmission) null)).isEmpty();
        PaymentSubmission submission = new PaymentSubmission();
        submission.setFileKey("legacy.png");
        org.springframework.test.util.ReflectionTestUtils.setField(submission, "attachments", null);
        when(storage.resolveFileReference("legacy.png")).thenReturn("url/legacy.png");
        assertThat(references(submission)).containsExactly("url/legacy.png");

        ReflectionTestUtils.setField(submission, "attachments", new java.util.ArrayList<>());
        submission.addAttachment("first.png");
        submission.addAttachment("  ");
        submission.addAttachment("third.png");
        submission.getAttachments().add(null);
        when(storage.resolveFileReference("first.png")).thenReturn("url/first.png");
        when(storage.resolveFileReference("third.png")).thenReturn("url/third.png");
        assertThat(references(submission)).containsExactly("url/first.png", "url/third.png");
    }

    @Test
    void legacyReceiptAndSubmissionProjectBlankFileKeyWhenStorageReturnsNull() {
        PaymentReceipt receipt = mock(PaymentReceipt.class);
        Installment installment = mock(Installment.class);
        Trip trip = mock(Trip.class);
        when(receipt.getId()).thenReturn(9L);
        when(receipt.getInstallment()).thenReturn(installment);
        when(receipt.getReportedAmount()).thenReturn(BigDecimal.ONE);
        when(receipt.getAmountInTripCurrency()).thenReturn(BigDecimal.ONE);
        when(receipt.getStatus()).thenReturn(ReceiptStatus.PENDING);
        when(installment.getId()).thenReturn(7L);
        when(installment.getInstallmentNumber()).thenReturn(1);
        when(installment.getTrip()).thenReturn(trip);
        when(trip.getId()).thenReturn(5L);
        when(planner.getRemainingAmount(installment)).thenReturn(BigDecimal.ZERO);

        PaymentInstallmentHistoryDTO history = ReflectionTestUtils.invokeMethod(service, "toInstallmentHistoryDTO", receipt);
        assertThat(history.fileKey()).isEmpty();
        assertThat(history.fileKeys()).isEmpty();
        PaymentSubmissionDTO legacy = ((List<PaymentSubmissionDTO>) ReflectionTestUtils.invokeMethod(
                service, "toLegacySubmissionDTOs", List.of(receipt))).get(0);
        assertThat(legacy.fileKey()).isEmpty();
        assertThat(legacy.fileKeys()).isEmpty();
        verifyNoInteractions(storage);

        when(receipt.getFileKey()).thenReturn("  ");
        assertThat(((PaymentInstallmentHistoryDTO) ReflectionTestUtils.invokeMethod(service,
                "toInstallmentHistoryDTO", receipt)).fileKeys()).isEmpty();
        assertThat(((List<PaymentSubmissionDTO>) ReflectionTestUtils.invokeMethod(service,
                "toLegacySubmissionDTOs", List.of(receipt))).get(0).fileKeys()).isEmpty();
        verifyNoInteractions(storage);

        when(receipt.getFileKey()).thenReturn("legacy.png");
        assertThat(((PaymentInstallmentHistoryDTO) ReflectionTestUtils.invokeMethod(service,
                "toInstallmentHistoryDTO", receipt)).fileKeys()).isEmpty();
        assertThat(((List<PaymentSubmissionDTO>) ReflectionTestUtils.invokeMethod(service,
                "toLegacySubmissionDTOs", List.of(receipt))).get(0).fileKeys()).isEmpty();
        verify(storage, times(2)).resolveFileReference("legacy.png");
    }

    @SuppressWarnings("unchecked")
    private List<String> references(String storedKey) {
        return ReflectionTestUtils.invokeMethod(service, "attachmentReferences", storedKey);
    }

    @SuppressWarnings("unchecked")
    private List<String> references(PaymentSubmission submission) {
        return ReflectionTestUtils.invokeMethod(service, "attachmentReferences", submission);
    }
}
