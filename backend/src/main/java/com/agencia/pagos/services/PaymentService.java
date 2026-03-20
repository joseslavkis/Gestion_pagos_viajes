package com.agencia.pagos.services;

import com.agencia.pagos.dtos.request.RegisterPaymentDTO;
import com.agencia.pagos.dtos.request.ReviewPaymentDTO;
import com.agencia.pagos.dtos.response.PaymentReceiptDTO;
import com.agencia.pagos.dtos.response.UserInstallmentDTO;
import com.agencia.pagos.entities.Installment;
import com.agencia.pagos.entities.InstallmentStatus;
import com.agencia.pagos.entities.PaymentMethod;
import com.agencia.pagos.entities.PaymentReceipt;
import com.agencia.pagos.entities.Role;
import com.agencia.pagos.entities.ReceiptStatus;
import com.agencia.pagos.entities.user.User;
import com.agencia.pagos.repositories.InstallmentRepository;
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
import java.time.LocalDate;
import java.util.Base64;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

@Service
@Transactional
public class PaymentService {

    private final PaymentReceiptRepository paymentReceiptRepository;
    private final InstallmentRepository installmentRepository;
    private final UserRepository userRepository;
    private final InstallmentStatusResolver installmentStatusResolver;

    @Autowired
    public PaymentService(
            PaymentReceiptRepository paymentReceiptRepository,
            InstallmentRepository installmentRepository,
            UserRepository userRepository,
            InstallmentStatusResolver installmentStatusResolver
    ) {
        this.paymentReceiptRepository = paymentReceiptRepository;
        this.installmentRepository = installmentRepository;
        this.userRepository = userRepository;
        this.installmentStatusResolver = installmentStatusResolver;
    }

        public PaymentReceiptDTO registerPayment(
            Long installmentId,
            BigDecimal reportedAmount,
            LocalDate reportedPaymentDate,
            PaymentMethod paymentMethod,
            MultipartFile file,
            String email
        ) {
        User user = userRepository.findByEmail(email)
                .orElseThrow(() -> new EntityNotFoundException("User not found with email " + email));

        Installment installment = installmentRepository.findByIdWithTrip(installmentId)
            .orElseThrow(() -> new EntityNotFoundException("Installment not found with id " + installmentId));

        boolean isAdmin = user.getRole() == Role.ADMIN;
        if (!isAdmin && !installment.getUser().getId().equals(user.getId())) {
            throw new AccessDeniedException("No podés registrar un pago para una cuota que no es tuya");
        }

        int yellowDays = installment.getTrip().getYellowWarningDays() == null
            ? 0
            : installment.getTrip().getYellowWarningDays();
        InstallmentStatus effective = installmentStatusResolver.computeEffective(
            installment.getStatus(), installment.getDueDate(), yellowDays);
        if (effective == InstallmentStatus.GREEN
            && installment.getStatus() == InstallmentStatus.GREEN) {
            throw new IllegalStateException("Esta cuota ya está pagada");
        }

        if (paymentReceiptRepository.existsByInstallmentIdAndStatus(installmentId, ReceiptStatus.PENDING)) {
            throw new IllegalStateException("Ya existe un comprobante pendiente de revisión para esta cuota");
        }

        String fileKey = "";
        if (file != null && !file.isEmpty()) {
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
                fileKey = "data:" + mimeType + ";base64," + base64;
            } catch (IOException exception) {
                throw new IllegalStateException("No se pudo procesar el archivo adjunto", exception);
            }
        }

        PaymentReceipt receipt = PaymentReceipt.builder()
                .installment(installment)
                .reportedAmount(reportedAmount)
                .reportedPaymentDate(reportedPaymentDate)
                .paymentMethod(paymentMethod)
                .status(ReceiptStatus.PENDING)
                .fileKey(fileKey)
                .adminObservation(null)
                .build();

        PaymentReceipt saved = paymentReceiptRepository.save(receipt);
        return toDTO(saved);
    }

    public PaymentReceiptDTO registerPayment(RegisterPaymentDTO dto, String email) {
        return registerPayment(
                dto.installmentId(),
                dto.reportedAmount(),
                dto.reportedPaymentDate(),
                dto.paymentMethod(),
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
            receipt.setStatus(ReceiptStatus.APPROVED);
            receipt.setAdminObservation(null);
            installment.setStatus(InstallmentStatus.GREEN);
            installmentRepository.save(installment);
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

        receipt.setStatus(ReceiptStatus.REJECTED);
        receipt.setAdminObservation("Anulado por administrador");

        Installment installment = receipt.getInstallment();
        if (installment.getStatus() == InstallmentStatus.GREEN) {
            installment.setStatus(InstallmentStatus.YELLOW);
            installmentRepository.save(installment);
        }

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
        User user = userRepository.findByEmail(email)
                .orElseThrow(() -> new EntityNotFoundException("User not found with email " + email));

        return paymentReceiptRepository.findByInstallmentUserId(user.getId()).stream()
                .sorted(Comparator.comparing(PaymentReceipt::getId).reversed())
                .map(this::toDTO)
                .toList();
    }

    @Transactional(readOnly = true)
    public List<UserInstallmentDTO> getInstallmentsForCurrentUser(String email) {
        User user = userRepository.findByEmail(email)
                .orElseThrow(() -> new EntityNotFoundException("User not found with email " + email));

        List<Installment> installments = installmentRepository.findByUserIdWithTrip(user.getId());
        List<Long> installmentIds = installments.stream()
            .map(Installment::getId)
            .toList();

        Map<Long, PaymentReceipt> latestReceiptByInstallmentId = installmentIds.isEmpty()
            ? Map.of()
            : paymentReceiptRepository.findByInstallmentIdIn(installmentIds)
                .stream()
                .collect(Collectors.toMap(
                    receipt -> receipt.getInstallment().getId(),
                    Function.identity(),
                    (existing, ignored) -> existing
                ));

        return installments.stream()
                .map((installment) -> {
                PaymentReceipt latestReceipt = latestReceiptByInstallmentId.get(installment.getId());

                    int yellowDays = installment.getTrip().getYellowWarningDays() == null
                            ? 0
                            : installment.getTrip().getYellowWarningDays();

                    return new UserInstallmentDTO(
                    installment.getTrip().getId(),
                            installment.getId(),
                            installment.getInstallmentNumber(),
                            installment.getDueDate(),
                            installment.getTotalDue(),
                    installmentStatusResolver.computeEffective(
                        installment.getStatus(), installment.getDueDate(), yellowDays),
                            latestReceipt != null ? latestReceipt.getStatus() : null,
                            latestReceipt != null ? latestReceipt.getAdminObservation() : null
                    );
                })
                .sorted(Comparator.comparing(UserInstallmentDTO::installmentNumber))
                .toList();
    }

    private PaymentReceiptDTO toDTO(PaymentReceipt receipt) {
        return new PaymentReceiptDTO(
                receipt.getId(),
                receipt.getInstallment().getId(),
                receipt.getInstallment().getInstallmentNumber(),
                receipt.getReportedAmount(),
                receipt.getReportedPaymentDate(),
                receipt.getPaymentMethod(),
                receipt.getStatus(),
                receipt.getFileKey(),
                receipt.getAdminObservation()
        );
    }
}
