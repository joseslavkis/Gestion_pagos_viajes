package com.agencia.pagos.payment;

import com.agencia.pagos.payment.dto.PaymentPreviewRequestDTO;
import com.agencia.pagos.payment.dto.RegisterPaymentDTO;
import com.agencia.pagos.payment.dto.ReviewPaymentDTO;
import com.agencia.pagos.payment.dto.PaymentBatchInstallmentDTO;
import com.agencia.pagos.payment.dto.PaymentBatchPreviewDTO;
import com.agencia.pagos.payment.dto.PendingPaymentReviewDTO;
import com.agencia.pagos.payment.dto.PaymentInstallmentHistoryDTO;
import com.agencia.pagos.payment.dto.PaymentSubmissionDTO;
import com.agencia.pagos.payment.dto.PaymentCalculationIntent;
import com.agencia.pagos.payment.dto.PaymentCalculationRequestDTO;
import com.agencia.pagos.payment.dto.PaymentCalculationResponseDTO;
import com.agencia.pagos.payment.dto.PaymentCalculationStatus;
import com.agencia.pagos.user.dto.UserInstallmentDTO;
import com.agencia.pagos.payment.BankAccount;
import com.agencia.pagos.shared.money.Currency;
import com.agencia.pagos.trip.Installment;
import com.agencia.pagos.trip.InstallmentStatus;
import com.agencia.pagos.payment.PaymentAllocation;
import com.agencia.pagos.payment.PaymentBatch;
import com.agencia.pagos.payment.PaymentHistoryStatus;
import com.agencia.pagos.payment.PaymentMethod;
import com.agencia.pagos.payment.PaymentOutcome;
import com.agencia.pagos.payment.PaymentOutcomeStatus;
import com.agencia.pagos.payment.PaymentReceipt;
import com.agencia.pagos.payment.PaymentSubmission;
import com.agencia.pagos.payment.PaymentSubmissionStatus;
import com.agencia.pagos.payment.ReceiptStatus;
import com.agencia.pagos.user.Role;
import com.agencia.pagos.user.Student;
import com.agencia.pagos.user.User;
import com.agencia.pagos.payment.BankAccountRepository;
import com.agencia.pagos.trip.InstallmentRepository;
import com.agencia.pagos.payment.PaymentAllocationRepository;
import com.agencia.pagos.payment.PaymentBatchRepository;
import com.agencia.pagos.payment.PaymentOutcomeRepository;
import com.agencia.pagos.payment.PaymentReceiptRepository;
import com.agencia.pagos.payment.PaymentSubmissionRepository;
import com.agencia.pagos.trip.TripRepository;
import com.agencia.pagos.user.UserRepository;
import com.agencia.pagos.payment.storage.PaymentAttachmentStorageService;
import com.agencia.pagos.trip.InstallmentStatusResolver;
import com.agencia.pagos.trip.InstallmentUiStatusResolver;
import com.agencia.pagos.trip.InstallmentUiStatus;
import com.agencia.pagos.user.StudentNameFormatter;
import jakarta.persistence.EntityNotFoundException;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

@Service
@Transactional
public class PaymentService {

    private static final String LEGACY_RECONCILIATION_OBSERVATION =
            "Aprobación conciliada con los valores históricos v1 persistidos";

    private record UserInstallmentGroupKey(Long tripId, Long studentId) {
    }

    private record PaymentScopeSelection(Installment anchorInstallment, List<Installment> installments) {
    }

    private record ValidatedPreview(ExchangeRateQuote quote, PaymentCalculationIntent intent) {
    }

    private record LegacySubmissionKey(Long batchId, Long receiptId) {
    }

    private final PaymentReceiptRepository paymentReceiptRepository;
    private final PaymentBatchRepository paymentBatchRepository;
    private final PaymentSubmissionRepository paymentSubmissionRepository;
    private final PaymentOutcomeRepository paymentOutcomeRepository;
    private final PaymentAllocationRepository paymentAllocationRepository;
    private final InstallmentRepository installmentRepository;
    private final TripRepository tripRepository;
    private final UserRepository userRepository;
    private final BankAccountRepository bankAccountRepository;
    private final InstallmentStatusResolver installmentStatusResolver;
    private final InstallmentUiStatusResolver installmentUiStatusResolver;
    private final ExchangeRateQuoteProvider exchangeRateQuoteProvider;
    private final PaymentMoneyPolicy paymentMoneyPolicy;
    private final PaymentAllocationPlanner paymentAllocationPlanner;
    private final PaymentInstallmentOverlayService paymentInstallmentOverlayService;
    private final PaymentAttachmentStorageService paymentAttachmentStorageService;
    private final PaymentPreviewTokenService paymentPreviewTokenService;
    private final PaymentBusinessDatePolicy paymentBusinessDatePolicy;

    @Autowired
    public PaymentService(
            PaymentReceiptRepository paymentReceiptRepository,
            PaymentBatchRepository paymentBatchRepository,
            PaymentSubmissionRepository paymentSubmissionRepository,
            PaymentOutcomeRepository paymentOutcomeRepository,
            PaymentAllocationRepository paymentAllocationRepository,
            InstallmentRepository installmentRepository,
            TripRepository tripRepository,
            UserRepository userRepository,
            BankAccountRepository bankAccountRepository,
            InstallmentStatusResolver installmentStatusResolver,
            InstallmentUiStatusResolver installmentUiStatusResolver,
            ExchangeRateQuoteProvider exchangeRateQuoteProvider,
            PaymentMoneyPolicy paymentMoneyPolicy,
            PaymentAllocationPlanner paymentAllocationPlanner,
            PaymentInstallmentOverlayService paymentInstallmentOverlayService,
            PaymentAttachmentStorageService paymentAttachmentStorageService,
            PaymentPreviewTokenService paymentPreviewTokenService,
            PaymentBusinessDatePolicy paymentBusinessDatePolicy
    ) {
        this.paymentReceiptRepository = paymentReceiptRepository;
        this.paymentBatchRepository = paymentBatchRepository;
        this.paymentSubmissionRepository = paymentSubmissionRepository;
        this.paymentOutcomeRepository = paymentOutcomeRepository;
        this.paymentAllocationRepository = paymentAllocationRepository;
        this.installmentRepository = installmentRepository;
        this.tripRepository = tripRepository;
        this.userRepository = userRepository;
        this.bankAccountRepository = bankAccountRepository;
        this.installmentStatusResolver = installmentStatusResolver;
        this.installmentUiStatusResolver = installmentUiStatusResolver;
        this.exchangeRateQuoteProvider = exchangeRateQuoteProvider;
        this.paymentMoneyPolicy = paymentMoneyPolicy;
        this.paymentAllocationPlanner = paymentAllocationPlanner;
        this.paymentInstallmentOverlayService = paymentInstallmentOverlayService;
        this.paymentAttachmentStorageService = paymentAttachmentStorageService;
        this.paymentPreviewTokenService = paymentPreviewTokenService;
        this.paymentBusinessDatePolicy = paymentBusinessDatePolicy;
    }

    @Transactional(readOnly = true)
    public PaymentBatchPreviewDTO previewPayment(PaymentPreviewRequestDTO dto, String email) {
        BigDecimal reportedAmount = paymentMoneyPolicy.requirePositiveMoney(dto.reportedAmount(), "reportedAmount");
        paymentBusinessDatePolicy.requireNotFuture(dto.reportedPaymentDate());
        User user = getUserByEmail(email);
        PaymentScopeSelection selection = resolvePaymentScope(user, dto.anchorInstallmentId(), false);
        ExchangeRateQuote quote = fetchLiveQuoteForPreview(
                selection.anchorInstallment().getTrip().getCurrency(),
                dto.paymentCurrency(),
                dto.reportedPaymentDate()
        );
        PaymentAllocationPlanner.PlanResult plan = paymentAllocationPlanner.plan(
                selection.installments(),
                reportedAmount,
                dto.paymentCurrency(),
                quote == null ? null : quote.sellRate()
        );
        paymentAllocationPlanner.assertConservation(plan);
        PaymentPreviewTokenService.PreviewSnapshot snapshot = buildPreviewSnapshot(
                user,
                selection.anchorInstallment().getId(),
                dto.paymentCurrency(),
                reportedAmount,
                dto.reportedPaymentDate(),
                quote,
                PaymentCalculationIntent.MANUAL
        );
        String previewToken = paymentPreviewTokenService.issueToken(snapshot);
        return toPreviewDTO(selection.anchorInstallment(), dto.reportedPaymentDate(), plan, quote, previewToken);
    }

    @Transactional(readOnly = true)
    public PaymentCalculationResponseDTO calculatePayment(PaymentCalculationRequestDTO dto, String email) {
        paymentBusinessDatePolicy.requireNotFuture(dto.reportedPaymentDate());
        User user = getUserByEmail(email);
        PaymentScopeSelection selection = resolvePaymentScope(user, dto.anchorInstallmentId(), false);
        Currency tripCurrency = selection.anchorInstallment().getTrip().getCurrency();
        BigDecimal remainingAmount = getRemainingAmount(selection.anchorInstallment());
        BigDecimal totalPendingAmountInTripCurrency = selection.installments().stream()
                .map(this::getRemainingAmount)
                .reduce(BigDecimal.ZERO, BigDecimal::add)
                .setScale(PaymentMoneyPolicy.MONEY_SCALE, RoundingMode.UNNECESSARY);

        PaymentPreviewTokenService.TokenValidation tokenValidation = validateCalculationToken(dto, user);
        if (tokenValidation.status() == PaymentPreviewTokenService.TokenValidationStatus.EXPIRED) {
            return calculationState(
                    PaymentCalculationStatus.EXPIRED,
                    dto,
                    tripCurrency,
                    remainingAmount,
                    totalPendingAmountInTripCurrency,
                    null,
                    null,
                    "La previsualización venció. Volvé a calcular el pago."
            );
        }

        ExchangeRateQuote quote;
        try {
            quote = fetchLiveQuoteForPreview(tripCurrency, dto.paymentCurrency(), dto.reportedPaymentDate());
        } catch (IllegalStateException providerFailure) {
            return calculationState(
                    PaymentCalculationStatus.QUOTE_UNAVAILABLE,
                    dto,
                    tripCurrency,
                    remainingAmount,
                    totalPendingAmountInTripCurrency,
                    null,
                    null,
                    providerFailure.getMessage()
            );
        }

        BigDecimal exchangeRate = quote == null ? null : quote.sellRate();
        BigDecimal calculationBalance = dto.intent() == PaymentCalculationIntent.REMAINING
                ? remainingAmount
                : totalPendingAmountInTripCurrency;
        BigDecimal maxAllowedAmount = paymentMoneyPolicy.maxAllowedPaymentAmount(
                calculationBalance, tripCurrency, dto.paymentCurrency(), exchangeRate);
        PaymentAllocationPlanner.PaymentLimit paymentLimit = new PaymentAllocationPlanner.PaymentLimit(
                calculationBalance, maxAllowedAmount);
        BigDecimal reportedAmount = resolveCalculationAmount(
                dto, remainingAmount, tripCurrency, exchangeRate, maxAllowedAmount);
        if (maxAllowedAmount.signum() <= 0 || reportedAmount.signum() <= 0) {
            return calculationState(
                    PaymentCalculationStatus.UNPAYABLE,
                    dto,
                    tripCurrency,
                    remainingAmount,
                    totalPendingAmountInTripCurrency,
                    maxAllowedAmount,
                    quote,
                    "El saldo no puede imputarse en la moneda elegida con una unidad mínima de un centavo."
            );
        }

        PaymentAllocationPlanner.PlanResult plan;
        try {
            plan = paymentAllocationPlanner.plan(
                    selection.installments(), reportedAmount, dto.paymentCurrency(), exchangeRate, paymentLimit);
        } catch (PaymentBalanceExceededException exceeded) {
            BigDecimal amountInTripCurrency = paymentMoneyPolicy.convertPaymentToTripCurrency(
                    reportedAmount, tripCurrency, dto.paymentCurrency(), exchangeRate);
            return new PaymentCalculationResponseDTO(
                    PaymentCalculationStatus.AMOUNT_EXCEEDS_BALANCE,
                    dto.intent(),
                    dto.anchorInstallmentId(),
                    tripCurrency,
                    dto.paymentCurrency(),
                    reportedAmount,
                    amountInTripCurrency,
                    remainingAmount,
                    totalPendingAmountInTripCurrency,
                    exceeded.maxAllowedAmount(),
                    exceeded.residualInTripCurrency(),
                    exchangeRate,
                    dto.reportedPaymentDate(),
                    quote == null ? null : quote.requestedDate(),
                    quote == null ? null : quote.effectiveDate(),
                    quote == null ? null : quote.source(),
                    quote == null ? null : quote.provider(),
                    quote == null ? null : quote.providerTimestamp(),
                    PaymentPreviewTokenService.CURRENT_CALCULATION_VERSION,
                    null,
                    List.of(),
                    exceeded.getMessage()
            );
        }
        paymentAllocationPlanner.assertConservation(plan);
        PaymentPreviewTokenService.PreviewSnapshot snapshot = buildPreviewSnapshot(
                user,
                dto.anchorInstallmentId(),
                dto.paymentCurrency(),
                plan.reportedAmount(),
                dto.reportedPaymentDate(),
                quote,
                dto.intent()
        );
        String previewToken = paymentPreviewTokenService.issueToken(snapshot);
        BigDecimal residual = calculationBalance.subtract(plan.amountInTripCurrency())
                .setScale(PaymentMoneyPolicy.MONEY_SCALE, RoundingMode.UNNECESSARY);
        return new PaymentCalculationResponseDTO(
                PaymentCalculationStatus.READY,
                dto.intent(),
                dto.anchorInstallmentId(),
                tripCurrency,
                dto.paymentCurrency(),
                plan.reportedAmount(),
                plan.amountInTripCurrency(),
                remainingAmount,
                totalPendingAmountInTripCurrency,
                maxAllowedAmount,
                residual,
                plan.exchangeRate(),
                dto.reportedPaymentDate(),
                quote == null ? null : quote.requestedDate(),
                quote == null ? null : quote.effectiveDate(),
                quote == null ? null : quote.source(),
                quote == null ? null : quote.provider(),
                quote == null ? null : quote.providerTimestamp(),
                PaymentPreviewTokenService.CURRENT_CALCULATION_VERSION,
                previewToken,
                toInstallmentDTOs(plan.allocations(), null),
                null
        );
    }

    private PaymentPreviewTokenService.TokenValidation validateCalculationToken(
            PaymentCalculationRequestDTO dto,
            User user
    ) {
        if (dto.previewToken() == null || dto.previewToken().isBlank()) {
            return new PaymentPreviewTokenService.TokenValidation(
                    PaymentPreviewTokenService.TokenValidationStatus.VALID,
                    java.util.Optional.empty()
            );
        }
        PaymentPreviewTokenService.TokenValidation validation = paymentPreviewTokenService
                .validateToken(dto.previewToken(), user.getId());
        if (validation.status() == PaymentPreviewTokenService.TokenValidationStatus.INVALID) {
            throw new IllegalArgumentException("La previsualización del pago es inválida.");
        }
        validation.snapshot().ifPresent(snapshot -> {
            if (!snapshot.anchorInstallmentId().equals(dto.anchorInstallmentId())
                    || snapshot.paymentCurrency() != dto.paymentCurrency()
                    || snapshot.intent() != dto.intent()
                    || !snapshot.reportedPaymentDate().equals(dto.reportedPaymentDate())) {
                throw new IllegalArgumentException("La previsualización no corresponde al contexto de cálculo.");
            }
            if (dto.intent() == PaymentCalculationIntent.MANUAL
                    && snapshot.reportedAmount().compareTo(dto.reportedAmount()) != 0) {
                throw new IllegalArgumentException("La previsualización no corresponde al monto calculado.");
            }
        });
        return validation;
    }

    private BigDecimal resolveCalculationAmount(
            PaymentCalculationRequestDTO dto,
            BigDecimal remainingAmount,
            Currency tripCurrency,
            BigDecimal exchangeRate,
            BigDecimal maxAllowedAmount
    ) {
        if (dto.intent() == PaymentCalculationIntent.MANUAL) {
            return paymentMoneyPolicy.requirePositiveMoney(dto.reportedAmount(), "reportedAmount");
        }
        if (dto.reportedAmount() != null) {
            throw new IllegalArgumentException("reportedAmount must be omitted for REMAINING intent");
        }
        BigDecimal convertedBalance = paymentMoneyPolicy.convertTripToPaymentCurrency(
                remainingAmount, tripCurrency, dto.paymentCurrency(), exchangeRate);
        return convertedBalance.min(maxAllowedAmount);
    }

    private PaymentCalculationResponseDTO calculationState(
            PaymentCalculationStatus status,
            PaymentCalculationRequestDTO dto,
            Currency tripCurrency,
            BigDecimal remainingAmount,
            BigDecimal totalPendingAmountInTripCurrency,
            BigDecimal maxAllowedAmount,
            ExchangeRateQuote quote,
            String message
    ) {
        return new PaymentCalculationResponseDTO(
                status,
                dto.intent(),
                dto.anchorInstallmentId(),
                tripCurrency,
                dto.paymentCurrency(),
                null,
                null,
                remainingAmount,
                totalPendingAmountInTripCurrency,
                maxAllowedAmount,
                remainingAmount,
                quote == null ? null : quote.sellRate(),
                dto.reportedPaymentDate(),
                quote == null ? null : quote.requestedDate(),
                quote == null ? null : quote.effectiveDate(),
                quote == null ? null : quote.source(),
                quote == null ? null : quote.provider(),
                quote == null ? null : quote.providerTimestamp(),
                PaymentPreviewTokenService.CURRENT_CALCULATION_VERSION,
                null,
                List.of(),
                message
        );
    }

    public PaymentSubmissionDTO registerPayment(
            Long anchorInstallmentId,
            BigDecimal reportedAmount,
            LocalDate reportedPaymentDate,
            Currency paymentCurrency,
            PaymentMethod paymentMethod,
            Long bankAccountId,
            MultipartFile file,
            String previewToken,
            String email
    ) {
        BigDecimal normalizedReportedAmount = paymentMoneyPolicy.requirePositiveMoney(reportedAmount, "reportedAmount");
        paymentBusinessDatePolicy.requireNotFuture(reportedPaymentDate);
        User user = getUserByEmail(email);
        // [DEADLOCK FIX] Establish Trip → Installments lock order to match
        // TripService.unassignStudentByDni (which also locks Trip before Installments). The
        // PaymentSubmission row needs a FK share-lock on Trip on INSERT, so locking Installments
        // first would create a Trip ↔ Installments cycle with concurrent unassigns. The initial
        // non-locking lookup is purely for trip-ID discovery; ownership/anchoring validation
        // remains inside resolvePaymentScope (which re-loads the anchor with user + student
        // associations and throws AccessDeniedException if it doesn't belong to the caller).
        lockTripForPaymentRegistration(anchorInstallmentId);
        PaymentScopeSelection selection = resolvePaymentScope(user, anchorInstallmentId, true);
        BankAccount bankAccount = resolveBankAccount(bankAccountId, paymentCurrency);
        boolean requiresQuote = requiresExchangeRate(
                selection.anchorInstallment().getTrip().getCurrency(),
                paymentCurrency
        );

        if (requiresQuote && (previewToken == null || previewToken.isBlank())) {
            throw new IllegalArgumentException(
                    "La previsualización del pago es obligatoria o venció. Volvé a calcularla antes de enviar el comprobante."
            );
        }

        ExchangeRateQuote quote = null;
        PaymentCalculationIntent intent = PaymentCalculationIntent.MANUAL;
        if (previewToken != null && !previewToken.isBlank()) {
            ValidatedPreview preview = applyPreviewToken(
                    user,
                    anchorInstallmentId,
                    paymentCurrency,
                    normalizedReportedAmount,
                    reportedPaymentDate,
                    previewToken
            );
            quote = preview.quote();
            intent = preview.intent();
        }

        return persistPaymentSubmission(
                selection,
                bankAccount,
                normalizedReportedAmount,
                reportedPaymentDate,
                paymentCurrency,
                paymentMethod,
                file,
                quote,
                intent
        );
    }

    private PaymentSubmissionDTO persistPaymentSubmission(
            PaymentScopeSelection selection,
            BankAccount bankAccount,
            BigDecimal reportedAmount,
            LocalDate reportedPaymentDate,
            Currency paymentCurrency,
            PaymentMethod paymentMethod,
            MultipartFile file,
            ExchangeRateQuote quote,
            PaymentCalculationIntent intent
    ) {
        BigDecimal normalizedReportedAmount = paymentMoneyPolicy.requirePositiveMoney(reportedAmount, "reportedAmount");
        BigDecimal totalPendingAmountInTripCurrency = selection.installments().stream()
                .map(this::getRemainingAmount)
                .reduce(BigDecimal.ZERO, BigDecimal::add)
                .setScale(PaymentMoneyPolicy.MONEY_SCALE, RoundingMode.UNNECESSARY);
        BigDecimal balanceLimit = intent == PaymentCalculationIntent.REMAINING
                ? getRemainingAmount(selection.anchorInstallment())
                : totalPendingAmountInTripCurrency;
        BigDecimal maxAllowedAmount = paymentMoneyPolicy.maxAllowedPaymentAmount(
                balanceLimit,
                selection.anchorInstallment().getTrip().getCurrency(),
                paymentCurrency,
                quote == null ? null : quote.sellRate()
        );
        PaymentAllocationPlanner.PlanResult plan = paymentAllocationPlanner.plan(
                selection.installments(),
                normalizedReportedAmount,
                paymentCurrency,
                quote == null ? null : quote.sellRate(),
                new PaymentAllocationPlanner.PaymentLimit(balanceLimit, maxAllowedAmount)
        );
        paymentAllocationPlanner.assertConservation(plan);

        PaymentSubmission submission = new PaymentSubmission();
        submission.setTrip(selection.anchorInstallment().getTrip());
        submission.setUser(selection.anchorInstallment().getUser());
        submission.setStudent(selection.anchorInstallment().getStudent());
        submission.setAnchorInstallment(selection.anchorInstallment());
        submission.setBankAccount(bankAccount);
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
        submission.setPaymentMethod(paymentMethod);
        submission.setStatus(PaymentSubmissionStatus.PENDING);
        submission.setFileKey(paymentAttachmentStorageService.storeReceipt(
                file,
                selection.anchorInstallment().getTrip().getId(),
                selection.anchorInstallment().getUser().getId(),
                selection.anchorInstallment().getStudent() != null
                        ? selection.anchorInstallment().getStudent().getId()
                        : null
        ));

        PaymentSubmission saved = paymentSubmissionRepository.save(submission);
        return toSubmissionDTO(saved, toInstallmentDTOs(plan.allocations(), null));
    }

    public PaymentSubmissionDTO registerPayment(
            Long anchorInstallmentId,
            BigDecimal reportedAmount,
            LocalDate reportedPaymentDate,
            Currency paymentCurrency,
            PaymentMethod paymentMethod,
            Long bankAccountId,
            MultipartFile file,
            String email
    ) {
        return registerPayment(
                anchorInstallmentId,
                reportedAmount,
                reportedPaymentDate,
                paymentCurrency,
                paymentMethod,
                bankAccountId,
                file,
                null,
                email
        );
    }

    public PaymentSubmissionDTO registerPayment(RegisterPaymentDTO dto, String email) {
        return registerPayment(
                dto.anchorInstallmentId(),
                dto.reportedAmount(),
                dto.reportedPaymentDate(),
                dto.paymentCurrency(),
                dto.paymentMethod(),
                dto.bankAccountId(),
                null,
                dto.previewToken(),
                email
        );
    }

    public PaymentSubmissionDTO reviewPayment(Long submissionId, ReviewPaymentDTO dto, String reviewerEmail) {
        paymentSubmissionRepository.findByIdForUpdate(submissionId)
                .orElseThrow(() -> new EntityNotFoundException("PaymentSubmission not found with id " + submissionId));
        PaymentSubmission submission = paymentSubmissionRepository.findByIdWithContext(submissionId)
                .orElseThrow(() -> new EntityNotFoundException("PaymentSubmission not found with id " + submissionId));

        if (submission.getStatus() != PaymentSubmissionStatus.PENDING) {
            throw new IllegalStateException("Este pago ya fue revisado");
        }

        BigDecimal approvedAmount = paymentMoneyPolicy.requireMoney(dto.approvedAmount(), "approvedAmount");
        BigDecimal reportedAmount = paymentMoneyPolicy.requireMoney(submission.getReportedAmount(), "reportedAmount");
        if (approvedAmount.compareTo(BigDecimal.ZERO) < 0) {
            throw new IllegalArgumentException("El monto aprobado no puede ser negativo");
        }
        if (approvedAmount.compareTo(reportedAmount) > 0) {
            throw new IllegalArgumentException("El monto aprobado no puede superar el monto informado");
        }

        BigDecimal rejectedAmount = reportedAmount.subtract(approvedAmount).setScale(2, RoundingMode.HALF_UP);
        if (rejectedAmount.compareTo(BigDecimal.ZERO) > 0
                && (dto.adminObservation() == null || dto.adminObservation().isBlank())) {
            throw new IllegalStateException("Se requiere una observación al no aprobar el monto completo");
        }

        List<Installment> scopedInstallments = installmentRepository.findByTripIdAndUserIdAndStudentIdForUpdate(
                submission.getTrip().getId(),
                submission.getUser().getId(),
                submission.getStudent() != null ? submission.getStudent().getId() : null
        );

        BigDecimal approvedTripAmount = BigDecimal.ZERO;
        if (approvedAmount.compareTo(BigDecimal.ZERO) > 0) {
            PaymentAllocationPlanner.PlanResult approvedPlan;
            try {
                approvedPlan = paymentAllocationPlanner.plan(
                        scopedInstallments,
                        approvedAmount,
                        submission.getPaymentCurrency(),
                        submission.getExchangeRate()
                );
            } catch (PaymentBalanceExceededException exception) {
                throw new IllegalStateException(exception.getMessage(), exception);
            }
            paymentAllocationPlanner.assertConservation(approvedPlan);
            approvedTripAmount = approvedPlan.amountInTripCurrency();

            PaymentOutcome approvedOutcome = new PaymentOutcome();
            approvedOutcome.setSubmission(submission);
            approvedOutcome.setStatus(PaymentOutcomeStatus.APPROVED);
            approvedOutcome.setReportedAmount(approvedPlan.reportedAmount());
            approvedOutcome.setAmountInTripCurrency(approvedPlan.amountInTripCurrency());
            approvedOutcome.setAdminObservation(isLegacyPendingSubmission(submission)
                    ? LEGACY_RECONCILIATION_OBSERVATION
                    : null);
            approvedOutcome.setResolvedByEmail(reviewerEmail);
            PaymentOutcome savedOutcome = paymentOutcomeRepository.save(approvedOutcome);
            submission.getOutcomes().add(savedOutcome);

            List<PaymentAllocation> allocations = new ArrayList<>();
            for (PaymentAllocationPlanner.PlannedAllocation allocation : approvedPlan.allocations()) {
                Installment installment = allocation.installment();
                installment.setPaidAmount(safeAmount(installment.getPaidAmount()).add(allocation.amountInTripCurrency()));

                PaymentAllocation entity = new PaymentAllocation();
                entity.setOutcome(savedOutcome);
                entity.setInstallment(installment);
                entity.setAllocationOrder(allocation.allocationOrder());
                entity.setReportedAmount(allocation.reportedAmount());
                entity.setAmountInTripCurrency(allocation.amountInTripCurrency());
                allocations.add(entity);
            }
            installmentRepository.saveAll(scopedInstallments);
            paymentAllocationRepository.saveAll(allocations);
            savedOutcome.getAllocations().addAll(allocations);
        }

        if (rejectedAmount.compareTo(BigDecimal.ZERO) > 0) {
            BigDecimal rejectedTripAmount = paymentMoneyPolicy.requireMoney(
                    submission.getAmountInTripCurrency(), "amountInTripCurrency")
                    .subtract(approvedTripAmount)
                    .setScale(PaymentMoneyPolicy.MONEY_SCALE, RoundingMode.UNNECESSARY);
            if (rejectedTripAmount.signum() < 0) {
                throw new IllegalStateException(
                        "FIN-001: los resultados aprobado y rechazado superan el importe convertido original");
            }
            PaymentOutcome rejectedOutcome = new PaymentOutcome();
            rejectedOutcome.setSubmission(submission);
            rejectedOutcome.setStatus(PaymentOutcomeStatus.REJECTED);
            rejectedOutcome.setReportedAmount(rejectedAmount);
            rejectedOutcome.setAmountInTripCurrency(rejectedTripAmount);
            rejectedOutcome.setAdminObservation(dto.adminObservation().trim());
            rejectedOutcome.setResolvedByEmail(reviewerEmail);
            submission.getOutcomes().add(paymentOutcomeRepository.save(rejectedOutcome));
        }

        submission.setStatus(PaymentSubmissionStatus.RESOLVED);
        paymentSubmissionRepository.save(submission);

        return toSubmissionDTO(paymentSubmissionRepository.findByIdWithContext(submissionId).orElseThrow(), null);
    }

    public PaymentSubmissionDTO voidPayment(Long submissionId, String reviewerEmail) {
        paymentSubmissionRepository.findByIdForUpdate(submissionId)
                .orElseThrow(() -> new EntityNotFoundException("PaymentSubmission not found with id " + submissionId));
        PaymentSubmission submission = paymentSubmissionRepository.findByIdWithContext(submissionId)
                .orElseThrow(() -> new EntityNotFoundException("PaymentSubmission not found with id " + submissionId));

        if (submission.getStatus() == PaymentSubmissionStatus.VOIDED) {
            throw new IllegalStateException("Este pago ya fue anulado");
        }

        PaymentOutcome approvedOutcome = submission.getOutcomes().stream()
                .filter(outcome -> outcome.getStatus() == PaymentOutcomeStatus.APPROVED)
                .findFirst()
                .orElseThrow(() -> new IllegalStateException("Solo se puede anular un pago con tramo aprobado"));

        List<Installment> scopedInstallments = installmentRepository.findByTripIdAndUserIdAndStudentIdForUpdate(
                submission.getTrip().getId(),
                submission.getUser().getId(),
                submission.getStudent() != null ? submission.getStudent().getId() : null
        );
        Map<Long, Installment> installmentsById = scopedInstallments.stream()
                .collect(Collectors.toMap(Installment::getId, Function.identity()));

        List<PaymentAllocation> allocations = approvedOutcome.getAllocations().stream()
                .sorted(Comparator.comparing(PaymentAllocation::getAllocationOrder))
                .toList();
        for (PaymentAllocation allocation : allocations) {
            Installment installment = installmentsById.get(allocation.getInstallment().getId());
            if (installment == null) {
                throw new IllegalStateException("No se encontró la cuota a revertir");
            }

            BigDecimal currentPaidAmount = safeAmount(installment.getPaidAmount());
            if (currentPaidAmount.compareTo(allocation.getAmountInTripCurrency()) < 0) {
                throw new IllegalStateException("La cuota no tiene saldo suficiente para anular este pago");
            }

            installment.setPaidAmount(currentPaidAmount.subtract(allocation.getAmountInTripCurrency()));
        }
        installmentRepository.saveAll(scopedInstallments);

        PaymentOutcome voidOutcome = new PaymentOutcome();
        voidOutcome.setSubmission(submission);
        voidOutcome.setStatus(PaymentOutcomeStatus.VOIDED);
        voidOutcome.setReportedAmount(approvedOutcome.getReportedAmount());
        voidOutcome.setAmountInTripCurrency(approvedOutcome.getAmountInTripCurrency());
        voidOutcome.setAdminObservation("Anulado por administrador");
        voidOutcome.setResolvedByEmail(reviewerEmail);
        submission.getOutcomes().add(paymentOutcomeRepository.save(voidOutcome));

        submission.setStatus(PaymentSubmissionStatus.VOIDED);
        paymentSubmissionRepository.save(submission);

        return toSubmissionDTO(paymentSubmissionRepository.findByIdWithContext(submissionId).orElseThrow(), null);
    }

    @Transactional(readOnly = true)
    public List<PaymentInstallmentHistoryDTO> getReceiptsForInstallment(Long installmentId) {
        List<PaymentInstallmentHistoryDTO> legacyHistory = paymentReceiptRepository.findByInstallmentId(installmentId).stream()
                .sorted(Comparator.comparing(PaymentReceipt::getId).reversed())
                .map(this::toInstallmentHistoryDTO)
                .toList();

        List<PaymentInstallmentHistoryDTO> newHistory = paymentAllocationRepository.findByInstallmentIdWithContext(installmentId).stream()
                .map(this::toInstallmentHistoryDTO)
                .toList();

        return java.util.stream.Stream.concat(legacyHistory.stream(), newHistory.stream())
                .sorted(Comparator
                        .comparing(PaymentInstallmentHistoryDTO::reportedPaymentDate, Comparator.nullsLast(Comparator.reverseOrder()))
                        .thenComparing(PaymentInstallmentHistoryDTO::id, Comparator.nullsLast(Comparator.reverseOrder())))
                .toList();
    }

    @Transactional(readOnly = true)
    public List<PaymentSubmissionDTO> getReceiptsForCurrentUser(String email) {
        User user = getUserByEmail(email);
        return buildUnifiedSubmissionHistory(user.getId());
    }

    @Transactional(readOnly = true)
    public List<PendingPaymentReviewDTO> getPendingReviewReceipts() {
        return paymentSubmissionRepository.findByStatusWithContext(PaymentSubmissionStatus.PENDING).stream()
                .map(this::toPendingReviewDTO)
                .toList();
    }

    @Transactional(readOnly = true)
    public List<UserInstallmentDTO> getInstallmentsForCurrentUser(String email) {
        User user = getUserByEmail(email);

        List<Installment> installments = installmentRepository.findByUserIdWithTrip(user.getId());
        List<Long> installmentIds = installments.stream()
                .map(Installment::getId)
                .toList();

        Map<Long, PaymentReceipt> latestReceiptByInstallmentId = installmentIds.isEmpty()
                ? Map.of()
                : paymentReceiptRepository.findByInstallmentIdIn(installmentIds).stream()
                .collect(Collectors.toMap(
                        receipt -> receipt.getInstallment().getId(),
                        Function.identity(),
                        (existing, ignored) -> existing
                ));
        Map<Long, PaymentInstallmentOverlayService.InstallmentOverlay> overlays =
                paymentInstallmentOverlayService.resolveForInstallments(installments);

        Map<UserInstallmentGroupKey, List<Installment>> installmentsByTripId = installments.stream()
                .collect(Collectors.groupingBy(i -> new UserInstallmentGroupKey(
                        i.getTrip().getId(),
                        i.getStudent() != null ? i.getStudent().getId() : null
                )));

        return installments.stream()
                .map(installment -> {
                    PaymentReceipt latestReceipt = latestReceiptByInstallmentId.get(installment.getId());
                    PaymentInstallmentOverlayService.InstallmentOverlay overlay = overlays.get(installment.getId());
                    ReceiptStatus latestStatus = overlay != null
                            ? overlay.status()
                            : latestReceipt != null ? latestReceipt.getStatus() : null;
                    String latestObservation = overlay != null
                            ? overlay.observation()
                            : latestReceipt != null ? latestReceipt.getAdminObservation() : null;

                    Student student = installment.getStudent();
                    int yellowDays = installment.getTrip().getYellowWarningDays() == null
                            ? 0
                            : installment.getTrip().getYellowWarningDays();

                    InstallmentStatus effectiveStatus = installmentStatusResolver.computeEffective(
                            installment.getStatus(),
                            installment.getDueDate(),
                            yellowDays,
                            installment.getPaidAmount(),
                            installment.getTotalDue()
                    );
                    InstallmentUiStatus uiStatus = installmentUiStatusResolver.resolve(
                            effectiveStatus,
                            latestStatus,
                            installment.getDueDate(),
                            yellowDays,
                            installment.getPaidAmount(),
                            installment.getTotalDue()
                    );

                    List<Installment> tripGroup = installmentsByTripId.get(
                            new UserInstallmentGroupKey(
                                    installment.getTrip().getId(),
                                    student != null ? student.getId() : null
                            )
                    );
                    boolean userCompletedTrip = tripGroup.stream()
                            .allMatch(this::isFullyCovered);

                    return new UserInstallmentDTO(
                            installment.getTrip().getId(),
                            installment.getTrip().getName(),
                            student != null ? student.getId() : null,
                            StudentNameFormatter.displayName(student),
                            student != null ? student.getDni() : null,
                            installment.getId(),
                            installment.getInstallmentNumber(),
                            installment.getDueDate(),
                            installment.getTotalDue(),
                            installment.getPaidAmount(),
                            yellowDays,
                            installment.getTrip().getCurrency(),
                            effectiveStatus,
                            latestStatus,
                            uiStatus.code(),
                            uiStatus.label(),
                            uiStatus.tone(),
                            latestObservation,
                            userCompletedTrip
                    );
                })
                .sorted(Comparator
                        .comparing(UserInstallmentDTO::tripId)
                        .thenComparing(UserInstallmentDTO::studentId, Comparator.nullsFirst(Comparator.naturalOrder()))
                        .thenComparing(UserInstallmentDTO::installmentNumber))
                .toList();
    }

    @Transactional(readOnly = true)
    public List<PaymentSubmissionDTO> getUnifiedSubmissionHistoryForUserId(Long userId) {
        return buildUnifiedSubmissionHistory(userId);
    }

    private List<PaymentSubmissionDTO> buildUnifiedSubmissionHistory(Long userId) {
        List<PaymentSubmissionDTO> newSubmissions = paymentSubmissionRepository.findByUserIdWithContext(userId).stream()
                .map(submission -> toSubmissionDTO(submission, null))
                .toList();

        List<PaymentSubmissionDTO> legacySubmissions = toLegacySubmissionDTOs(
                paymentReceiptRepository.findByInstallmentUserIdWithContext(userId)
        );

        return java.util.stream.Stream.concat(newSubmissions.stream(), legacySubmissions.stream())
                .sorted(Comparator
                        .comparing(PaymentSubmissionDTO::reportedPaymentDate, Comparator.nullsLast(Comparator.reverseOrder()))
                        .thenComparing(PaymentSubmissionDTO::submissionId, Comparator.nullsLast(Comparator.reverseOrder())))
                .toList();
    }

    private User getUserByEmail(String email) {
        return userRepository.findByEmail(email)
                .orElseThrow(() -> new EntityNotFoundException("User not found with email " + email));
    }

    /**
     * Acquires a {@code SELECT ... FOR UPDATE} on the Trip row referenced by the given anchor
     * installment, BEFORE any installment scope lock is acquired. This is the lock-order
     * contract enforced by {@code registerPayment} to prevent a Trip ↔ Installments deadlock
     * with {@code TripService.unassignStudentByDni} (which also locks Trip before Installments).
     *
     * <p>The non-locking initial lookup is purely for trip-ID discovery — it intentionally does
     * NOT perform ownership validation. Ownership validation remains the responsibility of
     * {@link #resolvePaymentScope(User, Long, boolean)} which re-loads the anchor with its user
     * + student associations and throws {@link AccessDeniedException} if the anchor belongs to
     * another user. Calling this method with an anchor that doesn't belong to the caller is
     * safe: the caller will still receive a typed {@code AccessDeniedException} from
     * {@code resolvePaymentScope}; the Trip lock acquired here is then released on rollback.
     *
     * <p>Throws {@link EntityNotFoundException} if the anchor installment (or its referenced
     * Trip) does not exist.
     */
    private void lockTripForPaymentRegistration(Long anchorInstallmentId) {
        Long tripId = installmentRepository.findByIdWithTrip(anchorInstallmentId)
                .map(installment -> installment.getTrip().getId())
                .orElseThrow(() -> new EntityNotFoundException("Installment not found with id " + anchorInstallmentId));
        tripRepository.findByIdForUpdate(tripId)
                .orElseThrow(() -> new EntityNotFoundException("Trip not found with id " + tripId));
    }

    private PaymentScopeSelection resolvePaymentScope(User user, Long anchorInstallmentId, boolean forUpdate) {
        Installment anchorInstallment = installmentRepository.findByIdWithTripUserAndStudent(anchorInstallmentId)
                .orElseThrow(() -> new EntityNotFoundException("Installment not found with id " + anchorInstallmentId));

        boolean isAdmin = user.getRole() == Role.ADMIN;
        if (!isAdmin && !anchorInstallment.getUser().getId().equals(user.getId())) {
            throw new AccessDeniedException("No podés registrar un pago para una cuota que no es tuya");
        }

        Long studentId = anchorInstallment.getStudent() != null ? anchorInstallment.getStudent().getId() : null;
        if (hasPendingReview(anchorInstallment.getTrip().getId(), anchorInstallment.getUser().getId(), studentId)) {
            throw new IllegalStateException("Ya existe al menos un pago pendiente para esta inscripción");
        }

        List<Installment> groupInstallments = forUpdate
                ? installmentRepository.findByTripIdAndUserIdAndStudentIdForUpdate(
                        anchorInstallment.getTrip().getId(),
                        anchorInstallment.getUser().getId(),
                        studentId
                )
                : installmentRepository.findByTripIdAndUserIdAndStudentId(
                        anchorInstallment.getTrip().getId(),
                        anchorInstallment.getUser().getId(),
                        studentId
                ).stream().sorted(Comparator.comparing(Installment::getInstallmentNumber)).toList();

        List<Installment> payableInstallments = groupInstallments.stream()
                .filter(this::hasRemainingBalance)
                .sorted(Comparator.comparing(Installment::getInstallmentNumber))
                .toList();

        if (payableInstallments.isEmpty()) {
            throw new IllegalStateException("Esta inscripción no tiene cuotas pendientes");
        }

        Installment firstPendingInstallment = payableInstallments.get(0);
        if (!firstPendingInstallment.getId().equals(anchorInstallment.getId())) {
            throw new IllegalStateException("Solo podés pagar desde la primera cuota pendiente");
        }

        return new PaymentScopeSelection(anchorInstallment, payableInstallments);
    }

    private boolean hasPendingReview(Long tripId, Long userId, Long studentId) {
        boolean hasLegacyPending = paymentReceiptRepository.existsByTripIdAndUserIdAndStudentIdAndStatus(
                tripId,
                userId,
                studentId,
                ReceiptStatus.PENDING
        );
        boolean hasPendingSubmission = paymentSubmissionRepository.existsByTripIdAndUserIdAndStudentIdAndStatus(
                tripId,
                userId,
                studentId,
                PaymentSubmissionStatus.PENDING
        );
        return hasLegacyPending || hasPendingSubmission;
    }

    private BankAccount resolveBankAccount(Long bankAccountId, Currency paymentCurrency) {
        if (bankAccountId == null) {
            throw new IllegalArgumentException("Debe seleccionar una cuenta bancaria para acreditar el pago");
        }

        BankAccount bankAccount = bankAccountRepository.findById(bankAccountId)
                .orElseThrow(() -> new EntityNotFoundException("BankAccount not found with id " + bankAccountId));

        if (!bankAccount.isActive()) {
            throw new IllegalArgumentException("La cuenta bancaria seleccionada no está activa");
        }

        if (bankAccount.getCurrency() != paymentCurrency) {
            throw new IllegalArgumentException("La cuenta bancaria seleccionada no coincide con la moneda del pago");
        }

        return bankAccount;
    }

    private boolean requiresExchangeRate(Currency tripCurrency, Currency paymentCurrency) {
        return tripCurrency != paymentCurrency;
    }

    private ExchangeRateQuote fetchLiveQuoteForPreview(Currency tripCurrency, Currency paymentCurrency, LocalDate reportedPaymentDate) {
        paymentBusinessDatePolicy.requireNotFuture(reportedPaymentDate);
        if (tripCurrency == paymentCurrency) {
            return null;
        }
        ExchangeRateQuote quote = exchangeRateQuoteProvider.getOfficialQuoteForDate(reportedPaymentDate);
        BigDecimal normalizedRate = paymentMoneyPolicy.requireProviderRate(quote.sellRate());
        return new ExchangeRateQuote(
                normalizedRate,
                quote.requestedDate(),
                quote.effectiveDate(),
                quote.source(),
                quote.provider(),
                quote.providerTimestamp()
        );
    }

    private PaymentPreviewTokenService.PreviewSnapshot buildPreviewSnapshot(
            User user,
            Long anchorInstallmentId,
            Currency paymentCurrency,
            BigDecimal reportedAmount,
            LocalDate reportedPaymentDate,
            ExchangeRateQuote quote,
            PaymentCalculationIntent intent
    ) {
        return new PaymentPreviewTokenService.PreviewSnapshot(
                user.getId(),
                anchorInstallmentId,
                paymentCurrency,
                paymentMoneyPolicy.requirePositiveMoney(reportedAmount, "reportedAmount"),
                reportedPaymentDate,
                quote == null ? null : quote.sellRate(),
                quote == null ? null : quote.requestedDate(),
                quote == null ? null : quote.effectiveDate(),
                quote == null ? null : quote.source(),
                quote == null ? null : quote.provider(),
                quote == null ? null : quote.providerTimestamp(),
                intent,
                PaymentPreviewTokenService.CURRENT_CALCULATION_VERSION
        );
    }

    private ValidatedPreview applyPreviewToken(
            User user,
            Long anchorInstallmentId,
            Currency paymentCurrency,
            BigDecimal reportedAmount,
            LocalDate reportedPaymentDate,
            String previewToken
    ) {
        PaymentPreviewTokenService.PreviewSnapshot snapshot = paymentPreviewTokenService
                .parseAndValidate(previewToken, user.getId())
                .orElseThrow(() -> new IllegalArgumentException(
                        "El comprobante de imputación venció o es inválido. Volvé a calcular la previsualización."
                ));
        if (!snapshot.anchorInstallmentId().equals(anchorInstallmentId)) {
            throw new IllegalArgumentException(
                    "El comprobante de imputación no corresponde a esta cuota. Volvé a calcular la previsualización."
            );
        }
        if (snapshot.paymentCurrency() != paymentCurrency) {
            throw new IllegalArgumentException(
                    "El comprobante de imputación no coincide con la moneda del pago. Volvé a calcular la previsualización."
            );
        }
        if (snapshot.reportedAmount().compareTo(
                paymentMoneyPolicy.requirePositiveMoney(reportedAmount, "reportedAmount")) != 0) {
            throw new IllegalArgumentException(
                    "El comprobante de imputación no coincide con el monto informado. Volvé a calcular la previsualización."
            );
        }
        if (!snapshot.reportedPaymentDate().equals(reportedPaymentDate)) {
            throw new IllegalArgumentException(
                    "El comprobante de imputación no coincide con la fecha de pago. Volvé a calcular la previsualización."
            );
        }
        if (snapshot.quoteSellRate() == null) {
            return new ValidatedPreview(null, snapshot.intent());
        }
        return new ValidatedPreview(new ExchangeRateQuote(
                paymentMoneyPolicy.requireProviderRate(snapshot.quoteSellRate()),
                snapshot.quoteRequestedDate() != null ? snapshot.quoteRequestedDate() : reportedPaymentDate,
                snapshot.quoteEffectiveDate() != null ? snapshot.quoteEffectiveDate() : reportedPaymentDate,
                snapshot.quoteSource() != null ? snapshot.quoteSource() : "unknown",
                snapshot.quoteProvider() != null ? snapshot.quoteProvider() : "unknown",
                snapshot.quoteProviderTimestamp()
        ), snapshot.intent());
    }

    private PaymentBatchPreviewDTO toPreviewDTO(
            Installment anchorInstallment,
            LocalDate reportedPaymentDate,
            PaymentAllocationPlanner.PlanResult plan,
            ExchangeRateQuote quote,
            String previewToken
    ) {
        return new PaymentBatchPreviewDTO(
                anchorInstallment.getId(),
                plan.tripCurrency(),
                plan.paymentCurrency(),
                plan.reportedAmount(),
                plan.maxAllowedAmount(),
                plan.exchangeRate(),
                plan.totalPendingAmountInTripCurrency(),
                plan.amountInTripCurrency(),
                reportedPaymentDate,
                quote == null ? null : quote.requestedDate(),
                quote == null ? null : quote.effectiveDate(),
                quote == null ? null : quote.source(),
                quote == null ? null : quote.provider(),
                quote == null ? null : quote.providerTimestamp(),
                PaymentPreviewTokenService.CURRENT_CALCULATION_VERSION,
                previewToken,
                toInstallmentDTOs(plan.allocations(), null)
        );
    }

    private PaymentSubmissionDTO toSubmissionDTO(PaymentSubmission submission, List<PaymentBatchInstallmentDTO> fallbackInstallments) {
        PaymentHistoryStatus status = resolveSubmissionStatus(submission);
        PaymentOutcome approvedOutcome = findAllocationOutcome(submission);
        boolean voided = submission.getStatus() == PaymentSubmissionStatus.VOIDED;
        BigDecimal approvedAmount = !voided && approvedOutcome != null && approvedOutcome.getStatus() == PaymentOutcomeStatus.APPROVED
                ? approvedOutcome.getReportedAmount()
                : BigDecimal.ZERO;
        BigDecimal approvedAmountInTripCurrency = !voided && approvedOutcome != null && approvedOutcome.getStatus() == PaymentOutcomeStatus.APPROVED
                ? approvedOutcome.getAmountInTripCurrency()
                : BigDecimal.ZERO;
        BigDecimal rejectedAmount = submission.getOutcomes().stream()
                .filter(outcome -> outcome.getStatus() == PaymentOutcomeStatus.REJECTED)
                .map(PaymentOutcome::getReportedAmount)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        String adminObservation = submission.getOutcomes().stream()
                .filter(outcome -> outcome.getAdminObservation() != null && !outcome.getAdminObservation().isBlank())
                .map(PaymentOutcome::getAdminObservation)
                .findFirst()
                .orElse(null);

        List<PaymentBatchInstallmentDTO> installments = fallbackInstallments != null
                ? fallbackInstallments
                : resolveSubmissionInstallments(submission, status);

        return new PaymentSubmissionDTO(
                submission.getId(),
                status,
                submission.getReportedAmount(),
                approvedAmount,
                rejectedAmount,
                submission.getPaymentCurrency(),
                exchangeRateForDto(submission),
                submission.getAmountInTripCurrency(),
                approvedAmountInTripCurrency,
                submission.getReportedPaymentDate(),
                submission.getExchangeRateRequestedDate(),
                submission.getExchangeRateEffectiveDate(),
                submission.getExchangeRateSource(),
                submission.getExchangeRateProvider(),
                submission.getExchangeRateProviderTimestamp(),
                submission.getCalculationVersion(),
                submission.getPaymentMethod(),
                resolveFileReference(submission.getFileKey()),
                adminObservation,
                submission.getBankAccount() != null ? submission.getBankAccount().getId() : null,
                submission.getBankAccount() != null ? formatBankAccountDisplay(submission.getBankAccount()) : null,
                submission.getBankAccount() != null ? submission.getBankAccount().getAlias() : null,
                submission.getTrip().getId(),
                submission.getTrip().getName(),
                submission.getTrip().getCurrency(),
                submission.getStudent() != null ? submission.getStudent().getId() : null,
                StudentNameFormatter.displayName(submission.getStudent()),
                submission.getStudent() != null ? submission.getStudent().getDni() : null,
                installments
        );
    }

    private List<PaymentBatchInstallmentDTO> resolveSubmissionInstallments(PaymentSubmission submission, PaymentHistoryStatus status) {
        PaymentOutcome approvedOrVoidedOutcome = findAllocationOutcome(submission);
        if (approvedOrVoidedOutcome != null && !approvedOrVoidedOutcome.getAllocations().isEmpty()) {
            ReceiptStatus allocationStatus = submission.getStatus() == PaymentSubmissionStatus.VOIDED
                    ? ReceiptStatus.REJECTED
                    : ReceiptStatus.APPROVED;
            return approvedOrVoidedOutcome.getAllocations().stream()
                    .sorted(Comparator.comparing(PaymentAllocation::getAllocationOrder))
                    .map(allocation -> toInstallmentDTO(allocation, allocationStatus))
                    .toList();
        }

        if (status == PaymentHistoryStatus.PENDING || status == PaymentHistoryStatus.REJECTED) {
            return projectSubmissionInstallments(submission, status);
        }

        return List.of();
    }

    private PaymentOutcome findAllocationOutcome(PaymentSubmission submission) {
        PaymentOutcome approved = submission.getOutcomes().stream()
                .filter(outcome -> outcome.getStatus() == PaymentOutcomeStatus.APPROVED)
                .findFirst()
                .orElse(null);
        if (approved != null) {
            return approved;
        }
        return submission.getOutcomes().stream()
                .filter(outcome -> outcome.getStatus() == PaymentOutcomeStatus.VOIDED)
                .filter(outcome -> !outcome.getAllocations().isEmpty())
                .findFirst()
                .orElse(null);
    }

    private List<PaymentBatchInstallmentDTO> projectSubmissionInstallments(PaymentSubmission submission, PaymentHistoryStatus status) {
        List<Installment> scopedInstallments = installmentRepository.findByTripIdAndUserIdAndStudentId(
                submission.getTrip().getId(),
                submission.getUser().getId(),
                submission.getStudent() != null ? submission.getStudent().getId() : null
        ).stream().sorted(Comparator.comparing(Installment::getInstallmentNumber)).toList();
        List<Installment> payableInstallments = scopedInstallments.stream()
                .filter(this::hasRemainingBalance)
                .toList();
        if (payableInstallments.isEmpty()) {
            return List.of();
        }

        try {
            PaymentAllocationPlanner.PlanResult plan = paymentAllocationPlanner.plan(
                    payableInstallments,
                    submission.getReportedAmount(),
                    submission.getPaymentCurrency(),
                    submission.getExchangeRate()
            );
            ReceiptStatus projectedStatus = status == PaymentHistoryStatus.PENDING ? ReceiptStatus.PENDING : ReceiptStatus.REJECTED;
            return toInstallmentDTOs(plan.allocations(), projectedStatus);
        } catch (IllegalStateException | IllegalArgumentException ex) {
            return List.of();
        }
    }

    private PendingPaymentReviewDTO toPendingReviewDTO(PaymentSubmission submission) {
        return new PendingPaymentReviewDTO(
                submission.getId(),
                PaymentHistoryStatus.PENDING,
                submission.getReportedAmount(),
                submission.getPaymentCurrency(),
                exchangeRateForDto(submission),
                submission.getAmountInTripCurrency(),
                submission.getReportedPaymentDate(),
                submission.getExchangeRateRequestedDate(),
                submission.getExchangeRateEffectiveDate(),
                submission.getExchangeRateSource(),
                submission.getExchangeRateProvider(),
                submission.getExchangeRateProviderTimestamp(),
                submission.getCalculationVersion(),
                submission.getPaymentMethod(),
                resolveFileReference(submission.getFileKey()),
                submission.getBankAccount() != null ? submission.getBankAccount().getId() : null,
                submission.getBankAccount() != null ? formatBankAccountDisplay(submission.getBankAccount()) : null,
                submission.getBankAccount() != null ? submission.getBankAccount().getAlias() : null,
                submission.getTrip().getId(),
                submission.getTrip().getName(),
                submission.getTrip().getCurrency(),
                submission.getUser().getId(),
                submission.getUser().getName(),
                submission.getUser().getLastname(),
                submission.getUser().getEmail(),
                StudentNameFormatter.displayName(submission.getStudent()),
                submission.getStudent() != null ? submission.getStudent().getDni() : null,
                resolveSubmissionInstallments(submission, PaymentHistoryStatus.PENDING)
        );
    }

    private PaymentInstallmentHistoryDTO toInstallmentHistoryDTO(PaymentReceipt receipt) {
        return new PaymentInstallmentHistoryDTO(
                receipt.getId(),
                null,
                receipt.getInstallment().getId(),
                receipt.getInstallment().getInstallmentNumber(),
                receipt.getReportedAmount(),
                receipt.getPaymentCurrency(),
                receipt.getExchangeRate(),
                receipt.getAmountInTripCurrency(),
                receipt.getReportedPaymentDate(),
                null,
                null,
                null,
                null,
                null,
                null,
                receipt.getPaymentMethod(),
                toHistoryStatus(receipt.getStatus()),
                resolveFileReference(resolveFileKey(receipt)),
                receipt.getAdminObservation(),
                resolveBankAccountId(receipt),
                resolveBankAccountDisplayName(receipt),
                resolveBankAccountAlias(receipt)
        );
    }

    private PaymentInstallmentHistoryDTO toInstallmentHistoryDTO(PaymentAllocation allocation) {
        PaymentSubmission submission = allocation.getOutcome().getSubmission();
        return new PaymentInstallmentHistoryDTO(
                allocation.getId(),
                submission.getId(),
                allocation.getInstallment().getId(),
                allocation.getInstallment().getInstallmentNumber(),
                allocation.getReportedAmount(),
                submission.getPaymentCurrency(),
                exchangeRateForDto(submission),
                allocation.getAmountInTripCurrency(),
                submission.getReportedPaymentDate(),
                submission.getExchangeRateRequestedDate(),
                submission.getExchangeRateEffectiveDate(),
                submission.getExchangeRateSource(),
                submission.getExchangeRateProvider(),
                submission.getExchangeRateProviderTimestamp(),
                submission.getCalculationVersion(),
                submission.getPaymentMethod(),
                submission.getStatus() == PaymentSubmissionStatus.VOIDED ? PaymentHistoryStatus.VOIDED : PaymentHistoryStatus.APPROVED,
                resolveFileReference(submission.getFileKey()),
                allocation.getOutcome().getAdminObservation(),
                submission.getBankAccount() != null ? submission.getBankAccount().getId() : null,
                submission.getBankAccount() != null ? formatBankAccountDisplay(submission.getBankAccount()) : null,
                submission.getBankAccount() != null ? submission.getBankAccount().getAlias() : null
        );
    }

    private List<PaymentSubmissionDTO> toLegacySubmissionDTOs(List<PaymentReceipt> receipts) {
        Map<LegacySubmissionKey, List<PaymentReceipt>> grouped = new LinkedHashMap<>();
        for (PaymentReceipt receipt : receipts) {
            PaymentBatch batch = receipt.getBatch();
            LegacySubmissionKey key = batch != null
                    ? new LegacySubmissionKey(batch.getId(), null)
                    : new LegacySubmissionKey(null, receipt.getId());
            grouped.computeIfAbsent(key, ignored -> new ArrayList<>()).add(receipt);
        }

        return grouped.entrySet().stream()
                .map(entry -> toLegacySubmissionDTO(entry.getKey(), entry.getValue()))
                .toList();
    }

    private PaymentSubmissionDTO toLegacySubmissionDTO(LegacySubmissionKey key, List<PaymentReceipt> receipts) {
        List<PaymentReceipt> sortedReceipts = receipts.stream()
                .sorted(Comparator.comparing(receipt -> receipt.getInstallment().getInstallmentNumber()))
                .toList();
        PaymentReceipt firstReceipt = sortedReceipts.get(0);
        PaymentBatch batch = firstReceipt.getBatch();

        BigDecimal reportedAmount = batch != null
                ? batch.getReportedAmount()
                : sortedReceipts.stream().map(PaymentReceipt::getReportedAmount).reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal amountInTripCurrency = batch != null
                ? batch.getAmountInTripCurrency()
                : sortedReceipts.stream().map(PaymentReceipt::getAmountInTripCurrency).reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal approvedAmount = sortedReceipts.stream()
                .filter(receipt -> receipt.getStatus() == ReceiptStatus.APPROVED)
                .map(PaymentReceipt::getReportedAmount)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal rejectedAmount = sortedReceipts.stream()
                .filter(receipt -> receipt.getStatus() == ReceiptStatus.REJECTED)
                .map(PaymentReceipt::getReportedAmount)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal approvedAmountInTripCurrency = sortedReceipts.stream()
                .filter(receipt -> receipt.getStatus() == ReceiptStatus.APPROVED)
                .map(PaymentReceipt::getAmountInTripCurrency)
                .reduce(BigDecimal.ZERO, BigDecimal::add);

        PaymentHistoryStatus status;
        boolean hasPending = sortedReceipts.stream().anyMatch(receipt -> receipt.getStatus() == ReceiptStatus.PENDING);
        boolean hasApproved = sortedReceipts.stream().anyMatch(receipt -> receipt.getStatus() == ReceiptStatus.APPROVED);
        boolean hasRejected = sortedReceipts.stream().anyMatch(receipt -> receipt.getStatus() == ReceiptStatus.REJECTED);
        if (hasPending) {
            status = PaymentHistoryStatus.PENDING;
        } else if (hasApproved && hasRejected) {
            status = PaymentHistoryStatus.PARTIALLY_APPROVED;
        } else if (hasApproved) {
            status = PaymentHistoryStatus.APPROVED;
        } else {
            status = PaymentHistoryStatus.REJECTED;
        }

        Installment installment = firstReceipt.getInstallment();
        Student student = installment.getStudent();
        return new PaymentSubmissionDTO(
                batch != null ? -batch.getId() : -firstReceipt.getId(),
                status,
                reportedAmount,
                approvedAmount,
                rejectedAmount,
                batch != null ? batch.getPaymentCurrency() : firstReceipt.getPaymentCurrency(),
                batch != null ? batch.getExchangeRate() : firstReceipt.getExchangeRate(),
                amountInTripCurrency,
                approvedAmountInTripCurrency,
                batch != null ? batch.getReportedPaymentDate() : firstReceipt.getReportedPaymentDate(),
                null,
                null,
                null,
                null,
                null,
                null,
                batch != null ? batch.getPaymentMethod() : firstReceipt.getPaymentMethod(),
                resolveFileReference(batch != null ? batch.getFileKey() : firstReceipt.getFileKey()),
                sortedReceipts.stream()
                        .map(PaymentReceipt::getAdminObservation)
                        .filter(value -> value != null && !value.isBlank())
                        .findFirst()
                        .orElse(null),
                batch != null && batch.getBankAccount() != null
                        ? batch.getBankAccount().getId()
                        : firstReceipt.getBankAccount() != null ? firstReceipt.getBankAccount().getId() : null,
                batch != null && batch.getBankAccount() != null
                        ? formatBankAccountDisplay(batch.getBankAccount())
                        : firstReceipt.getBankAccount() != null ? formatBankAccountDisplay(firstReceipt.getBankAccount()) : null,
                batch != null && batch.getBankAccount() != null
                        ? batch.getBankAccount().getAlias()
                        : firstReceipt.getBankAccount() != null ? firstReceipt.getBankAccount().getAlias() : null,
                installment.getTrip().getId(),
                installment.getTrip().getName(),
                installment.getTrip().getCurrency(),
                student != null ? student.getId() : null,
                StudentNameFormatter.displayName(student),
                student != null ? student.getDni() : null,
                sortedReceipts.stream().map(this::toLegacyInstallmentDTO).toList()
        );
    }

    private PaymentBatchInstallmentDTO toLegacyInstallmentDTO(PaymentReceipt receipt) {
        Installment installment = receipt.getInstallment();
        return new PaymentBatchInstallmentDTO(
                receipt.getId(),
                installment.getId(),
                installment.getInstallmentNumber(),
                installment.getDueDate(),
                installment.getTotalDue(),
                installment.getPaidAmount(),
                getRemainingAmount(installment),
                receipt.getReportedAmount(),
                receipt.getAmountInTripCurrency(),
                receipt.getStatus()
        );
    }

    private List<PaymentBatchInstallmentDTO> toInstallmentDTOs(
            List<PaymentAllocationPlanner.PlannedAllocation> allocations,
            ReceiptStatus status
    ) {
        return allocations.stream()
                .map(allocation -> new PaymentBatchInstallmentDTO(
                        null,
                        allocation.installment().getId(),
                        allocation.installment().getInstallmentNumber(),
                        allocation.installment().getDueDate(),
                        allocation.installment().getTotalDue(),
                        allocation.installment().getPaidAmount(),
                        allocation.remainingAmount(),
                        allocation.reportedAmount(),
                        allocation.amountInTripCurrency(),
                        status
                ))
                .toList();
    }

    private PaymentBatchInstallmentDTO toInstallmentDTO(PaymentAllocation allocation, ReceiptStatus status) {
        Installment installment = allocation.getInstallment();
        return new PaymentBatchInstallmentDTO(
                allocation.getId(),
                installment.getId(),
                installment.getInstallmentNumber(),
                installment.getDueDate(),
                installment.getTotalDue(),
                installment.getPaidAmount(),
                getRemainingAmount(installment),
                allocation.getReportedAmount(),
                allocation.getAmountInTripCurrency(),
                status
        );
    }

    private PaymentHistoryStatus resolveSubmissionStatus(PaymentSubmission submission) {
        if (submission.getStatus() == PaymentSubmissionStatus.PENDING) {
            return PaymentHistoryStatus.PENDING;
        }
        if (submission.getStatus() == PaymentSubmissionStatus.VOIDED) {
            return PaymentHistoryStatus.VOIDED;
        }

        boolean hasApproved = submission.getOutcomes().stream()
                .anyMatch(outcome -> outcome.getStatus() == PaymentOutcomeStatus.APPROVED);
        boolean hasRejected = submission.getOutcomes().stream()
                .anyMatch(outcome -> outcome.getStatus() == PaymentOutcomeStatus.REJECTED);

        if (hasApproved && hasRejected) {
            return PaymentHistoryStatus.PARTIALLY_APPROVED;
        }
        if (hasApproved) {
            return PaymentHistoryStatus.APPROVED;
        }
        return PaymentHistoryStatus.REJECTED;
    }

    private PaymentHistoryStatus toHistoryStatus(ReceiptStatus status) {
        if (status == null) {
            return PaymentHistoryStatus.PENDING;
        }
        return switch (status) {
            case PENDING -> PaymentHistoryStatus.PENDING;
            case APPROVED -> PaymentHistoryStatus.APPROVED;
            case REJECTED -> PaymentHistoryStatus.REJECTED;
        };
    }

    private String resolveFileKey(PaymentReceipt receipt) {
        if (receipt.getBatch() != null && receipt.getBatch().getFileKey() != null && !receipt.getBatch().getFileKey().isBlank()) {
            return receipt.getBatch().getFileKey();
        }
        return receipt.getFileKey();
    }

    private String resolveFileReference(String storedValue) {
        return paymentAttachmentStorageService.resolveFileReference(storedValue);
    }

    private Long resolveBankAccountId(PaymentReceipt receipt) {
        if (receipt.getBankAccount() != null) {
            return receipt.getBankAccount().getId();
        }
        if (receipt.getBatch() != null && receipt.getBatch().getBankAccount() != null) {
            return receipt.getBatch().getBankAccount().getId();
        }
        return null;
    }

    private String resolveBankAccountDisplayName(PaymentReceipt receipt) {
        if (receipt.getBankAccount() != null) {
            return formatBankAccountDisplay(receipt.getBankAccount());
        }
        if (receipt.getBatch() != null && receipt.getBatch().getBankAccount() != null) {
            return formatBankAccountDisplay(receipt.getBatch().getBankAccount());
        }
        return null;
    }

    private String resolveBankAccountAlias(PaymentReceipt receipt) {
        if (receipt.getBankAccount() != null) {
            return receipt.getBankAccount().getAlias();
        }
        if (receipt.getBatch() != null && receipt.getBatch().getBankAccount() != null) {
            return receipt.getBatch().getBankAccount().getAlias();
        }
        return null;
    }

    private String formatBankAccountDisplay(BankAccount bankAccount) {
        return bankAccount.getBankName() + " - " + bankAccount.getAccountLabel();
    }

    private BigDecimal safeAmount(BigDecimal amount) {
        return amount == null ? BigDecimal.ZERO : amount;
    }

    private boolean isLegacyPendingSubmission(PaymentSubmission submission) {
        return "v1".equals(submission.getCalculationVersion());
    }

    private BigDecimal exchangeRateForDto(PaymentSubmission submission) {
        BigDecimal rate = submission.getExchangeRate();
        Integer scale = submission.getExchangeRateScale();
        if (rate == null || scale == null) {
            return rate;
        }
        return rate.setScale(scale, RoundingMode.UNNECESSARY);
    }

    private BigDecimal getRemainingAmount(Installment installment) {
        return paymentAllocationPlanner.getRemainingAmount(installment);
    }

    private boolean hasRemainingBalance(Installment installment) {
        return getRemainingAmount(installment).compareTo(BigDecimal.ZERO) > 0;
    }

    private boolean isFullyCovered(Installment installment) {
        return getRemainingAmount(installment).compareTo(BigDecimal.ZERO) <= 0;
    }
}
