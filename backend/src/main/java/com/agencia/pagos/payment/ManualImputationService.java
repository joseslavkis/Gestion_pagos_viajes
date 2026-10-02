package com.agencia.pagos.payment;

import com.agencia.pagos.payment.dto.ManualImputationContextDTO;
import com.agencia.pagos.payment.dto.PaymentBatchInstallmentDTO;
import com.agencia.pagos.payment.dto.PaymentSubmissionDTO;
import com.agencia.pagos.payment.storage.PaymentAttachmentStorageService;
import com.agencia.pagos.shared.money.Currency;
import com.agencia.pagos.trip.Installment;
import com.agencia.pagos.trip.InstallmentRepository;
import com.agencia.pagos.trip.TripRepository;
import com.agencia.pagos.user.Role;
import com.agencia.pagos.user.Student;
import com.agencia.pagos.user.StudentNameFormatter;
import com.agencia.pagos.user.User;
import com.agencia.pagos.user.UserRepository;
import jakarta.persistence.EntityNotFoundException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.web.multipart.MultipartFile;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Imputación manual administrativa de pagos.
 *
 * <p>Regla de dominio: solo puede comenzar en la primera cuota con saldo
 * pendiente de una inscripción (trip+user+student) sin pagos pendientes de
 * aprobación; puede distribuirse secuencialmente hacia adelante, en ARS o USD,
 * con conversión backend según la fecha real del pago, auditándose como
 * ADMIN_MANUAL y ejecutándose atómicamente bajo locks Trip → Installments.
 */
@Service
@Transactional
public class ManualImputationService {

    private static final Logger LOGGER = LoggerFactory.getLogger(ManualImputationService.class);

    private final InstallmentRepository installmentRepository;
    private final TripRepository tripRepository;
    private final UserRepository userRepository;
    private final PaymentReceiptRepository paymentReceiptRepository;
    private final PaymentSubmissionRepository paymentSubmissionRepository;
    private final PaymentOutcomeRepository paymentOutcomeRepository;
    private final PaymentAllocationRepository paymentAllocationRepository;
    private final PaymentMoneyPolicy paymentMoneyPolicy;
    private final PaymentAllocationPlanner paymentAllocationPlanner;
    private final PaymentPreviewTokenService paymentPreviewTokenService;
    private final PaymentBusinessDatePolicy paymentBusinessDatePolicy;
    private final PaymentAttachmentStorageService paymentAttachmentStorageService;

    public ManualImputationService(
            InstallmentRepository installmentRepository,
            TripRepository tripRepository,
            UserRepository userRepository,
            PaymentReceiptRepository paymentReceiptRepository,
            PaymentSubmissionRepository paymentSubmissionRepository,
            PaymentOutcomeRepository paymentOutcomeRepository,
            PaymentAllocationRepository paymentAllocationRepository,
            PaymentMoneyPolicy paymentMoneyPolicy,
            PaymentAllocationPlanner paymentAllocationPlanner,
            PaymentPreviewTokenService paymentPreviewTokenService,
            PaymentBusinessDatePolicy paymentBusinessDatePolicy,
            PaymentAttachmentStorageService paymentAttachmentStorageService) {
        this.installmentRepository = installmentRepository;
        this.tripRepository = tripRepository;
        this.userRepository = userRepository;
        this.paymentReceiptRepository = paymentReceiptRepository;
        this.paymentSubmissionRepository = paymentSubmissionRepository;
        this.paymentOutcomeRepository = paymentOutcomeRepository;
        this.paymentAllocationRepository = paymentAllocationRepository;
        this.paymentMoneyPolicy = paymentMoneyPolicy;
        this.paymentAllocationPlanner = paymentAllocationPlanner;
        this.paymentPreviewTokenService = paymentPreviewTokenService;
        this.paymentBusinessDatePolicy = paymentBusinessDatePolicy;
        this.paymentAttachmentStorageService = paymentAttachmentStorageService;
    }

    @Transactional(readOnly = true)
    public ManualImputationContextDTO getContext(Long installmentId, String adminEmail) {
        getAdminByEmail(adminEmail);
        Installment anchor = installmentRepository.findByIdWithTripUserAndStudent(installmentId)
                .orElseThrow(() -> new EntityNotFoundException("Installment not found with id " + installmentId));
        Currency tripCurrency = anchor.getTrip().getCurrency();
        Long studentId = anchor.getStudent() != null ? anchor.getStudent().getId() : null;

        if (hasPendingReview(anchor.getTrip().getId(), anchor.getUser().getId(), studentId)) {
            return new ManualImputationContextDTO(
                    false,
                    anchor.getId(),
                    null,
                    null,
                    null,
                    totalRemaining(anchor),
                    tripCurrency,
                    true,
                    "Hay pagos pendientes de aprobación. Revisalos antes de realizar una nueva imputación.");
        }

        List<Installment> group = installmentRepository.findByTripIdAndUserIdAndStudentId(
                        anchor.getTrip().getId(), anchor.getUser().getId(), studentId)
                .stream().sorted(Comparator.comparing(Installment::getInstallmentNumber)).toList();
        List<Installment> payable = group.stream()
                .filter(i -> getRemaining(i).compareTo(BigDecimal.ZERO) > 0)
                .sorted(Comparator.comparing(Installment::getInstallmentNumber)).toList();

        if (payable.isEmpty()) {
            return new ManualImputationContextDTO(
                    false,
                    anchor.getId(),
                    null,
                    null,
                    BigDecimal.ZERO.setScale(PaymentMoneyPolicy.MONEY_SCALE),
                    BigDecimal.ZERO.setScale(PaymentMoneyPolicy.MONEY_SCALE),
                    tripCurrency,
                    false,
                    "Este viaje no tiene saldo pendiente.");
        }

        Installment first = payable.get(0);
        BigDecimal total = payable.stream()
                .map(this::getRemaining)
                .reduce(BigDecimal.ZERO, BigDecimal::add)
                .setScale(PaymentMoneyPolicy.MONEY_SCALE, RoundingMode.UNNECESSARY);

        if (!first.getId().equals(anchor.getId())) {
            BigDecimal anchorRemaining = getRemaining(anchor);
            if (anchorRemaining.compareTo(BigDecimal.ZERO) <= 0) {
                return new ManualImputationContextDTO(
                        false,
                        anchor.getId(),
                        first.getId(),
                        first.getInstallmentNumber(),
                        BigDecimal.ZERO.setScale(PaymentMoneyPolicy.MONEY_SCALE),
                        total,
                        tripCurrency,
                        false,
                        "Esta cuota ya está completamente pagada. Seleccioná la primera cuota pendiente de pago (#"
                                + first.getInstallmentNumber() + ").");
            }
            return new ManualImputationContextDTO(
                    false,
                    anchor.getId(),
                    first.getId(),
                    first.getInstallmentNumber(),
                    anchorRemaining,
                    total,
                    tripCurrency,
                    false,
                    "La imputación debe comenzar desde la cuota #" + first.getInstallmentNumber()
                            + ", que es la primera cuota pendiente de pago.");
        }

        return new ManualImputationContextDTO(
                true,
                anchor.getId(),
                first.getId(),
                first.getInstallmentNumber(),
                getRemaining(anchor),
                total,
                tripCurrency,
                false,
                null);
    }

    public PaymentSubmissionDTO execute(
            Long anchorInstallmentId,
            BigDecimal reportedAmount,
            LocalDate reportedPaymentDate,
            Currency paymentCurrency,
            String previewToken,
            String reason,
            List<MultipartFile> files,
            String adminEmail) {
        List<MultipartFile> attachments = files == null ? List.of()
                : files.stream().filter(f -> f != null && !f.isEmpty()).toList();
        if (attachments.size() > 1) {
            throw new IllegalArgumentException("Solo se admite un comprobante opcional por imputación manual");
        }
        String trimmedReason = reason == null ? null : reason.trim();
        if (trimmedReason != null && trimmedReason.length() > 500) {
            throw new IllegalArgumentException("El motivo no puede superar los 500 caracteres");
        }
        if (trimmedReason != null && trimmedReason.isEmpty()) {
            trimmedReason = null;
        }
        final String manualReason = trimmedReason;

        BigDecimal normalized;
        try {
            normalized = paymentMoneyPolicy.requirePositivePersistableMoney(reportedAmount, "reportedAmount");
        } catch (IllegalArgumentException e) {
            if (reportedAmount == null || safeCompare(reportedAmount) <= 0) {
                throw new IllegalArgumentException("El monto a imputar debe ser mayor a cero.", e);
            }
            throw new IllegalArgumentException("El monto ingresado supera el saldo pendiente del viaje.", e);
        }
        try {
            paymentBusinessDatePolicy.requireNotFuture(reportedPaymentDate);
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("La fecha de pago no puede ser futura.", e);
        }
        if (previewToken == null || previewToken.isBlank()) {
            throw ManualImputationException.previewMismatch();
        }
        if (paymentCurrency == null) {
            throw new IllegalArgumentException("La moneda del pago es obligatoria.");
        }

        User admin = getAdminByEmail(adminEmail);

        // Lock order Trip → Installments, igual que registerPayment y unassign.
        Long tripId = installmentRepository.findByIdWithTrip(anchorInstallmentId)
                .map(i -> i.getTrip().getId())
                .orElseThrow(() -> new EntityNotFoundException("Installment not found with id " + anchorInstallmentId));
        tripRepository.findByIdForUpdate(tripId)
                .orElseThrow(() -> new EntityNotFoundException("Trip not found with id " + tripId));

        Installment anchor = installmentRepository.findByIdWithTripUserAndStudent(anchorInstallmentId)
                .orElseThrow(() -> new EntityNotFoundException("Installment not found with id " + anchorInstallmentId));
        Long studentId = anchor.getStudent() != null ? anchor.getStudent().getId() : null;

        if (hasPendingReview(anchor.getTrip().getId(), anchor.getUser().getId(), studentId)) {
            throw ManualImputationException.pendingReview();
        }

        List<Installment> scoped = installmentRepository.findByTripIdAndUserIdAndStudentIdForUpdate(
                anchor.getTrip().getId(), anchor.getUser().getId(), studentId);
        List<Installment> payable = scoped.stream()
                .filter(i -> getRemaining(i).compareTo(BigDecimal.ZERO) > 0)
                .sorted(Comparator.comparing(Installment::getInstallmentNumber)).toList();
        if (payable.isEmpty()) {
            throw ManualImputationException.tripFullyPaid();
        }
        Installment first = payable.get(0);
        if (!first.getId().equals(anchor.getId())) {
            if (getRemaining(anchor).compareTo(BigDecimal.ZERO) <= 0) {
                throw ManualImputationException.alreadyPaid(first.getInstallmentNumber());
            }
            throw ManualImputationException.invalidAnchor(first.getInstallmentNumber());
        }

        PaymentPreviewTokenService.PreviewSnapshot snapshot = resolvePreviewSnapshot(
                previewToken, admin.getId(), anchorInstallmentId, paymentCurrency, normalized, reportedPaymentDate);

        ExchangeRateQuote quote = null;
        if (snapshot.quoteSellRate() != null) {
            BigDecimal rate;
            try {
                rate = paymentMoneyPolicy.requireProviderRate(snapshot.quoteSellRate());
            } catch (RuntimeException e) {
                throw ManualImputationException.quoteUnavailable();
            }
            quote = new ExchangeRateQuote(
                    rate,
                    snapshot.quoteRequestedDate() != null ? snapshot.quoteRequestedDate() : reportedPaymentDate,
                    snapshot.quoteEffectiveDate() != null ? snapshot.quoteEffectiveDate() : reportedPaymentDate,
                    snapshot.quoteSource() != null ? snapshot.quoteSource() : "unknown",
                    snapshot.quoteProvider() != null ? snapshot.quoteProvider() : "unknown",
                    snapshot.quoteProviderTimestamp());
        }
        // Misma moneda nunca usa cotización: si el viaje y el pago coinciden,
        // un token con rate es inconsistente.
        if (anchor.getTrip().getCurrency() == paymentCurrency && quote != null) {
            throw ManualImputationException.previewMismatch();
        }
        if (anchor.getTrip().getCurrency() != paymentCurrency && quote == null) {
            throw ManualImputationException.quoteUnavailable();
        }

        BigDecimal totalPending = payable.stream()
                .map(this::getRemaining)
                .reduce(BigDecimal.ZERO, BigDecimal::add)
                .setScale(PaymentMoneyPolicy.MONEY_SCALE, RoundingMode.UNNECESSARY);
        BigDecimal maxAllowed = paymentMoneyPolicy.maxAllowedPaymentAmount(
                totalPending, anchor.getTrip().getCurrency(), paymentCurrency,
                quote == null ? null : quote.sellRate());

        PaymentAllocationPlanner.PlanResult plan;
        try {
            plan = paymentAllocationPlanner.plan(
                    payable,
                    normalized,
                    paymentCurrency,
                    quote == null ? null : quote.sellRate(),
                    new PaymentAllocationPlanner.PaymentLimit(totalPending, maxAllowed));
        } catch (PaymentBalanceExceededException e) {
            // A preview válido que ahora excede implica consumo concurrente.
            throw ManualImputationException.staleBalance();
        } catch (IllegalArgumentException e) {
            String msg = e.getMessage() == null ? "" : e.getMessage();
            if (msg.contains("demasiado bajo")) {
                throw new IllegalArgumentException("El monto informado es demasiado bajo para imputarse.", e);
            }
            throw new IllegalArgumentException("El monto ingresado supera el saldo pendiente del viaje.", e);
        }
        paymentAllocationPlanner.assertConservation(plan);

        return persistManual(
                anchor, payable, plan, quote, paymentCurrency, reportedPaymentDate, manualReason,
                admin.getEmail(), attachments);
    }

    private PaymentPreviewTokenService.PreviewSnapshot resolvePreviewSnapshot(
            String previewToken,
            Long adminId,
            Long anchorInstallmentId,
            Currency paymentCurrency,
            BigDecimal normalizedAmount,
            LocalDate reportedPaymentDate) {
        PaymentPreviewTokenService.TokenValidation validation =
                paymentPreviewTokenService.validateToken(previewToken, adminId);
        if (validation.status() == PaymentPreviewTokenService.TokenValidationStatus.EXPIRED) {
            throw ManualImputationException.previewExpired();
        }
        if (validation.status() != PaymentPreviewTokenService.TokenValidationStatus.VALID
                || validation.snapshot().isEmpty()) {
            throw ManualImputationException.previewMismatch();
        }
        PaymentPreviewTokenService.PreviewSnapshot snapshot = validation.snapshot().get();
        if (!snapshot.anchorInstallmentId().equals(anchorInstallmentId)
                || snapshot.paymentCurrency() != paymentCurrency
                || !snapshot.reportedPaymentDate().equals(reportedPaymentDate)
                || snapshot.reportedAmount().compareTo(normalizedAmount) != 0) {
            throw ManualImputationException.previewMismatch();
        }
        if (!PaymentPreviewTokenService.CURRENT_CALCULATION_VERSION.equals(snapshot.calculationVersion())) {
            throw ManualImputationException.previewMismatch();
        }
        return snapshot;
    }

    private PaymentSubmissionDTO persistManual(
            Installment anchor,
            List<Installment> payable,
            PaymentAllocationPlanner.PlanResult plan,
            ExchangeRateQuote quote,
            Currency paymentCurrency,
            LocalDate reportedPaymentDate,
            String manualReason,
            String adminEmail,
            List<MultipartFile> files) {
        PaymentSubmission submission = new PaymentSubmission();
        submission.setTrip(anchor.getTrip());
        submission.setUser(anchor.getUser());
        submission.setStudent(anchor.getStudent());
        submission.setAnchorInstallment(anchor);
        submission.setBankAccount(null);
        submission.setReportedAmount(plan.reportedAmount());
        submission.setPaymentCurrency(paymentCurrency);
        submission.setExchangeRate(plan.exchangeRate());
        submission.setExchangeRateScale(quote == null ? null : quote.sellRate().scale());
        submission.setAmountInTripCurrency(plan.amountInTripCurrency());
        submission.setReportedPaymentDate(reportedPaymentDate);
        if (quote != null) {
            submission.setExchangeRateRequestedDate(quote.requestedDate());
            submission.setExchangeRateEffectiveDate(quote.effectiveDate());
            submission.setExchangeRateSource(quote.source());
            submission.setExchangeRateProvider(quote.provider());
            submission.setExchangeRateProviderTimestamp(quote.providerTimestamp());
        }
        submission.setCalculationVersion(PaymentPreviewTokenService.CURRENT_CALCULATION_VERSION);
        submission.setPaymentMethod(null);
        submission.setStatus(PaymentSubmissionStatus.RESOLVED);
        submission.setSource(PaymentSubmissionSource.ADMIN_MANUAL);
        submission.setManualReason(manualReason);

        List<String> written = new ArrayList<>();
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCompletion(int status) {
                    if (status != STATUS_COMMITTED) {
                        cleanupWritten(written);
                    }
                }
            });
        }
        try {
            for (MultipartFile file : files) {
                String key = paymentAttachmentStorageService.storeReceipt(
                        file,
                        anchor.getTrip().getId(),
                        anchor.getUser().getId(),
                        anchor.getStudent() != null ? anchor.getStudent().getId() : null);
                if (key == null || key.isEmpty()) {
                    throw new IllegalStateException("No se pudo guardar el comprobante");
                }
                written.add(key);
                submission.addAttachment(key);
            }
            submission.setFileKey(written.isEmpty() ? "" : written.get(0));
            PaymentSubmission saved = paymentSubmissionRepository.save(submission);

            PaymentOutcome outcome = new PaymentOutcome();
            outcome.setSubmission(saved);
            outcome.setStatus(PaymentOutcomeStatus.APPROVED);
            outcome.setReportedAmount(plan.reportedAmount());
            outcome.setAmountInTripCurrency(plan.amountInTripCurrency());
            outcome.setAdminObservation(manualReason);
            outcome.setResolvedByEmail(adminEmail);
            PaymentOutcome savedOutcome = paymentOutcomeRepository.save(outcome);
            saved.getOutcomes().add(savedOutcome);

            List<PaymentAllocation> entities = new ArrayList<>();
            for (PaymentAllocationPlanner.PlannedAllocation allocation : plan.allocations()) {
                Installment installment = allocation.installment();
                installment.setPaidAmount(safe(installment.getPaidAmount()).add(allocation.amountInTripCurrency()));
                PaymentAllocation entity = new PaymentAllocation();
                entity.setOutcome(savedOutcome);
                entity.setInstallment(installment);
                entity.setAllocationOrder(allocation.allocationOrder());
                entity.setReportedAmount(allocation.reportedAmount());
                entity.setAmountInTripCurrency(allocation.amountInTripCurrency());
                entities.add(entity);
            }
            installmentRepository.saveAll(payable);
            paymentAllocationRepository.saveAll(entities);
            savedOutcome.getAllocations().addAll(entities);

            return toManualDTO(saved, savedOutcome, plan);
        } catch (RuntimeException e) {
            cleanupWritten(written);
            throw e;
        }
    }

    private PaymentSubmissionDTO toManualDTO(
            PaymentSubmission submission,
            PaymentOutcome outcome,
            PaymentAllocationPlanner.PlanResult plan) {
        List<PaymentBatchInstallmentDTO> installments = plan.allocations().stream()
                .map(a -> new PaymentBatchInstallmentDTO(
                        null,
                        a.installment().getId(),
                        a.installment().getInstallmentNumber(),
                        a.installment().getDueDate(),
                        a.installment().getTotalDue(),
                        a.installment().getPaidAmount(),
                        a.remainingAmount(),
                        a.reportedAmount(),
                        a.amountInTripCurrency(),
                        ReceiptStatus.APPROVED))
                .toList();
        BigDecimal zero = BigDecimal.ZERO.setScale(PaymentMoneyPolicy.MONEY_SCALE);
        return new PaymentSubmissionDTO(
                submission.getId(),
                PaymentHistoryStatus.APPROVED,
                submission.getReportedAmount(),
                outcome.getReportedAmount(),
                zero,
                submission.getPaymentCurrency(),
                submission.getExchangeRate(),
                submission.getAmountInTripCurrency(),
                outcome.getAmountInTripCurrency(),
                submission.getReportedPaymentDate(),
                submission.getExchangeRateRequestedDate(),
                submission.getExchangeRateEffectiveDate(),
                submission.getExchangeRateSource(),
                submission.getExchangeRateProvider(),
                submission.getExchangeRateProviderTimestamp(),
                submission.getCalculationVersion(),
                submission.getPaymentMethod(),
                submission.getFileKey() == null ? "" : resolveRef(submission.getFileKey()),
                outcome.getAdminObservation(),
                null,
                null,
                null,
                submission.getTrip().getId(),
                submission.getTrip().getName(),
                submission.getTrip().getCurrency(),
                submission.getStudent() != null ? submission.getStudent().getId() : null,
                StudentNameFormatter.displayName(submission.getStudent()),
                submission.getStudent() != null ? submission.getStudent().getDni() : null,
                installments,
                attachmentRefs(submission),
                PaymentSubmissionSource.ADMIN_MANUAL,
                submission.getManualReason());
    }

    private User getAdminByEmail(String email) {
        User user = userRepository.findByEmail(email)
                .orElseThrow(() -> new EntityNotFoundException("User not found with email " + email));
        if (user.getRole() != Role.ADMIN) {
            throw new org.springframework.security.access.AccessDeniedException(
                    "No tiene permisos para realizar una imputación manual.");
        }
        return user;
    }

    private boolean hasPendingReview(Long tripId, Long userId, Long studentId) {
        return paymentReceiptRepository.existsByTripIdAndUserIdAndStudentIdAndStatus(
                        tripId, userId, studentId, ReceiptStatus.PENDING)
                || paymentSubmissionRepository.existsByTripIdAndUserIdAndStudentIdAndStatus(
                        tripId, userId, studentId, PaymentSubmissionStatus.PENDING);
    }

    private BigDecimal getRemaining(Installment installment) {
        return paymentAllocationPlanner.getRemainingAmount(installment);
    }

    private BigDecimal totalRemaining(Installment anchor) {
        Long studentId = anchor.getStudent() != null ? anchor.getStudent().getId() : null;
        List<Installment> group = installmentRepository.findByTripIdAndUserIdAndStudentId(
                anchor.getTrip().getId(), anchor.getUser().getId(), studentId);
        return group.stream()
                .map(this::getRemaining)
                .reduce(BigDecimal.ZERO, BigDecimal::add)
                .setScale(PaymentMoneyPolicy.MONEY_SCALE, RoundingMode.UNNECESSARY);
    }

    private BigDecimal safe(BigDecimal v) {
        return v == null ? BigDecimal.ZERO : v;
    }

    private int safeCompare(BigDecimal v) {
        try {
            return paymentMoneyPolicy.requireMoney(v, "reportedAmount").signum();
        } catch (RuntimeException e) {
            return -1;
        }
    }

    private void cleanupWritten(List<String> written) {
        for (var it = written.iterator(); it.hasNext();) {
            String key = it.next();
            try {
                if (paymentAttachmentStorageService.deleteReceipt(key)) {
                    it.remove();
                } else {
                    LOGGER.warn("Could not delete manual imputation attachment '{}'", key);
                }
            } catch (RuntimeException e) {
                LOGGER.warn("Could not delete manual imputation attachment '{}'", key, e);
            }
        }
    }

    private String resolveRef(String stored) {
        if (stored == null || stored.isBlank()) {
            return "";
        }
        try {
            String ref = paymentAttachmentStorageService.resolveFileReference(stored);
            return ref == null ? "" : ref;
        } catch (RuntimeException e) {
            return "";
        }
    }

    private List<String> attachmentRefs(PaymentSubmission submission) {
        if (submission.getAttachments() != null && !submission.getAttachments().isEmpty()) {
            List<String> refs = new ArrayList<>();
            for (PaymentSubmissionAttachment a : submission.getAttachments()) {
                if (a != null && a.getFileKey() != null && !a.getFileKey().isBlank()) {
                    String ref = resolveRef(a.getFileKey());
                    if (ref != null && !ref.isBlank()) {
                        refs.add(ref);
                    }
                }
            }
            return refs;
        }
        String ref = resolveRef(submission.getFileKey());
        return ref.isBlank() ? List.of() : List.of(ref);
    }

    // Para tests de elegibilidad sin HTTP.
    boolean isFirstPayableForTest(Long anchorId) {
        Installment anchor = installmentRepository.findByIdWithTripUserAndStudent(anchorId)
                .orElseThrow(() -> new EntityNotFoundException("Installment not found"));
        Long studentId = anchor.getStudent() != null ? anchor.getStudent().getId() : null;
        List<Installment> payable = installmentRepository
                .findByTripIdAndUserIdAndStudentId(anchor.getTrip().getId(), anchor.getUser().getId(), studentId)
                .stream().filter(i -> getRemaining(i).compareTo(BigDecimal.ZERO) > 0)
                .sorted(Comparator.comparing(Installment::getInstallmentNumber)).toList();
        return !payable.isEmpty() && payable.get(0).getId().equals(anchorId);
    }
}
