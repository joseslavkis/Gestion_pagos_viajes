package com.agencia.pagos.controllers;

import com.agencia.pagos.dtos.request.BankAccountActiveDTO;
import com.agencia.pagos.dtos.request.BankAccountCreateDTO;
import com.agencia.pagos.dtos.request.BankAccountUpdateDTO;
import com.agencia.pagos.dtos.response.BankAccountDTO;
import com.agencia.pagos.entities.Currency;
import com.agencia.pagos.services.BankAccountService;
import jakarta.validation.Valid;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/v1/bank-accounts")
class BankAccountRestController {

    private final BankAccountService bankAccountService;

    @Autowired
    BankAccountRestController(BankAccountService bankAccountService) {
        this.bankAccountService = bankAccountService;
    }

    @PreAuthorize("hasAnyRole('USER','ADMIN')")
    @GetMapping(produces = "application/json")
    ResponseEntity<List<BankAccountDTO>> getActiveAccounts(@RequestParam(value = "currency", required = false) Currency currency) {
        return ResponseEntity.ok(bankAccountService.getActiveAccounts(currency));
    }

    @PreAuthorize("hasRole('ADMIN')")
    @GetMapping(value = "/admin", produces = "application/json")
    ResponseEntity<List<BankAccountDTO>> getAllAccountsForAdmin() {
        return ResponseEntity.ok(bankAccountService.getAllAccountsForAdmin());
    }

    @PreAuthorize("hasRole('ADMIN')")
    @PostMapping(produces = "application/json", consumes = "application/json")
    ResponseEntity<BankAccountDTO> create(@Valid @RequestBody BankAccountCreateDTO dto) {
        return ResponseEntity.status(HttpStatus.CREATED).body(bankAccountService.create(dto));
    }

    @PreAuthorize("hasRole('ADMIN')")
    @PutMapping(value = "/{id}", produces = "application/json", consumes = "application/json")
    ResponseEntity<BankAccountDTO> update(@PathVariable Long id, @Valid @RequestBody BankAccountUpdateDTO dto) {
        return ResponseEntity.ok(bankAccountService.update(id, dto));
    }

    @PreAuthorize("hasRole('ADMIN')")
    @PatchMapping(value = "/{id}/active", produces = "application/json", consumes = "application/json")
    ResponseEntity<BankAccountDTO> updateActive(@PathVariable Long id, @Valid @RequestBody BankAccountActiveDTO dto) {
        return ResponseEntity.ok(bankAccountService.updateActive(id, dto));
    }
}
