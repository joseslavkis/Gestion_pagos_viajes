package com.agencia.pagos.school;

import com.agencia.pagos.school.dto.SchoolCreateDTO;
import com.agencia.pagos.school.dto.SchoolDTO;
import com.agencia.pagos.school.SchoolService;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/v1/admin/schools")
class SchoolAdminRestController {

    private final SchoolService schoolService;

    SchoolAdminRestController(SchoolService schoolService) {
        this.schoolService = schoolService;
    }

    @PreAuthorize("hasRole('ADMIN')")
    @GetMapping(produces = "application/json")
    ResponseEntity<List<SchoolDTO>> getAll() {
        return ResponseEntity.ok(schoolService.getAll());
    }

    @PreAuthorize("hasRole('ADMIN')")
    @PostMapping(produces = "application/json", consumes = "application/json")
    ResponseEntity<SchoolDTO> create(@Valid @RequestBody SchoolCreateDTO dto) {
        return ResponseEntity.status(HttpStatus.CREATED).body(schoolService.create(dto));
    }
}
