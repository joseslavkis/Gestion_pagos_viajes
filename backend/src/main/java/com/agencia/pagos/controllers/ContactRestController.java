package com.agencia.pagos.controllers;

import com.agencia.pagos.dtos.request.ContactMessageDTO;
import com.agencia.pagos.dtos.response.StatusResponseDTO;
import com.agencia.pagos.services.EmailService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/contact")
@Tag(name = "Contact")
class ContactRestController {

    private final EmailService emailService;

    ContactRestController(EmailService emailService) {
        this.emailService = emailService;
    }

    @PostMapping(value = "/send", produces = "application/json")
    @Operation(summary = "Send a contact message to the agency mailbox")
    ResponseEntity<StatusResponseDTO> send(@Valid @RequestBody ContactMessageDTO dto) {
        emailService.sendContactMessage(dto);
        return ResponseEntity.accepted().body(new StatusResponseDTO("success", "Message queued"));
    }
}

