package com.agencia.pagos.payment;

import com.agencia.pagos.payment.dto.ReviewPaymentDTO;
import jakarta.persistence.EntityNotFoundException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/** Quote preparation and locked accounting have deliberately separate transaction boundaries. */
@Service
public class PaymentReviewService {
    private final PaymentSubmissionRepository submissions;
    private final AdministrativeReviewQuoteResolver quotes;
    private final PaymentReviewTransaction transaction;

    public PaymentReviewService(PaymentSubmissionRepository submissions,
            AdministrativeReviewQuoteResolver quotes, PaymentReviewTransaction transaction) {
        this.submissions = submissions;
        this.quotes = quotes;
        this.transaction = transaction;
    }

    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    public PaymentSubmission review(Long id, ReviewPaymentDTO dto, String reviewer) {
        PaymentSubmission original = submissions.findByIdWithContext(id)
                .orElseThrow(() -> new EntityNotFoundException("No se encontró el pago solicitado."));
        var prepared = quotes.prepare(original, dto);
        return transaction.review(id, dto, reviewer, prepared);
    }
}
