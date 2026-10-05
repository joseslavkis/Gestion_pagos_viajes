package com.agencia.pagos.payment;

import com.agencia.pagos.payment.dto.ReviewPaymentDTO;
import com.agencia.pagos.trip.Installment;
import com.agencia.pagos.trip.InstallmentRepository;
import jakarta.persistence.EntityNotFoundException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

@Service
public class PaymentReviewTransaction {
    private final PaymentSubmissionRepository submissions;
    private final PaymentOutcomeRepository outcomes;
    private final PaymentAllocationRepository allocations;
    private final InstallmentRepository installments;
    private final PaymentAllocationPlanner planner;
    private final PaymentMoneyPolicy money;

    public PaymentReviewTransaction(PaymentSubmissionRepository submissions, PaymentOutcomeRepository outcomes,
            PaymentAllocationRepository allocations, InstallmentRepository installments,
            PaymentAllocationPlanner planner, PaymentMoneyPolicy money) {
        this.submissions = submissions;
        this.outcomes = outcomes;
        this.allocations = allocations;
        this.installments = installments;
        this.planner = planner;
        this.money = money;
    }

    @Transactional
    public PaymentSubmission review(Long id, ReviewPaymentDTO dto, String reviewer,
            AdministrativeReviewQuoteResolver.PreparedReview prepared) {
        PaymentSubmission submission = submissions.findByIdForUpdate(id)
                .orElseThrow(() -> new EntityNotFoundException("No se encontró el pago solicitado."));
        if (submission.getStatus() != PaymentSubmissionStatus.PENDING) {
            throw new IllegalStateException("Este pago ya fue revisado");
        }
        prepared.revalidate(submission);
        var scope = prepared.scope();
        List<Installment> scoped = installments.findByTripIdAndUserIdAndStudentIdForUpdate(
                scope.tripId(), scope.userId(), scope.studentId());
        BigDecimal approved = money.requirePersistableMoney(dto.approvedAmount(), "approvedAmount");
        BigDecimal originalTrip = money.requireMoney(submission.getAmountInTripCurrency(), "amountInTripCurrency");
        String observation = dto.adminObservation() == null ? null : dto.adminObservation().trim();
        BigDecimal approvedTrip = BigDecimal.ZERO.setScale(PaymentMoneyPolicy.MONEY_SCALE);
        if (approved.signum() > 0) {
            boolean legacyCross = "v1".equals(submission.getCalculationVersion())
                    && submission.getPaymentCurrency() != scope.tripCurrency();
            boolean unchanged = dto.approvedCurrency() == submission.getPaymentCurrency()
                    && approved.compareTo(submission.getReportedAmount()) == 0;
            if (legacyCross && !unchanged
                    && !(dto.approvedCurrency() == scope.tripCurrency() && approved.compareTo(originalTrip) == 0)) {
                throw new IllegalStateException(AdministrativeReviewQuoteResolver.RECONCILIATION_REQUIRED);
            }
            PaymentAllocationPlanner.PlanResult plan;
            try {
                plan = planner.plan(scoped, approved, dto.approvedCurrency(), prepared.administrativeSnapshot().exchangeRate());
            } catch (PaymentBalanceExceededException exception) {
                throw new IllegalStateException("El monto aprobado supera el saldo pendiente. Puede aprobar hasta "
                        + exception.maxAllowedAmount().toPlainString() + " " + dto.approvedCurrency()
                        + "; el excedente convertido es " + exception.residualInTripCurrency().toPlainString()
                        + " " + scope.tripCurrency() + ".", exception);
            } catch (IllegalArgumentException exception) {
                throw new IllegalArgumentException("El monto aprobado no puede imputarse al saldo pendiente. Revise el importe.", exception);
            }
            planner.assertConservation(plan);
            approvedTrip = plan.amountInTripCurrency();
            if (legacyCross && approvedTrip.compareTo(originalTrip) != 0) {
                throw new IllegalStateException(AdministrativeReviewQuoteResolver.RECONCILIATION_REQUIRED);
            }
            String approvedObservation = "v1".equals(submission.getCalculationVersion()) && unchanged
                    ? "Aprobación conciliada con los valores históricos v1 persistidos" : observation;
            PaymentOutcome outcome = outcome(submission, PaymentOutcomeStatus.APPROVED, plan.reportedAmount(),
                    approvedTrip, prepared.administrativeSnapshot(), approvedObservation, reviewer);
            List<PaymentAllocation> entities = new ArrayList<>();
            for (var allocation : plan.allocations()) {
                Installment installment = allocation.installment();
                BigDecimal paid = installment.getPaidAmount() == null ? BigDecimal.ZERO : installment.getPaidAmount();
                installment.setPaidAmount(paid.add(allocation.amountInTripCurrency()));
                PaymentAllocation entity = new PaymentAllocation();
                entity.setOutcome(outcome);
                entity.setInstallment(installment);
                entity.setAllocationOrder(allocation.allocationOrder());
                entity.setReportedAmount(allocation.reportedAmount());
                entity.setAmountInTripCurrency(allocation.amountInTripCurrency());
                entities.add(entity);
            }
            installments.saveAll(scoped);
            allocations.saveAll(entities);
            outcome.getAllocations().addAll(entities);
        }
        BigDecimal rejectedTrip = originalTrip.subtract(approvedTrip).max(BigDecimal.ZERO)
                .setScale(PaymentMoneyPolicy.MONEY_SCALE);
        if (approved.signum() == 0 || rejectedTrip.signum() > 0) {
            BigDecimal rejected;
            try {
                BigDecimal originalRate = approved.signum() > 0 && submission.getPaymentCurrency() != scope.tripCurrency()
                        ? scope.originalSnapshot().requireApplicableRate(money, scope.paymentDate()) : null;
                rejected = approved.signum() == 0 ? submission.getReportedAmount()
                        : money.convertTripToPaymentCurrency(rejectedTrip, scope.tripCurrency(),
                                submission.getPaymentCurrency(), originalRate)
                            .max(BigDecimal.ZERO).min(submission.getReportedAmount());
            } catch (RuntimeException exception) {
                throw new IllegalStateException(AdministrativeReviewQuoteResolver.RECONCILIATION_REQUIRED, exception);
            }
            // A positive trip-cent remainder can round to zero original cents.
            // Keep that exact evidence instead of fabricating a rejected cent.
            outcome(submission, PaymentOutcomeStatus.REJECTED, rejected, rejectedTrip,
                    scope.originalSnapshot(), observation, reviewer);
        }
        submission.setStatus(PaymentSubmissionStatus.RESOLVED);
        submissions.saveAndFlush(submission);
        PaymentSubmission reviewed = submissions.findByIdWithContext(id).orElseThrow();
        reviewed.getAttachments().size(); // The existing response mapper runs after the write transaction.
        return reviewed;
    }

    private PaymentOutcome outcome(PaymentSubmission submission, PaymentOutcomeStatus status,
            BigDecimal amount, BigDecimal tripAmount, PaymentOutcomeSnapshot snapshot,
            String observation, String reviewer) {
        PaymentOutcome outcome = new PaymentOutcome();
        outcome.setSubmission(submission);
        outcome.setStatus(status);
        outcome.setReportedAmount(amount);
        outcome.setAmountInTripCurrency(tripAmount);
        outcome.applySnapshot(snapshot);
        outcome.setAdminObservation(observation == null || observation.isEmpty() ? null : observation);
        outcome.setResolvedByEmail(reviewer);
        PaymentOutcome saved = outcomes.save(outcome);
        submission.getOutcomes().add(saved);
        return saved;
    }
}
