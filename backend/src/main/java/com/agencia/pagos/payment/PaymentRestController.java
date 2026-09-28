package com.agencia.pagos.payment;

import com.agencia.pagos.payment.dto.PaymentPreviewRequestDTO;
import com.agencia.pagos.payment.dto.RegisterPaymentDTO;
import com.agencia.pagos.payment.dto.ReviewPaymentDTO;
import com.agencia.pagos.payment.dto.PendingPaymentReviewDTO;
import com.agencia.pagos.payment.dto.PaymentBatchPreviewDTO;
import com.agencia.pagos.payment.dto.PaymentInstallmentHistoryDTO;
import com.agencia.pagos.payment.dto.PaymentSubmissionDTO;
import com.agencia.pagos.payment.dto.PaymentCalculationRequestDTO;
import com.agencia.pagos.payment.dto.PaymentCalculationResponseDTO;
import com.agencia.pagos.user.dto.UserInstallmentDTO;
import com.agencia.pagos.shared.money.Currency;
import com.agencia.pagos.payment.PaymentMethod;
import com.agencia.pagos.payment.PaymentService;
import jakarta.validation.Valid;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;
import java.util.List;
import java.math.BigDecimal;

@RestController
@RequestMapping("/api/v1/payments")
class PaymentRestController {

    private final PaymentService paymentService;

    @Autowired
    PaymentRestController(PaymentService paymentService) {
        this.paymentService = paymentService;
    }

    @PreAuthorize("hasAnyRole('USER','ADMIN')")
    @PostMapping(value = "/preview", consumes = MediaType.APPLICATION_JSON_VALUE,
            produces = MediaType.APPLICATION_JSON_VALUE)
    ResponseEntity<PaymentBatchPreviewDTO> previewPayment(
            @Valid @RequestBody PaymentPreviewRequestDTO dto,
            @AuthenticationPrincipal(expression = "username") String email
    ) {
        return ResponseEntity.ok(paymentService.previewPayment(dto, email));
    }

    @PreAuthorize("hasAnyRole('USER','ADMIN')")
    @PostMapping(value = "/calculation", consumes = MediaType.APPLICATION_JSON_VALUE,
            produces = MediaType.APPLICATION_JSON_VALUE)
    ResponseEntity<PaymentCalculationResponseDTO> calculatePayment(
            @Valid @RequestBody PaymentCalculationRequestDTO dto,
            @AuthenticationPrincipal(expression = "username") String email
    ) {
        return ResponseEntity.ok(paymentService.calculatePayment(dto, email));
    }

    @PreAuthorize("hasAnyRole('USER','ADMIN')")
    @PostMapping(consumes = MediaType.APPLICATION_JSON_VALUE,
            produces = MediaType.APPLICATION_JSON_VALUE)
    ResponseEntity<PaymentSubmissionDTO> registerPaymentJson(
            @Valid @RequestBody RegisterPaymentDTO dto,
            @AuthenticationPrincipal(expression = "username") String email
    ) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(paymentService.registerPayment(dto, email));
    }

    @PreAuthorize("hasAnyRole('USER','ADMIN')")
    @PostMapping(consumes = MediaType.MULTIPART_FORM_DATA_VALUE,
            produces = MediaType.APPLICATION_JSON_VALUE)
    ResponseEntity<PaymentSubmissionDTO> registerPayment(
            @RequestParam("anchorInstallmentId") Long anchorInstallmentId,
            @RequestParam("reportedAmount") BigDecimal reportedAmount,
            @RequestParam("reportedPaymentDate") @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate reportedPaymentDate,
            @RequestParam("paymentCurrency") Currency paymentCurrency,
            @RequestParam("paymentMethod") PaymentMethod paymentMethod,
            @RequestParam("bankAccountId") Long bankAccountId,
            @RequestParam(value = "file", required = false) MultipartFile file,
            @RequestParam(value = "previewToken", required = false) String previewToken,
            @AuthenticationPrincipal(expression = "username") String email
    ) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(paymentService.registerPayment(
                        anchorInstallmentId,
                        reportedAmount,
                        reportedPaymentDate,
                        paymentCurrency,
                        paymentMethod,
                        bankAccountId,
                        file,
                        previewToken,
                        email));
    }

    @PreAuthorize("hasRole('ADMIN')")
    @PatchMapping(value = "/{id}/review", produces = "application/json", consumes = "application/json")
    ResponseEntity<PaymentSubmissionDTO> reviewPayment(
            @PathVariable Long id,
            @jakarta.validation.Valid @RequestBody ReviewPaymentDTO dto,
            @AuthenticationPrincipal(expression = "username") String email
    ) {
        return ResponseEntity.ok(paymentService.reviewPayment(id, dto, email));
    }

    @PreAuthorize("hasRole('ADMIN')")
    @PostMapping(value = "/{id}/void", produces = "application/json")
    ResponseEntity<PaymentSubmissionDTO> voidPayment(
            @PathVariable Long id,
            @AuthenticationPrincipal(expression = "username") String email
    ) {
        return ResponseEntity.ok(paymentService.voidPayment(id, email));
    }

    @PreAuthorize("hasRole('ADMIN')")
    @GetMapping(value = "/installment/{installmentId}", produces = "application/json")
    ResponseEntity<List<PaymentInstallmentHistoryDTO>> getReceiptsForInstallment(@PathVariable Long installmentId) {
        return ResponseEntity.ok(paymentService.getReceiptsForInstallment(installmentId));
    }

    @PreAuthorize("hasRole('ADMIN')")
    @GetMapping(value = "/pending-review", produces = "application/json")
    ResponseEntity<List<PendingPaymentReviewDTO>> getPendingReviewReceipts() {
        return ResponseEntity.ok(paymentService.getPendingReviewReceipts());
    }

    @PreAuthorize("hasRole('USER')")
    @GetMapping(value = "/my", produces = "application/json")
    ResponseEntity<List<PaymentSubmissionDTO>> getMyReceipts(
            @AuthenticationPrincipal(expression = "username") String email
    ) {
        return ResponseEntity.ok(paymentService.getReceiptsForCurrentUser(email));
    }

    @PreAuthorize("hasRole('USER')")
    @GetMapping(value = "/my/installments", produces = "application/json")
    ResponseEntity<List<UserInstallmentDTO>> getMyInstallments(
            @AuthenticationPrincipal(expression = "username") String email
    ) {
        return ResponseEntity.ok(paymentService.getInstallmentsForCurrentUser(email));
    }
}
