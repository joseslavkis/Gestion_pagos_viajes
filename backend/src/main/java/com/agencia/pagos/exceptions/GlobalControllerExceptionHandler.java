package com.agencia.pagos.exceptions;

import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import jakarta.persistence.EntityNotFoundException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@RestControllerAdvice
public class GlobalControllerExceptionHandler {

    private static final Logger logger = LoggerFactory.getLogger(GlobalControllerExceptionHandler.class);

    @ExceptionHandler(MethodArgumentNotValidException.class)
    @ApiResponse(
            responseCode = "400",
            description = "Invalid arguments supplied",
            content = @Content(
                    mediaType = "application/json",
                    schema = @Schema(example = "{\"errors\": [\"name: size must be between 2 and 100\"]}")
            )
    )
    public ResponseEntity<Map<String, List<String>>> handleMethodArgumentInvalid(MethodArgumentNotValidException ex) {
        List<String> errors = ex.getBindingResult().getFieldErrors().stream()
                .map(FieldError::getField)
                .collect(Collectors.toSet())
                .stream()
                .flatMap(field -> ex.getBindingResult().getFieldErrors(field).stream()
                        .map(fe -> fe.getField() + ": " + fe.getDefaultMessage()))
                .collect(Collectors.toList());
        return ResponseEntity.badRequest().body(Map.of("errors", errors));
    }

    @ExceptionHandler(value = EntityNotFoundException.class, produces = "text/plain")
    @ApiResponse(
            responseCode = "404",
            description = "Referenced entity not found",
            content = @Content(
                    mediaType = "text/plain",
                    schema = @Schema(implementation = String.class, example = "Failed to find foo with id 42")
            )
    )
    public ResponseEntity<String> handleItemNotFound(EntityNotFoundException ex) {
        return new ResponseEntity<>(ex.getMessage(), HttpStatus.NOT_FOUND);
    }

    @ExceptionHandler(AccessDeniedException.class)
    @ApiResponse(responseCode = "403", description = "Invalid jwt access token supplied", content = @Content)
    public ResponseEntity<String> handleAccessDenied(AccessDeniedException ex) {
        return new ResponseEntity<>(ex.getMessage(), HttpStatus.FORBIDDEN);
    }

    @ExceptionHandler(ResponseStatusException.class)
    public ResponseEntity<String> handleResponseStatusException(ResponseStatusException ex) {
        String message = ex.getReason() != null ? ex.getReason() : ex.getMessage();
        return new ResponseEntity<>(message, ex.getStatusCode());
    }

    @ExceptionHandler(IllegalStateException.class)
    public ResponseEntity<String> handleIllegalState(IllegalStateException ex) {
        return new ResponseEntity<>(ex.getMessage(), HttpStatus.CONFLICT);
    }

    @ExceptionHandler(Throwable.class)
    public ResponseEntity<String> handleFallback(Throwable ex) {
        logger.error("Unhandled server error", ex);
        return new ResponseEntity<>("Internal server error", HttpStatus.INTERNAL_SERVER_ERROR);
    }
}