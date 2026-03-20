package com.agencia.pagos.services;

import com.agencia.pagos.dtos.request.RegisterPaymentDTO;
import com.agencia.pagos.dtos.request.ReviewPaymentDTO;
import com.agencia.pagos.dtos.response.PaymentReceiptDTO;
import com.agencia.pagos.entities.Installment;
import com.agencia.pagos.entities.InstallmentStatus;
import com.agencia.pagos.entities.PaymentReceipt;
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

import java.util.Comparator;
import java.util.List;

@Service
@Transactional
public class PaymentService {

    private final PaymentReceiptRepository paymentReceiptRepository;
    private final InstallmentRepository installmentRepository;
    private final UserRepository userRepository;

    @Autowired
    public PaymentService(
            PaymentReceiptRepository paymentReceiptRepository,
            InstallmentRepository installmentRepository,
            UserRepository userRepository
    ) {
        this.paymentReceiptRepository = paymentReceiptRepository;
        this.installmentRepository = installmentRepository;
        this.userRepository = userRepository;
    }

    public PaymentReceiptDTO registerPayment(RegisterPaymentDTO dto, String email) {
        User user = userRepository.findByEmail(email)
                .orElseThrow(() -> new EntityNotFoundException("User not found with email " + email));

        Installment installment = installmentRepository.findById(dto.installmentId())
                .orElseThrow(() -> new EntityNotFoundException("Installment not found with id " + dto.installmentId()));

        if (!installment.getUser().getId().equals(user.getId())) {
            throw new AccessDeniedException("No podés registrar un pago para una cuota que no es tuya");
        }

        if (installment.getStatus() == InstallmentStatus.GREEN) {
            throw new IllegalStateException("Esta cuota ya está pagada");
        }

        if (paymentReceiptRepository.existsByInstallmentIdAndStatus(dto.installmentId(), ReceiptStatus.PENDING)) {
            throw new IllegalStateException("Ya existe un comprobante pendiente de revisión para esta cuota");
        }

        PaymentReceipt receipt = PaymentReceipt.builder()
                .installment(installment)
                .reportedAmount(dto.reportedAmount())
                .reportedPaymentDate(dto.reportedPaymentDate())
                .paymentMethod(dto.paymentMethod())
                .status(ReceiptStatus.PENDING)
                .fileKey("")
                .adminObservation(null)
                .build();

        PaymentReceipt saved = paymentReceiptRepository.save(receipt);
        return toDTO(saved);
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

    private PaymentReceiptDTO toDTO(PaymentReceipt receipt) {
        return new PaymentReceiptDTO(
                receipt.getId(),
                receipt.getInstallment().getId(),
                receipt.getInstallment().getInstallmentNumber(),
                receipt.getReportedAmount(),
                receipt.getReportedPaymentDate(),
                receipt.getPaymentMethod(),
                receipt.getStatus(),
                receipt.getAdminObservation()
        );
    }
}
