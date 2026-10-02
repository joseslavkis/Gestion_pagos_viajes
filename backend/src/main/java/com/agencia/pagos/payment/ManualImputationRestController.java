package com.agencia.pagos.payment;

import com.agencia.pagos.payment.dto.ManualImputationContextDTO;
import com.agencia.pagos.payment.dto.ManualImputationRequestDTO;
import com.agencia.pagos.payment.dto.PaymentSubmissionDTO;
import com.agencia.pagos.shared.money.Currency;
import jakarta.validation.Valid;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

@RestController
@RequestMapping("/api/v1/payments/manual-imputations")
class ManualImputationRestController {

    private final ManualImputationService manualImputationService;

    ManualImputationRestController(ManualImputationService manualImputationService) {
        this.manualImputationService = manualImputationService;
    }

    @PreAuthorize("hasRole('ADMIN')")
    @GetMapping(value = "/context", produces = "application/json")
    ResponseEntity<ManualImputationContextDTO> getContext(
            @RequestParam Long installmentId,
            @AuthenticationPrincipal(expression = "username") String email) {
        return ResponseEntity.ok(manualImputationService.getContext(installmentId, email));
    }

    @PreAuthorize("hasRole('ADMIN')")
    @PostMapping(consumes = MediaType.APPLICATION_JSON_VALUE, produces = MediaType.APPLICATION_JSON_VALUE)
    ResponseEntity<PaymentSubmissionDTO> executeJson(
            @Valid @RequestBody ManualImputationRequestDTO dto,
            @AuthenticationPrincipal(expression = "username") String email) {
        PaymentSubmissionDTO result = manualImputationService.execute(
                dto.anchorInstallmentId(),
                dto.reportedAmount(),
                dto.reportedPaymentDate(),
                dto.paymentCurrency(),
                dto.previewToken(),
                dto.reason(),
                List.of(),
                email);
        return ResponseEntity.status(HttpStatus.CREATED).body(result);
    }

    @PreAuthorize("hasRole('ADMIN')")
    @PostMapping(consumes = MediaType.MULTIPART_FORM_DATA_VALUE, produces = MediaType.APPLICATION_JSON_VALUE)
    ResponseEntity<PaymentSubmissionDTO> executeMultipart(
            @RequestParam("anchorInstallmentId") Long anchorInstallmentId,
            @RequestParam("reportedAmount") BigDecimal reportedAmount,
            @RequestParam("reportedPaymentDate") @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate reportedPaymentDate,
            @RequestParam("paymentCurrency") Currency paymentCurrency,
            @RequestParam("previewToken") String previewToken,
            @RequestParam(value = "reason", required = false) String reason,
            @RequestParam(value = "file", required = false) MultipartFile file,
            @AuthenticationPrincipal(expression = "username") String email) {
        List<MultipartFile> files = file == null || file.isEmpty() ? List.of() : List.of(file);
        PaymentSubmissionDTO result = manualImputationService.execute(
                anchorInstallmentId,
                reportedAmount,
                reportedPaymentDate,
                paymentCurrency,
                previewToken,
                reason,
                files,
                email);
        return ResponseEntity.status(HttpStatus.CREATED).body(result);
    }
}
