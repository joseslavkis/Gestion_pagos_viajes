package com.agencia.pagos.controllers;

import com.agencia.pagos.dtos.request.TripCreateDTO;
import com.agencia.pagos.dtos.request.TripUpdateDTO;
import com.agencia.pagos.dtos.request.UserAssignBulkDTO;
import com.agencia.pagos.dtos.response.BulkAssignResultDTO;
import com.agencia.pagos.dtos.response.StatusResponseDTO;
import com.agencia.pagos.dtos.response.TripDetailDTO;
import com.agencia.pagos.dtos.response.TripSummaryDTO;
import com.agencia.pagos.services.TripService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/v1/trips")
@Tag(name = "2 - Trips")
class TripRestController {

    private final TripService tripService;

    @Autowired
    TripRestController(TripService tripService) {
        this.tripService = tripService;
    }

    @PreAuthorize("hasRole('ADMIN')")
    @PostMapping(produces = "application/json")
    @Operation(summary = "Create a trip (admin only)")
    @ResponseStatus(HttpStatus.CREATED)
    ResponseEntity<TripDetailDTO> createTrip(@Valid @RequestBody TripCreateDTO dto) {
        return ResponseEntity.status(HttpStatus.CREATED).body(tripService.createTrip(dto));
    }

    @PreAuthorize("hasRole('ADMIN')")
    @GetMapping(produces = "application/json")
    @Operation(summary = "List all trips (admin only)")
    List<TripSummaryDTO> getAllTrips() {
        return tripService.getAllTrips();
    }

    @PreAuthorize("hasRole('ADMIN')")
    @GetMapping(value = "/{id}", produces = "application/json")
    @Operation(summary = "Get trip details (admin only)")
    @ApiResponse(responseCode = "404", description = "Trip not found", content = @Content)
    ResponseEntity<TripDetailDTO> getTripById(@PathVariable Long id) {
        return ResponseEntity.ok(tripService.getTripById(id));
    }

    @PreAuthorize("hasRole('ADMIN')")
    @PatchMapping(value = "/{id}", produces = "application/json")
    @Operation(summary = "Update a trip (admin only)")
    @ApiResponse(responseCode = "404", description = "Trip not found", content = @Content)
    ResponseEntity<TripDetailDTO> updateTrip(
            @PathVariable Long id,
            @Valid @RequestBody TripUpdateDTO dto
    ) {
        return ResponseEntity.ok(tripService.updateTrip(id, dto));
    }

    @PreAuthorize("hasRole('ADMIN')")
    @DeleteMapping(value = "/{id}", produces = "application/json")
    @Operation(summary = "Delete a trip with no assigned users (admin only)")
    @ApiResponse(responseCode = "404", description = "Trip not found", content = @Content)
    @ApiResponse(responseCode = "409", description = "Trip has assigned users", content = @Content)
    ResponseEntity<StatusResponseDTO> deleteTrip(@PathVariable Long id) {
        tripService.deleteTrip(id);
        return ResponseEntity.ok(new StatusResponseDTO("success", "Trip deleted"));
    }

    @PreAuthorize("hasRole('ADMIN')")
    @PostMapping(value = "/{id}/users/bulk", produces = "application/json", consumes = "application/json")
    @Operation(summary = "Assign users in bulk to a trip and generate quotas (admin only)")
    @ApiResponse(responseCode = "200", description = "Users assigned", content = @Content)
    @ApiResponse(responseCode = "404", description = "Trip or User not found", content = @Content)
    ResponseEntity<BulkAssignResultDTO> assignUsersInBulk(
            @PathVariable Long id,
            @Valid @RequestBody UserAssignBulkDTO dto
    ) {
        return ResponseEntity.ok(tripService.assignUsersInBulk(id, dto));
    }
}
