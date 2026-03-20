package com.agencia.pagos.controllers;

import com.agencia.pagos.dtos.request.RegisterPaymentDTO;
import com.agencia.pagos.dtos.request.ReviewPaymentDTO;
import com.agencia.pagos.dtos.response.PaymentReceiptDTO;
import com.agencia.pagos.dtos.response.UserInstallmentDTO;
import com.agencia.pagos.entities.PaymentMethod;
import com.agencia.pagos.services.PaymentService;
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

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

@RestController
@RequestMapping("/api/v1/payments")
class PaymentRestController {

    private final PaymentService paymentService;

    @Autowired
    PaymentRestController(PaymentService paymentService) {
        this.paymentService = paymentService;
    }

        @PreAuthorize("hasAnyRole('USER','ADMIN')")
        @PostMapping(consumes = MediaType.APPLICATION_JSON_VALUE,
            produces = MediaType.APPLICATION_JSON_VALUE)
        ResponseEntity<PaymentReceiptDTO> registerPaymentJson(
            @Valid @RequestBody RegisterPaymentDTO dto,
            @AuthenticationPrincipal(expression = "username") String email
        ) {
        return ResponseEntity.status(HttpStatus.CREATED)
            .body(paymentService.registerPayment(dto, email));
        }

        @PreAuthorize("hasAnyRole('USER','ADMIN')")
        @PostMapping(consumes = MediaType.MULTIPART_FORM_DATA_VALUE,
            produces = MediaType.APPLICATION_JSON_VALUE)
    ResponseEntity<PaymentReceiptDTO> registerPayment(
            @RequestParam("installmentId") Long installmentId,
            @RequestParam("reportedAmount") BigDecimal reportedAmount,
            @RequestParam("reportedPaymentDate") @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate reportedPaymentDate,
            @RequestParam("paymentMethod") PaymentMethod paymentMethod,
            @RequestParam(value = "file", required = false) MultipartFile file,
            @AuthenticationPrincipal(expression = "username") String email
    ) {
        return ResponseEntity.status(HttpStatus.CREATED)
            .body(paymentService.registerPayment(
                installmentId,
                reportedAmount,
                reportedPaymentDate,
                paymentMethod,
                file,
                email));
    }

    @PreAuthorize("hasRole('ADMIN')")
    @PatchMapping(value = "/{id}/review", produces = "application/json", consumes = "application/json")
    ResponseEntity<PaymentReceiptDTO> reviewPayment(@PathVariable Long id, @jakarta.validation.Valid @RequestBody ReviewPaymentDTO dto) {
        return ResponseEntity.ok(paymentService.reviewPayment(id, dto));
    }

    @PreAuthorize("hasRole('ADMIN')")
    @PostMapping(value = "/{id}/void", produces = "application/json")
    ResponseEntity<PaymentReceiptDTO> voidPayment(@PathVariable Long id) {
        return ResponseEntity.ok(paymentService.voidPayment(id));
    }

    @PreAuthorize("hasRole('ADMIN')")
    @GetMapping(value = "/installment/{installmentId}", produces = "application/json")
    ResponseEntity<List<PaymentReceiptDTO>> getReceiptsForInstallment(@PathVariable Long installmentId) {
        return ResponseEntity.ok(paymentService.getReceiptsForInstallment(installmentId));
    }

    @PreAuthorize("hasRole('USER')")
    @GetMapping(value = "/my", produces = "application/json")
    ResponseEntity<List<PaymentReceiptDTO>> getMyReceipts(
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
