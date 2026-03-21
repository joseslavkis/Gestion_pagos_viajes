package com.agencia.pagos.services;

import com.agencia.pagos.dtos.request.RegisterPaymentDTO;
import com.agencia.pagos.dtos.request.ReviewPaymentDTO;
import com.agencia.pagos.dtos.response.PaymentReceiptDTO;
import com.agencia.pagos.dtos.response.UserInstallmentDTO;
import com.agencia.pagos.entities.Currency;
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
import java.math.RoundingMode;
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
    private final ExchangeRateService exchangeRateService;

    @Autowired
    public PaymentService(
            PaymentReceiptRepository paymentReceiptRepository,
            InstallmentRepository installmentRepository,
            UserRepository userRepository,
            InstallmentStatusResolver installmentStatusResolver,
            ExchangeRateService exchangeRateService
    ) {
        this.paymentReceiptRepository = paymentReceiptRepository;
        this.installmentRepository = installmentRepository;
        this.userRepository = userRepository;
        this.installmentStatusResolver = installmentStatusResolver;
        this.exchangeRateService = exchangeRateService;
    }

        public PaymentReceiptDTO registerPayment(
            Long installmentId,
            BigDecimal reportedAmount,
            LocalDate reportedPaymentDate,
            Currency paymentCurrency,
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

        boolean fullyCovered = installment.getStatus() == InstallmentStatus.GREEN
                && installment.getPaidAmount() != null
                && installment.getPaidAmount().compareTo(installment.getTotalDue()) >= 0;
        if (fullyCovered) {
            throw new IllegalStateException("Esta cuota ya está pagada");
        }

        if (paymentReceiptRepository.existsByInstallmentIdAndStatus(installmentId, ReceiptStatus.PENDING)) {
            throw new IllegalStateException("Ya existe un comprobante pendiente de revisión para esta cuota");
        }

        Currency tripCurrency = installment.getTrip().getCurrency();
        BigDecimal exchangeRate = null;
        BigDecimal amountInTripCurrency;
        if (paymentCurrency == tripCurrency) {
            amountInTripCurrency = reportedAmount;
        } else {
            exchangeRate = exchangeRateService.getOfficialRateForDate(reportedPaymentDate);
            if (tripCurrency == Currency.USD && paymentCurrency == Currency.ARS) {
                amountInTripCurrency = reportedAmount.divide(exchangeRate, 2, RoundingMode.HALF_UP);
            } else if (tripCurrency == Currency.ARS && paymentCurrency == Currency.USD) {
                amountInTripCurrency = reportedAmount.multiply(exchangeRate).setScale(2, RoundingMode.HALF_UP);
            } else {
                throw new IllegalStateException("Conversión de moneda no soportada");
            }
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
                .paymentCurrency(paymentCurrency)
                .exchangeRate(exchangeRate)
                .amountInTripCurrency(amountInTripCurrency)
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
            dto.paymentCurrency(),
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

                BigDecimal remaining = receipt.getAmountInTripCurrency() == null
                    ? receipt.getReportedAmount()
                    : receipt.getAmountInTripCurrency();
            List<Installment> pendingInstallments = installmentRepository
                    .findByTripIdAndUserId(installment.getTrip().getId(), installment.getUser().getId())
                    .stream()
                    .filter(i -> i.getStatus() != InstallmentStatus.GREEN)
                    .sorted(Comparator.comparing(Installment::getInstallmentNumber))
                    .toList();

            for (Installment cuota : pendingInstallments) {
                BigDecimal currentPaid = cuota.getPaidAmount() == null ? BigDecimal.ZERO : cuota.getPaidAmount();
                BigDecimal saldoPendiente = cuota.getTotalDue().subtract(currentPaid);

                if (remaining.compareTo(BigDecimal.ZERO) <= 0) {
                    break;
                }

                if (remaining.compareTo(saldoPendiente) >= 0) {
                    cuota.setPaidAmount(cuota.getTotalDue());
                    cuota.setStatus(InstallmentStatus.GREEN);
                    remaining = remaining.subtract(saldoPendiente);
                } else {
                    cuota.setPaidAmount(currentPaid.add(remaining));
                    remaining = BigDecimal.ZERO;
                }

                installmentRepository.save(cuota);
            }
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

        // Revertir la cascada: deshacer lo que hizo el monto aprobado
        BigDecimal amountToRevert = receipt.getAmountInTripCurrency() != null
                ? receipt.getAmountInTripCurrency()
                : receipt.getReportedAmount();

        // Obtener todas las cuotas del usuario en el viaje ordenadas por número
        Installment directInstallment = receipt.getInstallment();
        List<Installment> allInstallments = installmentRepository
                .findByTripIdAndUserId(
                        directInstallment.getTrip().getId(),
                        directInstallment.getUser().getId())
                .stream()
                .sorted(Comparator.comparing(Installment::getInstallmentNumber))
                .toList();

        // Revertir en cascada desde la primera cuota hasta agotar el monto a revertir
        BigDecimal remaining = amountToRevert;
        for (Installment cuota : allInstallments) {
            if (remaining.compareTo(BigDecimal.ZERO) <= 0) {
                break;
            }

            BigDecimal paid = cuota.getPaidAmount() == null
                    ? BigDecimal.ZERO
                    : cuota.getPaidAmount();
            if (paid.compareTo(BigDecimal.ZERO) <= 0) {
                continue;
            }

            if (remaining.compareTo(paid) >= 0) {
                // Revertir esta cuota completamente
                remaining = remaining.subtract(paid);
                cuota.setPaidAmount(BigDecimal.ZERO);
                if (cuota.getStatus() == InstallmentStatus.GREEN) {
                    cuota.setStatus(InstallmentStatus.YELLOW);
                }
            } else {
                // Revertir parcialmente
                cuota.setPaidAmount(paid.subtract(remaining));
                // No cambiar status si queda con saldo parcial
                remaining = BigDecimal.ZERO;
            }
            installmentRepository.save(cuota);
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

        Map<Long, List<Installment>> installmentsByTripId = installments.stream()
                .collect(Collectors.groupingBy(i -> i.getTrip().getId()));

        return installments.stream()
                .map((installment) -> {
                    PaymentReceipt latestReceipt = latestReceiptByInstallmentId.get(installment.getId());

                    int yellowDays = installment.getTrip().getYellowWarningDays() == null
                            ? 0
                            : installment.getTrip().getYellowWarningDays();
                            
                    List<Installment> tripGroup = installmentsByTripId.get(installment.getTrip().getId());
                    boolean userCompletedTrip = tripGroup.stream()
                            .allMatch(i -> i.getStatus() == InstallmentStatus.GREEN);

                    return new UserInstallmentDTO(
                            installment.getTrip().getId(),
                            installment.getId(),
                            installment.getInstallmentNumber(),
                            installment.getDueDate(),
                            installment.getTotalDue(),
                            installment.getPaidAmount(),
                            installment.getTrip().getCurrency(),
                            installmentStatusResolver.computeEffective(
                                installment.getStatus(), installment.getDueDate(), yellowDays),
                            latestReceipt != null ? latestReceipt.getStatus() : null,
                            latestReceipt != null ? latestReceipt.getAdminObservation() : null,
                            userCompletedTrip
                    );
                })
                .sorted(Comparator
                    .comparing(UserInstallmentDTO::tripId)
                    .thenComparing(UserInstallmentDTO::installmentNumber))
                .toList();
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
                receipt.getFileKey(),
                receipt.getAdminObservation()
        );
    }
}
