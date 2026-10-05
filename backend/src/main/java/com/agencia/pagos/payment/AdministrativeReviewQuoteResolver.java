package com.agencia.pagos.payment;

import com.agencia.pagos.payment.dto.ReviewPaymentDTO;
import com.agencia.pagos.shared.money.Currency;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Objects;

@Component
public class AdministrativeReviewQuoteResolver {
    private static final Logger LOGGER = LoggerFactory.getLogger(AdministrativeReviewQuoteResolver.class);
    static final String RECONCILIATION_REQUIRED = "El pago histórico requiere conciliación manual para esta revisión.";
    private final ExchangeRateQuoteProvider provider;
    private final PaymentMoneyPolicy money;

    public AdministrativeReviewQuoteResolver(ExchangeRateQuoteProvider provider, PaymentMoneyPolicy money) {
        this.provider = provider;
        this.money = money;
    }

    /** Captures the exact applicability boundary before any financial lock is acquired. */
    public record ReviewScope(Long tripId, Long userId, Long studentId, Long anchorId,
            Currency tripCurrency, LocalDate paymentDate, BigDecimal reportedAmount,
            BigDecimal originalTripAmount, PaymentOutcomeSnapshot originalSnapshot) {
        static ReviewScope from(PaymentSubmission submission) {
            return new ReviewScope(submission.getTrip().getId(), submission.getUser().getId(),
                    submission.getStudent() == null ? null : submission.getStudent().getId(),
                    submission.getAnchorInstallment().getId(), submission.getTrip().getCurrency(),
                    submission.getReportedPaymentDate(), submission.getReportedAmount(),
                    submission.getAmountInTripCurrency(), PaymentOutcomeSnapshot.fromSubmission(submission));
        }
    }

    public record PreparedReview(ReviewScope scope, PaymentOutcomeSnapshot administrativeSnapshot) {
        public void revalidate(PaymentSubmission locked) {
            if (!scope.equals(ReviewScope.from(locked))) {
                throw new IllegalStateException("El pago cambió durante la revisión. Actualice la información e intente nuevamente.");
            }
        }
    }

    public PreparedReview prepare(PaymentSubmission submission, ReviewPaymentDTO dto) {
        if (submission.getStatus() != PaymentSubmissionStatus.PENDING) {
            throw new IllegalStateException("Este pago ya fue revisado");
        }
        if (dto.approvedCurrency() == null) {
            throw new IllegalArgumentException("Debe seleccionar la moneda aprobada.");
        }
        BigDecimal amount;
        try {
            amount = money.requirePersistableMoney(dto.approvedAmount(), "approvedAmount");
        } catch (IllegalArgumentException exception) {
            throw new IllegalArgumentException("El monto aprobado debe ser un importe válido, con hasta dos decimales y dentro del límite permitido.", exception);
        }
        if (amount.signum() < 0) {
            throw new IllegalArgumentException("El monto aprobado no puede ser negativo");
        }
        boolean corrected = dto.approvedCurrency() != submission.getPaymentCurrency()
                || amount.compareTo(submission.getReportedAmount()) != 0;
        if (corrected && (dto.adminObservation() == null || dto.adminObservation().isBlank())) {
            throw new IllegalStateException("Se requiere una observación al corregir el monto o la moneda informada.");
        }
        if (dto.adminObservation() != null && dto.adminObservation().trim().length() > 500) {
            throw new IllegalArgumentException("La observación administrativa no puede superar los 500 caracteres");
        }
        ReviewScope scope = ReviewScope.from(submission);
        if (amount.signum() == 0 || dto.approvedCurrency() == scope.tripCurrency()) {
            return new PreparedReview(scope, PaymentOutcomeSnapshot.administrative(dto.approvedCurrency(), null));
        }
        if (dto.approvedCurrency() == submission.getPaymentCurrency()) {
            PaymentOutcomeSnapshot frozen = scope.originalSnapshot();
            frozen.requireApplicableRate(money, scope.paymentDate());
            return new PreparedReview(scope, frozen);
        }
        try {
            ExchangeRateQuote quote = provider.getOfficialQuoteForDate(scope.paymentDate());
            BigDecimal rate = money.requireProviderRate(quote.sellRate());
            if (!Objects.equals(scope.paymentDate(), quote.requestedDate())
                    || quote.effectiveDate().isAfter(quote.requestedDate())) {
                throw new IllegalStateException("Historical quote date does not match the requested payment date");
            }
            ExchangeRateQuote normalized = new ExchangeRateQuote(rate, quote.requestedDate(),
                    quote.effectiveDate(), quote.source(), quote.provider(), quote.providerTimestamp());
            return new PreparedReview(scope, PaymentOutcomeSnapshot.administrative(dto.approvedCurrency(), normalized));
        } catch (RuntimeException exception) {
            LOGGER.warn("Administrative historical quote preparation failed for submission {}", submission.getId(), exception);
            throw new IllegalStateException("No se pudo obtener la cotización histórica. Intente revisar el pago nuevamente.", exception);
        }
    }

}
