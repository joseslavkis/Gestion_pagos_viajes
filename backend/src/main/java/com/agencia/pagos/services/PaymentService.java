package com.agencia.pagos.services;

import com.agencia.pagos.dtos.request.PaymentPreviewRequestDTO;
import com.agencia.pagos.dtos.request.RegisterPaymentDTO;
import com.agencia.pagos.dtos.request.ReviewPaymentDTO;
import com.agencia.pagos.dtos.response.PaymentBatchDTO;
import com.agencia.pagos.dtos.response.PaymentBatchInstallmentDTO;
import com.agencia.pagos.dtos.response.PaymentBatchPreviewDTO;
import com.agencia.pagos.dtos.response.PendingPaymentReviewDTO;
import com.agencia.pagos.dtos.response.PendingPaymentReviewLineDTO;
import com.agencia.pagos.dtos.response.PaymentReceiptDTO;
import com.agencia.pagos.dtos.response.UserInstallmentDTO;
import com.agencia.pagos.entities.BankAccount;
import com.agencia.pagos.entities.Currency;
import com.agencia.pagos.entities.Installment;
import com.agencia.pagos.entities.InstallmentStatus;
import com.agencia.pagos.entities.PaymentBatch;
import com.agencia.pagos.entities.PaymentMethod;
import com.agencia.pagos.entities.PaymentReceipt;
import com.agencia.pagos.entities.ReceiptStatus;
import com.agencia.pagos.entities.Role;
import com.agencia.pagos.entities.Student;
import com.agencia.pagos.entities.user.User;
import com.agencia.pagos.repositories.BankAccountRepository;
import com.agencia.pagos.repositories.InstallmentRepository;
import com.agencia.pagos.repositories.PaymentBatchRepository;
import com.agencia.pagos.repositories.PaymentReceiptRepository;
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
import java.util.function.Function;
import java.util.stream.Collectors;

@Service
@Transactional
public class PaymentService {

    private record UserInstallmentGroupKey(Long tripId, Long studentId) {}

    private record InstallmentSelection(Installment anchorInstallment, List<Installment> installments) {}

    private record InstallmentAllocation(
            Installment installment,
            BigDecimal remainingAmount,
            BigDecimal reportedAmount
    ) {}

    private record BatchAmounts(
            Currency tripCurrency,
            Currency paymentCurrency,
            BigDecimal exchangeRate,
            BigDecimal totalReportedAmount,
            BigDecimal totalAmountInTripCurrency,
            List<InstallmentAllocation> allocations
    ) {}

    private final PaymentReceiptRepository paymentReceiptRepository;
    private final PaymentBatchRepository paymentBatchRepository;
    private final InstallmentRepository installmentRepository;
    private final UserRepository userRepository;
    private final BankAccountRepository bankAccountRepository;
    private final InstallmentStatusResolver installmentStatusResolver;
    private final InstallmentUiStatusResolver installmentUiStatusResolver;
    private final ExchangeRateService exchangeRateService;

    @Autowired
    public PaymentService(
            PaymentReceiptRepository paymentReceiptRepository,
            PaymentBatchRepository paymentBatchRepository,
            InstallmentRepository installmentRepository,
            UserRepository userRepository,
            BankAccountRepository bankAccountRepository,
            InstallmentStatusResolver installmentStatusResolver,
            InstallmentUiStatusResolver installmentUiStatusResolver,
            ExchangeRateService exchangeRateService
    ) {
        this.paymentReceiptRepository = paymentReceiptRepository;
        this.paymentBatchRepository = paymentBatchRepository;
        this.installmentRepository = installmentRepository;
        this.userRepository = userRepository;
        this.bankAccountRepository = bankAccountRepository;
        this.installmentStatusResolver = installmentStatusResolver;
        this.installmentUiStatusResolver = installmentUiStatusResolver;
        this.exchangeRateService = exchangeRateService;
    }

    @Transactional(readOnly = true)
    public PaymentBatchPreviewDTO previewPayment(PaymentPreviewRequestDTO dto, String email) {
        User user = getUserByEmail(email);
        InstallmentSelection selection = resolveInstallmentSelection(
                user,
                dto.anchorInstallmentId(),
                dto.installmentsCount()
        );
        BatchAmounts batchAmounts = computeBatchAmounts(
                selection.installments(),
                dto.paymentCurrency(),
                dto.reportedPaymentDate()
        );
        return toPreviewDTO(selection.anchorInstallment(), dto.installmentsCount(), dto.reportedPaymentDate(), batchAmounts);
    }

    public PaymentBatchDTO registerPayment(
            Long anchorInstallmentId,
            Integer installmentsCount,
            LocalDate reportedPaymentDate,
            Currency paymentCurrency,
            PaymentMethod paymentMethod,
            Long bankAccountId,
            MultipartFile file,
            String email
    ) {
        User user = getUserByEmail(email);
        InstallmentSelection selection = resolveInstallmentSelection(user, anchorInstallmentId, installmentsCount);
        BankAccount bankAccount = resolveBankAccount(bankAccountId, paymentCurrency);
        BatchAmounts batchAmounts = computeBatchAmounts(
                selection.installments(),
                paymentCurrency,
                reportedPaymentDate
        );
        String fileKey = extractFileKey(file);

        PaymentBatch batch = new PaymentBatch();
        batch.setReportedAmount(batchAmounts.totalReportedAmount());
        batch.setPaymentCurrency(paymentCurrency);
        batch.setExchangeRate(batchAmounts.exchangeRate());
        batch.setAmountInTripCurrency(batchAmounts.totalAmountInTripCurrency());
        batch.setReportedPaymentDate(reportedPaymentDate);
        batch.setPaymentMethod(paymentMethod);
        batch.setBankAccount(bankAccount);
        batch.setFileKey(fileKey);
        PaymentBatch savedBatch = paymentBatchRepository.save(batch);

        List<PaymentReceipt> receipts = new ArrayList<>();
        for (InstallmentAllocation allocation : batchAmounts.allocations()) {
            PaymentReceipt receipt = PaymentReceipt.builder()
                    .installment(allocation.installment())
                    .batch(savedBatch)
                    .bankAccount(bankAccount)
                    .reportedAmount(allocation.reportedAmount())
                    .paymentCurrency(paymentCurrency)
                    .exchangeRate(batchAmounts.exchangeRate())
                    .amountInTripCurrency(allocation.remainingAmount())
                    .reportedPaymentDate(reportedPaymentDate)
                    .paymentMethod(paymentMethod)
                    .status(ReceiptStatus.PENDING)
                    .fileKey("")
                    .adminObservation(null)
                    .build();
            receipts.add(receipt);
        }

        List<PaymentReceipt> savedReceipts = paymentReceiptRepository.saveAll(receipts).stream()
                .sorted(Comparator.comparing(receipt -> receipt.getInstallment().getInstallmentNumber()))
                .toList();
        return toBatchDTO(savedBatch, savedReceipts);
    }

    public PaymentBatchDTO registerPayment(RegisterPaymentDTO dto, String email) {
        return registerPayment(
                dto.anchorInstallmentId(),
                dto.installmentsCount(),
                dto.reportedPaymentDate(),
                dto.paymentCurrency(),
                dto.paymentMethod(),
                dto.bankAccountId(),
                null,
                email
        );
    }

    public PaymentReceiptDTO reviewPayment(Long receiptId, ReviewPaymentDTO dto) {
        PaymentReceipt receipt = paymentReceiptRepository.findById(receiptId)
                .orElseThrow(() -> new EntityNotFoundException("PaymentReceipt not found with id " + receiptId));

        if (receipt.getStatus() != ReceiptStatus.PENDING) {
            throw new IllegalStateException("Este comprobante ya fue revisado");
        }

        if (dto.decision() != ReceiptStatus.APPROVED && dto.decision() != ReceiptStatus.REJECTED) {
            throw new IllegalStateException("La decision debe ser APPROVED o REJECTED");
        }

        if (dto.decision() == ReceiptStatus.REJECTED
                && (dto.adminObservation() == null || dto.adminObservation().isBlank())) {
            throw new IllegalStateException("Se requiere una observación al rechazar un comprobante");
        }

        Installment installment = receipt.getInstallment();

        if (dto.decision() == ReceiptStatus.APPROVED) {
            BigDecimal remainingAmount = getRemainingAmount(installment);
            if (remainingAmount.compareTo(BigDecimal.ZERO) <= 0) {
                throw new IllegalStateException("Esta cuota ya está totalmente cubierta");
            }
            if (receipt.getAmountInTripCurrency().compareTo(remainingAmount) > 0) {
                throw new IllegalStateException("El saldo de la cuota cambió y este comprobante ya no coincide con el importe pendiente");
            }

            installment.setPaidAmount(safeAmount(installment.getPaidAmount()).add(receipt.getAmountInTripCurrency()));
            installmentRepository.save(installment);

            receipt.setStatus(ReceiptStatus.APPROVED);
            receipt.setAdminObservation(null);
        } else {
            receipt.setStatus(ReceiptStatus.REJECTED);
            receipt.setAdminObservation(dto.adminObservation().trim());
        }

        PaymentReceipt saved = paymentReceiptRepository.save(receipt);
        return toDTO(saved);
    }

    public PaymentReceiptDTO voidPayment(Long receiptId) {
        PaymentReceipt receipt = paymentReceiptRepository.findById(receiptId)
                .orElseThrow(() -> new EntityNotFoundException("PaymentReceipt not found with id " + receiptId));

        if (receipt.getStatus() != ReceiptStatus.APPROVED) {
            throw new IllegalStateException("Solo se puede anular un comprobante aprobado");
        }

        Installment installment = receipt.getInstallment();
        BigDecimal currentPaid = safeAmount(installment.getPaidAmount());
        BigDecimal amountToRevert = safeAmount(receipt.getAmountInTripCurrency());

        if (currentPaid.compareTo(amountToRevert) < 0) {
            throw new IllegalStateException("La cuota no tiene saldo suficiente para anular este comprobante");
        }

        installment.setPaidAmount(currentPaid.subtract(amountToRevert));
        installmentRepository.save(installment);

        receipt.setStatus(ReceiptStatus.REJECTED);
        receipt.setAdminObservation("Anulado por administrador");

        PaymentReceipt saved = paymentReceiptRepository.save(receipt);
        return toDTO(saved);
    }

    @Transactional(readOnly = true)
    public List<PaymentReceiptDTO> getReceiptsForInstallment(Long installmentId) {
        return paymentReceiptRepository.findByInstallmentId(installmentId).stream()
                .sorted(Comparator.comparing(PaymentReceipt::getId).reversed())
                .map(this::toDTO)
                .toList();
    }

    @Transactional(readOnly = true)
    public List<PaymentReceiptDTO> getReceiptsForCurrentUser(String email) {
        User user = getUserByEmail(email);

        return paymentReceiptRepository.findByInstallmentUserIdWithContext(user.getId()).stream()
                .map(this::toDTO)
                .toList();
    }

    @Transactional(readOnly = true)
    public List<PendingPaymentReviewDTO> getPendingReviewReceipts() {
        List<PaymentReceipt> pendingReceipts = paymentReceiptRepository.findByStatusWithContext(ReceiptStatus.PENDING);
        if (pendingReceipts.isEmpty()) {
            return List.of();
        }

        List<Long> batchIds = pendingReceipts.stream()
                .map(PaymentReceipt::getBatch)
                .filter(Objects::nonNull)
                .map(PaymentBatch::getId)
                .distinct()
                .toList();

        Map<Long, List<PaymentReceipt>> receiptsByBatchId = batchIds.isEmpty()
                ? Map.of()
                : paymentReceiptRepository.findByBatchIdInWithContext(batchIds).stream()
                        .collect(Collectors.groupingBy(
                                receipt -> receipt.getBatch().getId(),
                                LinkedHashMap::new,
                                Collectors.toList()
                        ));

        Map<String, List<PaymentReceipt>> grouped = new LinkedHashMap<>();
        for (PaymentReceipt pendingReceipt : pendingReceipts) {
            PaymentBatch batch = pendingReceipt.getBatch();
            if (batch != null) {
                grouped.putIfAbsent("batch:" + batch.getId(), receiptsByBatchId.getOrDefault(batch.getId(), List.of(pendingReceipt)));
            } else {
                grouped.putIfAbsent("legacy:" + pendingReceipt.getId(), List.of(pendingReceipt));
            }
        }

        return grouped.values().stream()
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

        Map<UserInstallmentGroupKey, List<Installment>> installmentsByTripId = installments.stream()
                .collect(Collectors.groupingBy(i -> new UserInstallmentGroupKey(
                        i.getTrip().getId(),
                        i.getStudent() != null ? i.getStudent().getId() : null
                )));

        return installments.stream()
                .map(installment -> {
                    PaymentReceipt latestReceipt = latestReceiptByInstallmentId.get(installment.getId());
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
                            latestReceipt != null ? latestReceipt.getStatus() : null,
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
                            latestReceipt != null ? latestReceipt.getStatus() : null,
                            uiStatus.code(),
                            uiStatus.label(),
                            uiStatus.tone(),
                            latestReceipt != null ? latestReceipt.getAdminObservation() : null,
                            userCompletedTrip
                    );
                })
                .sorted(Comparator
                        .comparing(UserInstallmentDTO::tripId)
                        .thenComparing(
                                UserInstallmentDTO::studentId,
                                Comparator.nullsFirst(Comparator.naturalOrder())
                        )
                        .thenComparing(UserInstallmentDTO::installmentNumber))
                .toList();
    }

    private User getUserByEmail(String email) {
        return userRepository.findByEmail(email)
                .orElseThrow(() -> new EntityNotFoundException("User not found with email " + email));
    }

    private InstallmentSelection resolveInstallmentSelection(User user, Long anchorInstallmentId, Integer installmentsCount) {
        if (installmentsCount == null || installmentsCount <= 0) {
            throw new IllegalArgumentException("La cantidad de cuotas debe ser mayor a 0");
        }

        Installment anchorInstallment = installmentRepository.findByIdWithTrip(anchorInstallmentId)
                .orElseThrow(() -> new EntityNotFoundException("Installment not found with id " + anchorInstallmentId));

        boolean isAdmin = user.getRole() == Role.ADMIN;
        if (!isAdmin && !anchorInstallment.getUser().getId().equals(user.getId())) {
            throw new AccessDeniedException("No podés registrar un pago para una cuota que no es tuya");
        }

        Long studentId = anchorInstallment.getStudent() != null ? anchorInstallment.getStudent().getId() : null;
        boolean hasPendingReview = paymentReceiptRepository.existsByTripIdAndUserIdAndStudentIdAndStatus(
                anchorInstallment.getTrip().getId(),
                anchorInstallment.getUser().getId(),
                studentId,
                ReceiptStatus.PENDING
        );
        if (hasPendingReview) {
            throw new IllegalStateException("Ya existe al menos un comprobante pendiente para esta inscripción");
        }

        List<Installment> groupInstallments = installmentRepository
                .findByTripIdAndUserIdAndStudentId(
                        anchorInstallment.getTrip().getId(),
                        anchorInstallment.getUser().getId(),
                        studentId
                )
                .stream()
                .sorted(Comparator.comparing(Installment::getInstallmentNumber))
                .toList();

        List<Installment> payableInstallments = groupInstallments.stream()
                .filter(this::hasRemainingBalance)
                .toList();

        if (payableInstallments.isEmpty()) {
            throw new IllegalStateException("Esta inscripción no tiene cuotas pendientes");
        }

        Installment firstPendingInstallment = payableInstallments.get(0);
        if (!firstPendingInstallment.getId().equals(anchorInstallment.getId())) {
            throw new IllegalStateException("Solo podés pagar desde la primera cuota pendiente");
        }

        if (installmentsCount > payableInstallments.size()) {
            throw new IllegalStateException("La cantidad de cuotas seleccionada excede las cuotas pendientes");
        }

        return new InstallmentSelection(
                anchorInstallment,
                List.copyOf(payableInstallments.subList(0, installmentsCount))
        );
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

    private BatchAmounts computeBatchAmounts(
            List<Installment> installments,
            Currency paymentCurrency,
            LocalDate reportedPaymentDate
    ) {
        if (installments.isEmpty()) {
            throw new IllegalArgumentException("Debe haber al menos una cuota seleccionada");
        }

        Currency tripCurrency = installments.get(0).getTrip().getCurrency();
        List<BigDecimal> remainingTripAmounts = installments.stream()
                .map(this::getRemainingAmount)
                .toList();

        BigDecimal totalAmountInTripCurrency = remainingTripAmounts.stream()
                .reduce(BigDecimal.ZERO, BigDecimal::add)
                .setScale(2, RoundingMode.HALF_UP);

        BigDecimal exchangeRate = null;
        BigDecimal totalReportedAmount;
        if (paymentCurrency == tripCurrency) {
            totalReportedAmount = totalAmountInTripCurrency;
        } else {
            exchangeRate = exchangeRateService.getOfficialRateForDate(reportedPaymentDate);
            totalReportedAmount = convertTripToPaymentCurrency(
                    totalAmountInTripCurrency,
                    tripCurrency,
                    paymentCurrency,
                    exchangeRate
            );
        }

        List<InstallmentAllocation> allocations = new ArrayList<>();
        BigDecimal accumulatedReported = BigDecimal.ZERO;
        for (int index = 0; index < installments.size(); index++) {
            Installment installment = installments.get(index);
            BigDecimal remainingAmount = remainingTripAmounts.get(index).setScale(2, RoundingMode.HALF_UP);

            BigDecimal lineReportedAmount;
            if (paymentCurrency == tripCurrency) {
                lineReportedAmount = remainingAmount;
            } else if (index == installments.size() - 1) {
                lineReportedAmount = totalReportedAmount.subtract(accumulatedReported).setScale(2, RoundingMode.HALF_UP);
            } else {
                lineReportedAmount = convertTripToPaymentCurrency(
                        remainingAmount,
                        tripCurrency,
                        paymentCurrency,
                        exchangeRate
                );
                accumulatedReported = accumulatedReported.add(lineReportedAmount);
            }

            allocations.add(new InstallmentAllocation(
                    installment,
                    remainingAmount,
                    lineReportedAmount
            ));
        }

        return new BatchAmounts(
                tripCurrency,
                paymentCurrency,
                exchangeRate,
                totalReportedAmount,
                totalAmountInTripCurrency,
                allocations
        );
    }

    private BigDecimal convertTripToPaymentCurrency(
            BigDecimal amountInTripCurrency,
            Currency tripCurrency,
            Currency paymentCurrency,
            BigDecimal exchangeRate
    ) {
        if (tripCurrency == paymentCurrency) {
            return amountInTripCurrency.setScale(2, RoundingMode.HALF_UP);
        }

        if (tripCurrency == Currency.USD && paymentCurrency == Currency.ARS) {
            return amountInTripCurrency.multiply(exchangeRate).setScale(2, RoundingMode.HALF_UP);
        }

        if (tripCurrency == Currency.ARS && paymentCurrency == Currency.USD) {
            return amountInTripCurrency.divide(exchangeRate, 2, RoundingMode.HALF_UP);
        }

        throw new IllegalStateException("Conversión de moneda no soportada");
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
            Integer installmentsCount,
            LocalDate reportedPaymentDate,
            BatchAmounts batchAmounts
    ) {
        List<PaymentBatchInstallmentDTO> installments = batchAmounts.allocations().stream()
                .map(allocation -> new PaymentBatchInstallmentDTO(
                        null,
                        allocation.installment().getId(),
                        allocation.installment().getInstallmentNumber(),
                        allocation.installment().getDueDate(),
                        allocation.installment().getTotalDue(),
                        allocation.installment().getPaidAmount(),
                        allocation.remainingAmount(),
                        allocation.reportedAmount(),
                        allocation.remainingAmount(),
                        null
                ))
                .toList();

        return new PaymentBatchPreviewDTO(
                anchorInstallment.getId(),
                installmentsCount,
                batchAmounts.tripCurrency(),
                batchAmounts.paymentCurrency(),
                batchAmounts.totalReportedAmount(),
                batchAmounts.exchangeRate(),
                batchAmounts.totalAmountInTripCurrency(),
                reportedPaymentDate,
                installments
        );
    }

    private PaymentBatchDTO toBatchDTO(PaymentBatch batch, List<PaymentReceipt> receipts) {
        List<PaymentBatchInstallmentDTO> installments = receipts.stream()
                .sorted(Comparator.comparing(receipt -> receipt.getInstallment().getInstallmentNumber()))
                .map(this::toBatchInstallmentDTO)
                .toList();

        return new PaymentBatchDTO(
                batch.getId(),
                batch.getReportedAmount(),
                batch.getPaymentCurrency(),
                batch.getExchangeRate(),
                batch.getAmountInTripCurrency(),
                batch.getReportedPaymentDate(),
                batch.getPaymentMethod(),
                batch.getBankAccount() != null ? batch.getBankAccount().getId() : null,
                batch.getBankAccount() != null ? formatBankAccountDisplay(batch.getBankAccount()) : null,
                batch.getBankAccount() != null ? batch.getBankAccount().getAlias() : null,
                installments
        );
    }

    private PaymentBatchInstallmentDTO toBatchInstallmentDTO(PaymentReceipt receipt) {
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

    private PaymentReceiptDTO toDTO(PaymentReceipt receipt) {
        return new PaymentReceiptDTO(
                receipt.getId(),
                receipt.getInstallment().getId(),
                receipt.getInstallment().getInstallmentNumber(),
                receipt.getReportedAmount(),
                receipt.getPaymentCurrency(),
                receipt.getExchangeRate(),
                receipt.getAmountInTripCurrency(),
                receipt.getReportedPaymentDate(),
                receipt.getPaymentMethod(),
                receipt.getStatus(),
                resolveFileKey(receipt),
                receipt.getAdminObservation(),
                resolveBankAccountId(receipt),
                resolveBankAccountDisplayName(receipt),
                resolveBankAccountAlias(receipt)
        );
    }

    private PendingPaymentReviewDTO toPendingReviewDTO(List<PaymentReceipt> receipts) {
        List<PaymentReceipt> sortedReceipts = receipts.stream()
                .sorted(Comparator.comparing(receipt -> receipt.getInstallment().getInstallmentNumber()))
                .toList();
        PaymentReceipt firstReceipt = sortedReceipts.get(0);
        Installment installment = firstReceipt.getInstallment();
        User user = installment.getUser();
        Student student = installment.getStudent();
        PaymentBatch batch = firstReceipt.getBatch();

        BigDecimal reportedAmount = batch != null
                ? batch.getReportedAmount()
                : firstReceipt.getReportedAmount();
        Currency paymentCurrency = batch != null
                ? batch.getPaymentCurrency()
                : firstReceipt.getPaymentCurrency();
        BigDecimal exchangeRate = batch != null
                ? batch.getExchangeRate()
                : firstReceipt.getExchangeRate();
        BigDecimal amountInTripCurrency = batch != null
                ? batch.getAmountInTripCurrency()
                : firstReceipt.getAmountInTripCurrency();
        LocalDate reportedPaymentDate = batch != null
                ? batch.getReportedPaymentDate()
                : firstReceipt.getReportedPaymentDate();
        PaymentMethod paymentMethod = batch != null
                ? batch.getPaymentMethod()
                : firstReceipt.getPaymentMethod();

        List<PendingPaymentReviewLineDTO> lines = sortedReceipts.stream()
                .map(receipt -> new PendingPaymentReviewLineDTO(
                        receipt.getId(),
                        receipt.getStatus(),
                        receipt.getReportedAmount(),
                        receipt.getAmountInTripCurrency(),
                        receipt.getInstallment().getId(),
                        receipt.getInstallment().getInstallmentNumber(),
                        receipt.getInstallment().getDueDate(),
                        receipt.getInstallment().getTotalDue(),
                        receipt.getAdminObservation()
                ))
                .toList();

        return new PendingPaymentReviewDTO(
                batch != null ? batch.getId() : null,
                reportedAmount,
                paymentCurrency,
                exchangeRate,
                amountInTripCurrency,
                reportedPaymentDate,
                paymentMethod,
                resolveFileKey(firstReceipt),
                resolveBankAccountId(firstReceipt),
                resolveBankAccountDisplayName(firstReceipt),
                resolveBankAccountAlias(firstReceipt),
                installment.getTrip().getId(),
                installment.getTrip().getName(),
                installment.getTrip().getCurrency(),
                user.getId(),
                user.getName(),
                user.getLastname(),
                user.getEmail(),
                student != null ? student.getName() : null,
                student != null ? student.getDni() : null,
                lines
        );
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
        return safeAmount(installment.getTotalDue())
                .subtract(safeAmount(installment.getPaidAmount()))
                .max(BigDecimal.ZERO)
                .setScale(2, RoundingMode.HALF_UP);
    }

    private boolean hasRemainingBalance(Installment installment) {
        return getRemainingAmount(installment).compareTo(BigDecimal.ZERO) > 0;
    }

    private boolean isFullyCovered(Installment installment) {
        return getRemainingAmount(installment).compareTo(BigDecimal.ZERO) <= 0;
    }
}
