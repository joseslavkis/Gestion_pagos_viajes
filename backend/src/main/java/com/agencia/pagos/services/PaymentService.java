package com.agencia.pagos.services;

import com.agencia.pagos.dtos.request.PaymentPreviewRequestDTO;
import com.agencia.pagos.dtos.request.RegisterPaymentDTO;
import com.agencia.pagos.dtos.request.ReviewPaymentDTO;
import com.agencia.pagos.dtos.response.PaymentBatchInstallmentDTO;
import com.agencia.pagos.dtos.response.PaymentBatchPreviewDTO;
import com.agencia.pagos.dtos.response.PendingPaymentReviewDTO;
import com.agencia.pagos.dtos.response.PaymentInstallmentHistoryDTO;
import com.agencia.pagos.dtos.response.PaymentSubmissionDTO;
import com.agencia.pagos.dtos.response.UserInstallmentDTO;
import com.agencia.pagos.entities.BankAccount;
import com.agencia.pagos.entities.Currency;
import com.agencia.pagos.entities.Installment;
import com.agencia.pagos.entities.InstallmentStatus;
import com.agencia.pagos.entities.PaymentAllocation;
import com.agencia.pagos.entities.PaymentBatch;
import com.agencia.pagos.entities.PaymentHistoryStatus;
import com.agencia.pagos.entities.PaymentMethod;
import com.agencia.pagos.entities.PaymentOutcome;
import com.agencia.pagos.entities.PaymentOutcomeStatus;
import com.agencia.pagos.entities.PaymentReceipt;
import com.agencia.pagos.entities.PaymentSubmission;
import com.agencia.pagos.entities.PaymentSubmissionStatus;
import com.agencia.pagos.entities.ReceiptStatus;
import com.agencia.pagos.entities.Role;
import com.agencia.pagos.entities.Student;
import com.agencia.pagos.entities.user.User;
import com.agencia.pagos.repositories.BankAccountRepository;
import com.agencia.pagos.repositories.InstallmentRepository;
import com.agencia.pagos.repositories.PaymentAllocationRepository;
import com.agencia.pagos.repositories.PaymentBatchRepository;
import com.agencia.pagos.repositories.PaymentOutcomeRepository;
import com.agencia.pagos.repositories.PaymentReceiptRepository;
import com.agencia.pagos.repositories.PaymentSubmissionRepository;
import com.agencia.pagos.repositories.UserRepository;
import jakarta.persistence.EntityNotFoundException;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Base64;
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

    private record UserInstallmentGroupKey(Long tripId, Long studentId) {
    }

    private record PaymentScopeSelection(Installment anchorInstallment, List<Installment> installments) {
    }

    private record LegacySubmissionKey(Long batchId, Long receiptId) {
    }

    private final PaymentReceiptRepository paymentReceiptRepository;
    private final PaymentBatchRepository paymentBatchRepository;
    private final PaymentSubmissionRepository paymentSubmissionRepository;
    private final PaymentOutcomeRepository paymentOutcomeRepository;
    private final PaymentAllocationRepository paymentAllocationRepository;
    private final InstallmentRepository installmentRepository;
    private final UserRepository userRepository;
    private final BankAccountRepository bankAccountRepository;
    private final InstallmentStatusResolver installmentStatusResolver;
    private final InstallmentUiStatusResolver installmentUiStatusResolver;
    private final ExchangeRateService exchangeRateService;
    private final PaymentAllocationPlanner paymentAllocationPlanner;
    private final PaymentInstallmentOverlayService paymentInstallmentOverlayService;

    @Autowired
    public PaymentService(
            PaymentReceiptRepository paymentReceiptRepository,
            PaymentBatchRepository paymentBatchRepository,
            PaymentSubmissionRepository paymentSubmissionRepository,
            PaymentOutcomeRepository paymentOutcomeRepository,
            PaymentAllocationRepository paymentAllocationRepository,
            InstallmentRepository installmentRepository,
            UserRepository userRepository,
            BankAccountRepository bankAccountRepository,
            InstallmentStatusResolver installmentStatusResolver,
            InstallmentUiStatusResolver installmentUiStatusResolver,
            ExchangeRateService exchangeRateService,
            PaymentAllocationPlanner paymentAllocationPlanner,
            PaymentInstallmentOverlayService paymentInstallmentOverlayService
    ) {
        this.paymentReceiptRepository = paymentReceiptRepository;
        this.paymentBatchRepository = paymentBatchRepository;
        this.paymentSubmissionRepository = paymentSubmissionRepository;
        this.paymentOutcomeRepository = paymentOutcomeRepository;
        this.paymentAllocationRepository = paymentAllocationRepository;
        this.installmentRepository = installmentRepository;
        this.userRepository = userRepository;
        this.bankAccountRepository = bankAccountRepository;
        this.installmentStatusResolver = installmentStatusResolver;
        this.installmentUiStatusResolver = installmentUiStatusResolver;
        this.exchangeRateService = exchangeRateService;
        this.paymentAllocationPlanner = paymentAllocationPlanner;
        this.paymentInstallmentOverlayService = paymentInstallmentOverlayService;
    }

    @Transactional(readOnly = true)
    public PaymentBatchPreviewDTO previewPayment(PaymentPreviewRequestDTO dto, String email) {
        User user = getUserByEmail(email);
        PaymentScopeSelection selection = resolvePaymentScope(user, dto.anchorInstallmentId(), false);
        BigDecimal exchangeRate = resolveExchangeRate(
                selection.anchorInstallment().getTrip().getCurrency(),
                dto.paymentCurrency(),
                dto.reportedPaymentDate()
        );
        PaymentAllocationPlanner.PlanResult plan = paymentAllocationPlanner.plan(
                selection.installments(),
                dto.reportedAmount(),
                dto.paymentCurrency(),
                exchangeRate
        );
        return toPreviewDTO(selection.anchorInstallment(), dto.reportedPaymentDate(), plan);
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
        User user = getUserByEmail(email);
        PaymentScopeSelection selection = resolvePaymentScope(user, anchorInstallmentId, true);
        BankAccount bankAccount = resolveBankAccount(bankAccountId, paymentCurrency);
        BigDecimal exchangeRate = resolveExchangeRate(
                selection.anchorInstallment().getTrip().getCurrency(),
                paymentCurrency,
                reportedPaymentDate
        );
        PaymentAllocationPlanner.PlanResult plan = paymentAllocationPlanner.plan(
                selection.installments(),
                reportedAmount,
                paymentCurrency,
                exchangeRate
        );

        PaymentSubmission submission = new PaymentSubmission();
        submission.setTrip(selection.anchorInstallment().getTrip());
        submission.setUser(selection.anchorInstallment().getUser());
        submission.setStudent(selection.anchorInstallment().getStudent());
        submission.setAnchorInstallment(selection.anchorInstallment());
        submission.setBankAccount(bankAccount);
        submission.setReportedAmount(plan.reportedAmount());
        submission.setPaymentCurrency(paymentCurrency);
        submission.setExchangeRate(plan.exchangeRate());
        submission.setAmountInTripCurrency(plan.amountInTripCurrency());
        submission.setReportedPaymentDate(reportedPaymentDate);
        submission.setPaymentMethod(paymentMethod);
        submission.setStatus(PaymentSubmissionStatus.PENDING);
        submission.setFileKey(extractFileKey(file));

        PaymentSubmission saved = paymentSubmissionRepository.save(submission);
        return toSubmissionDTO(saved, toInstallmentDTOs(plan.allocations(), null));
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
                email
        );
    }

    public PaymentSubmissionDTO reviewPayment(Long submissionId, ReviewPaymentDTO dto, String reviewerEmail) {
        PaymentSubmission submission = paymentSubmissionRepository.findByIdWithContext(submissionId)
                .orElseThrow(() -> new EntityNotFoundException("PaymentSubmission not found with id " + submissionId));

        if (submission.getStatus() != PaymentSubmissionStatus.PENDING) {
            throw new IllegalStateException("Este pago ya fue revisado");
        }

        BigDecimal approvedAmount = safeAmount(dto.approvedAmount()).setScale(2, RoundingMode.HALF_UP);
        BigDecimal reportedAmount = safeAmount(submission.getReportedAmount()).setScale(2, RoundingMode.HALF_UP);
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
            PaymentAllocationPlanner.PlanResult approvedPlan = paymentAllocationPlanner.plan(
                    scopedInstallments,
                    approvedAmount,
                    submission.getPaymentCurrency(),
                    submission.getExchangeRate()
            );
            approvedTripAmount = approvedPlan.amountInTripCurrency();

            PaymentOutcome approvedOutcome = new PaymentOutcome();
            approvedOutcome.setSubmission(submission);
            approvedOutcome.setStatus(PaymentOutcomeStatus.APPROVED);
            approvedOutcome.setReportedAmount(approvedPlan.reportedAmount());
            approvedOutcome.setAmountInTripCurrency(approvedPlan.amountInTripCurrency());
            approvedOutcome.setAdminObservation(null);
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
            PaymentOutcome rejectedOutcome = new PaymentOutcome();
            rejectedOutcome.setSubmission(submission);
            rejectedOutcome.setStatus(PaymentOutcomeStatus.REJECTED);
            rejectedOutcome.setReportedAmount(rejectedAmount);
            rejectedOutcome.setAmountInTripCurrency(safeAmount(submission.getAmountInTripCurrency()).subtract(approvedTripAmount).max(BigDecimal.ZERO).setScale(2, RoundingMode.HALF_UP));
            rejectedOutcome.setAdminObservation(dto.adminObservation().trim());
            rejectedOutcome.setResolvedByEmail(reviewerEmail);
            submission.getOutcomes().add(paymentOutcomeRepository.save(rejectedOutcome));
        }

        submission.setStatus(PaymentSubmissionStatus.RESOLVED);
        paymentSubmissionRepository.save(submission);

        return toSubmissionDTO(paymentSubmissionRepository.findByIdWithContext(submissionId).orElseThrow(), null);
    }

    public PaymentSubmissionDTO voidPayment(Long submissionId, String reviewerEmail) {
        PaymentSubmission submission = paymentSubmissionRepository.findByIdWithContext(submissionId)
                .orElseThrow(() -> new EntityNotFoundException("PaymentSubmission not found with id " + submissionId));

        PaymentOutcome approvedOutcome = submission.getOutcomes().stream()
                .filter(outcome -> outcome.getStatus() == PaymentOutcomeStatus.APPROVED)
                .findFirst()
                .orElseThrow(() -> new IllegalStateException("Solo se puede anular un pago con tramo aprobado"));

        if (submission.getStatus() == PaymentSubmissionStatus.VOIDED || approvedOutcome.getStatus() == PaymentOutcomeStatus.VOIDED) {
            throw new IllegalStateException("Este pago ya fue anulado");
        }

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

        approvedOutcome.setStatus(PaymentOutcomeStatus.VOIDED);
        approvedOutcome.setAdminObservation("Anulado por administrador");
        approvedOutcome.setResolvedByEmail(reviewerEmail);
        paymentOutcomeRepository.save(approvedOutcome);

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
                            student != null ? student.getName() : null,
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

    private BigDecimal resolveExchangeRate(Currency tripCurrency, Currency paymentCurrency, LocalDate reportedPaymentDate) {
        if (tripCurrency == paymentCurrency) {
            return null;
        }
        return exchangeRateService.getOfficialRateForDate(reportedPaymentDate);
    }

    private String extractFileKey(MultipartFile file) {
        if (file == null || file.isEmpty()) {
            return "";
        }

        String mimeType = file.getContentType();
        List<String> allowedTypes = List.of(
                "image/jpeg",
                "image/png",
                "image/webp",
                "application/pdf"
        );
        if (mimeType == null || !allowedTypes.contains(mimeType)) {
            throw new IllegalArgumentException("Solo se aceptan imágenes JPG, PNG, WEBP o archivos PDF");
        }

        long maxBytes = 5L * 1024L * 1024L;
        if (file.getSize() > maxBytes) {
            throw new IllegalArgumentException("El archivo no puede superar los 5MB");
        }

        try {
            String base64 = Base64.getEncoder().encodeToString(file.getBytes());
            return "data:" + mimeType + ";base64," + base64;
        } catch (IOException exception) {
            throw new IllegalStateException("No se pudo procesar el archivo adjunto", exception);
        }
    }

    private PaymentBatchPreviewDTO toPreviewDTO(
            Installment anchorInstallment,
            LocalDate reportedPaymentDate,
            PaymentAllocationPlanner.PlanResult plan
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
                toInstallmentDTOs(plan.allocations(), null)
        );
    }

    private PaymentSubmissionDTO toSubmissionDTO(PaymentSubmission submission, List<PaymentBatchInstallmentDTO> fallbackInstallments) {
        PaymentHistoryStatus status = resolveSubmissionStatus(submission);
        PaymentOutcome approvedOutcome = submission.getOutcomes().stream()
                .filter(outcome -> outcome.getStatus() == PaymentOutcomeStatus.APPROVED || outcome.getStatus() == PaymentOutcomeStatus.VOIDED)
                .findFirst()
                .orElse(null);
        BigDecimal approvedAmount = approvedOutcome != null && approvedOutcome.getStatus() == PaymentOutcomeStatus.APPROVED
                ? approvedOutcome.getReportedAmount()
                : BigDecimal.ZERO;
        BigDecimal approvedAmountInTripCurrency = approvedOutcome != null && approvedOutcome.getStatus() == PaymentOutcomeStatus.APPROVED
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
                submission.getExchangeRate(),
                submission.getAmountInTripCurrency(),
                approvedAmountInTripCurrency,
                submission.getReportedPaymentDate(),
                submission.getPaymentMethod(),
                submission.getFileKey(),
                adminObservation,
                submission.getBankAccount() != null ? submission.getBankAccount().getId() : null,
                submission.getBankAccount() != null ? formatBankAccountDisplay(submission.getBankAccount()) : null,
                submission.getBankAccount() != null ? submission.getBankAccount().getAlias() : null,
                submission.getTrip().getId(),
                submission.getTrip().getName(),
                submission.getTrip().getCurrency(),
                submission.getStudent() != null ? submission.getStudent().getId() : null,
                submission.getStudent() != null ? submission.getStudent().getName() : null,
                submission.getStudent() != null ? submission.getStudent().getDni() : null,
                installments
        );
    }

    private List<PaymentBatchInstallmentDTO> resolveSubmissionInstallments(PaymentSubmission submission, PaymentHistoryStatus status) {
        PaymentOutcome approvedOrVoidedOutcome = submission.getOutcomes().stream()
                .filter(outcome -> outcome.getStatus() == PaymentOutcomeStatus.APPROVED || outcome.getStatus() == PaymentOutcomeStatus.VOIDED)
                .findFirst()
                .orElse(null);
        if (approvedOrVoidedOutcome != null && !approvedOrVoidedOutcome.getAllocations().isEmpty()) {
            ReceiptStatus allocationStatus = approvedOrVoidedOutcome.getStatus() == PaymentOutcomeStatus.VOIDED
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
                submission.getExchangeRate(),
                submission.getAmountInTripCurrency(),
                submission.getReportedPaymentDate(),
                submission.getPaymentMethod(),
                submission.getFileKey(),
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
                submission.getStudent() != null ? submission.getStudent().getName() : null,
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
                receipt.getPaymentMethod(),
                toHistoryStatus(receipt.getStatus()),
                resolveFileKey(receipt),
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
                submission.getExchangeRate(),
                allocation.getAmountInTripCurrency(),
                submission.getReportedPaymentDate(),
                submission.getPaymentMethod(),
                allocation.getOutcome().getStatus() == PaymentOutcomeStatus.VOIDED ? PaymentHistoryStatus.VOIDED : PaymentHistoryStatus.APPROVED,
                submission.getFileKey(),
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
                batch != null ? batch.getPaymentMethod() : firstReceipt.getPaymentMethod(),
                batch != null ? batch.getFileKey() : firstReceipt.getFileKey(),
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
                student != null ? student.getName() : null,
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
