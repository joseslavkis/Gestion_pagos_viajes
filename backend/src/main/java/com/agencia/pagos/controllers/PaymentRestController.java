package com.agencia.pagos.controllers;

import com.agencia.pagos.dtos.request.RegisterPaymentDTO;
import com.agencia.pagos.dtos.request.ReviewPaymentDTO;
import com.agencia.pagos.dtos.response.PaymentReceiptDTO;
import com.agencia.pagos.services.PaymentService;
import jakarta.validation.Valid;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/v1/payments")
class PaymentRestController {

    private final PaymentService paymentService;

    @Autowired
    PaymentRestController(PaymentService paymentService) {
        this.paymentService = paymentService;
    }

    @PreAuthorize("hasRole('USER')")
    @PostMapping(produces = "application/json", consumes = "application/json")
    ResponseEntity<PaymentReceiptDTO> registerPayment(
            @Valid @RequestBody RegisterPaymentDTO dto,
            @AuthenticationPrincipal(expression = "username") String email
    ) {
        return ResponseEntity.status(HttpStatus.CREATED).body(paymentService.registerPayment(dto, email));
    }

    @PreAuthorize("hasRole('ADMIN')")
    @PatchMapping(value = "/{id}/review", produces = "application/json", consumes = "application/json")
    ResponseEntity<PaymentReceiptDTO> reviewPayment(@PathVariable Long id, @Valid @RequestBody ReviewPaymentDTO dto) {
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
}
