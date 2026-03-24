package com.agencia.pagos.controllers;

import com.agencia.pagos.dtos.response.SchoolDTO;
import com.agencia.pagos.services.SchoolService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/v1/schools")
class SchoolRestController {

    private final SchoolService schoolService;

    SchoolRestController(SchoolService schoolService) {
        this.schoolService = schoolService;
    }

    @GetMapping(produces = "application/json")
    ResponseEntity<List<SchoolDTO>> getAll() {
        return ResponseEntity.ok(schoolService.getAll());
    }
}
