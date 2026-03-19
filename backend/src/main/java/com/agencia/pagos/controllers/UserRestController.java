package com.agencia.pagos.controllers;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import com.agencia.pagos.dtos.request.UserCreateDTO;
import com.agencia.pagos.dtos.request.UserUpdateDTO;
import com.agencia.pagos.dtos.response.StatusResponseDTO;
import com.agencia.pagos.dtos.response.TokenDTO;
import com.agencia.pagos.dtos.response.UserProfileDTO;
import com.agencia.pagos.entities.Role;
import com.agencia.pagos.services.UserService;

@RestController
@RequestMapping("/api/v1/users")
@Tag(name = "1 - Users")
class UserRestController {
    private final UserService userService;

    @Autowired
    UserRestController(UserService userService) {
        this.userService = userService;
    }

    @PreAuthorize("isAuthenticated()")
    @GetMapping(value = "/profile/{id}", produces = "application/json")
    @Operation(summary = "View a user's profile by ID")
    @ResponseStatus(HttpStatus.OK)
    @ApiResponse(responseCode = "404", description = "User not found", content = @Content)
    @ApiResponse(responseCode = "403", description = "Access denied", content = @Content)
    @ApiResponse(responseCode = "401", description = "Unauthorized", content = @Content)
    ResponseEntity<UserProfileDTO> viewProfile(
                        @PathVariable Long id,
                        @AuthenticationPrincipal(expression = "username") String email
    ) {
        return ResponseEntity.ok(userService.getProfileWithAuthorization(id, email));
    }

    @PreAuthorize("isAuthenticated()")
    @DeleteMapping(value = "/delete/me", produces = "application/json")
    @Operation(summary = "Delete yourself")
    @ResponseStatus(HttpStatus.OK)
    @ApiResponse(responseCode = "401", description = "Unauthorized", content = @Content)
    @ApiResponse(responseCode = "404", description = "User not found", content = @Content)
    ResponseEntity<StatusResponseDTO> deleteUser(
            @AuthenticationPrincipal(expression = "username") String email
    ) {
        var currentUser = userService.getUserByEmail(email);
        return userService.deactivateUser(currentUser.getId())
                .map(user -> ResponseEntity.ok(new StatusResponseDTO("success", "User deactivated")))
                .orElseGet(() -> ResponseEntity.status(HttpStatus.NOT_FOUND)
                        .body(new StatusResponseDTO("error", "User not found")));
    }

    @PreAuthorize("hasRole('ADMIN')")
    @PatchMapping(value = "/admin/update/{id}", produces = "application/json")
    @Operation(summary = "Update an admin (admin only)")
    @ResponseStatus(HttpStatus.OK)
    @ApiResponse(responseCode = "401", description = "Unauthorized", content = @Content)
    @ApiResponse(responseCode = "404", description = "User not found", content = @Content)
        ResponseEntity<StatusResponseDTO> updateAdmin(
            @PathVariable Long id,
                        @Valid @RequestBody UserUpdateDTO userDTO
    ) {
        userService.updateAdmin(id, userDTO);
        return ResponseEntity.ok(new StatusResponseDTO("success", "Admin updated"));
    }

    @PreAuthorize("hasRole('ADMIN')")
    @PostMapping(value = "/admin/create", produces = "application/json")
    @Operation(summary = "Create an admin (admin only)")
    @ResponseStatus(HttpStatus.OK)
    @ApiResponse(responseCode = "401", description = "Unauthorized", content = @Content)
    @ApiResponse(responseCode = "409", description = "Email already register", content = @Content)
    ResponseEntity<TokenDTO> createAdmin(
            @Valid @RequestBody UserCreateDTO userDTO
    ) {
        return userService.createUser(userDTO, Role.ADMIN)
                .map(tk -> ResponseEntity.status(HttpStatus.CREATED).body(tk))
                .orElse(ResponseEntity.status(HttpStatus.CONFLICT).build());
    }

    @PreAuthorize("isAuthenticated()")
    @PatchMapping(value = "/update/me", produces = "application/json")
    @Operation(summary = "Update yourself")
    @ResponseStatus(HttpStatus.OK)
    @ApiResponse(responseCode = "401", description = "Unauthorized", content = @Content)
    @ApiResponse(responseCode = "404", description = "User not found", content = @Content)
    ResponseEntity<StatusResponseDTO> updateUser(
            @Valid @RequestBody UserUpdateDTO userDTO,
            @AuthenticationPrincipal(expression = "username") String email
    ) {
        var currentUser = userService.getUserByEmail(email);
        userService.updateUser(userDTO, currentUser.getId());
        return ResponseEntity.ok(new StatusResponseDTO("success", "User updated"));
    }

    @PreAuthorize("hasRole('ADMIN')")
    @DeleteMapping(value = "/admin/delete/{id}", produces = "application/json")
    @Operation(summary = "Delete a user or admin (admin only)")
    @ResponseStatus(HttpStatus.OK)
    @ApiResponse(responseCode = "401", description = "Unauthorized", content = @Content)
    @ApiResponse(responseCode = "404", description = "User not found", content = @Content)
    ResponseEntity<StatusResponseDTO> deleteUser(
            @PathVariable Long id
    ) {
        return userService.deactivateUser(id)
                .map(user -> ResponseEntity.ok(new StatusResponseDTO("success", "User deactivated")))
                .orElseGet(() -> ResponseEntity.status(HttpStatus.NOT_FOUND)
                        .body(new StatusResponseDTO("error", "User not found")));
    }
}