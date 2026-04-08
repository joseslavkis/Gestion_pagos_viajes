package com.agencia.pagos.services;

import com.agencia.pagos.entities.Installment;
import com.agencia.pagos.entities.PaymentOutcome;
import com.agencia.pagos.entities.PaymentOutcomeStatus;
import com.agencia.pagos.entities.PaymentSubmission;
import com.agencia.pagos.entities.PaymentSubmissionStatus;
import com.agencia.pagos.entities.ReceiptStatus;
import com.agencia.pagos.repositories.PaymentSubmissionRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Service
@Transactional(readOnly = true)
public class PaymentInstallmentOverlayService {

    public record InstallmentOverlay(ReceiptStatus status, String observation) {
    }

    private record ScopeKey(Long tripId, Long userId, Long studentId) {
    }

    private final PaymentSubmissionRepository paymentSubmissionRepository;
    private final PaymentAllocationPlanner paymentAllocationPlanner;

    public PaymentInstallmentOverlayService(
            PaymentSubmissionRepository paymentSubmissionRepository,
            PaymentAllocationPlanner paymentAllocationPlanner
    ) {
        this.paymentSubmissionRepository = paymentSubmissionRepository;
        this.paymentAllocationPlanner = paymentAllocationPlanner;
    }

    public Map<Long, InstallmentOverlay> resolveForInstallments(List<Installment> installments) {
        if (installments == null || installments.isEmpty()) {
            return Map.of();
        }

        Map<ScopeKey, List<Installment>> byScope = installments.stream()
                .collect(Collectors.groupingBy(
                        installment -> new ScopeKey(
                                installment.getTrip().getId(),
                                installment.getUser().getId(),
                                installment.getStudent() != null ? installment.getStudent().getId() : null
                        ),
                        LinkedHashMap::new,
                        Collectors.toList()
                ));

        Map<Long, InstallmentOverlay> overlays = new LinkedHashMap<>();
        for (Map.Entry<ScopeKey, List<Installment>> entry : byScope.entrySet()) {
            ScopeKey scopeKey = entry.getKey();
            List<Installment> scopeInstallments = entry.getValue().stream()
                    .sorted((left, right) -> Integer.compare(left.getInstallmentNumber(), right.getInstallmentNumber()))
                    .toList();

            List<PaymentSubmission> submissions = paymentSubmissionRepository.findByTripIdAndUserIdAndStudentIdOrderByNewest(
                    scopeKey.tripId(),
                    scopeKey.userId(),
                    scopeKey.studentId()
            );

            PaymentSubmission pendingSubmission = submissions.stream()
                    .filter(submission -> submission.getStatus() == PaymentSubmissionStatus.PENDING)
                    .findFirst()
                    .orElse(null);
            if (pendingSubmission != null) {
                applyOverlay(overlays, scopeInstallments, pendingSubmission, ReceiptStatus.PENDING, null);
                continue;
            }

            PaymentSubmission latestRejectedOnlySubmission = submissions.stream()
                    .filter(this::isRejectedOnlySubmission)
                    .findFirst()
                    .orElse(null);
            if (latestRejectedOnlySubmission != null) {
                String observation = latestRejectedOnlySubmission.getOutcomes().stream()
                        .filter(outcome -> outcome.getStatus() == PaymentOutcomeStatus.REJECTED)
                        .map(PaymentOutcome::getAdminObservation)
                        .filter(value -> value != null && !value.isBlank())
                        .findFirst()
                        .orElse(null);
                applyOverlay(overlays, scopeInstallments, latestRejectedOnlySubmission, ReceiptStatus.REJECTED, observation);
            }
        }

        return overlays;
    }

    private void applyOverlay(
            Map<Long, InstallmentOverlay> overlays,
            List<Installment> scopeInstallments,
            PaymentSubmission submission,
            ReceiptStatus status,
            String observation
    ) {
        List<Installment> payableInstallments = scopeInstallments.stream()
                .filter(installment -> paymentAllocationPlanner.getRemainingAmount(installment).signum() > 0)
                .toList();
        if (payableInstallments.isEmpty()) {
            return;
        }

        PaymentAllocationPlanner.PlanResult plan = paymentAllocationPlanner.plan(
                payableInstallments,
                submission.getReportedAmount(),
                submission.getPaymentCurrency(),
                submission.getExchangeRate()
        );

        for (PaymentAllocationPlanner.PlannedAllocation allocation : plan.allocations()) {
            overlays.put(allocation.installment().getId(), new InstallmentOverlay(status, observation));
        }
    }

    private boolean isRejectedOnlySubmission(PaymentSubmission submission) {
        if (submission.getStatus() != PaymentSubmissionStatus.RESOLVED) {
            return false;
        }

        boolean hasApproved = submission.getOutcomes().stream()
                .anyMatch(outcome -> outcome.getStatus() == PaymentOutcomeStatus.APPROVED);
        boolean hasRejected = submission.getOutcomes().stream()
                .anyMatch(outcome -> outcome.getStatus() == PaymentOutcomeStatus.REJECTED);
        return !hasApproved && hasRejected;
    }
}
