package com.agencia.pagos.shared.web;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MaxUploadSizeExceededException;
import org.springframework.web.multipart.MultipartException;

import java.io.IOException;
import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@ExtendWith(OutputCaptureExtension.class)
class GlobalControllerExceptionHandlerTest {

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.standaloneSetup(new TransportProbe())
                .setControllerAdvice(new GlobalControllerExceptionHandler())
                .build();
    }

    @Test
    void uploadLimitReturnsSafeSpanish413() throws Exception {
        mockMvc.perform(get("/transport-probe/size"))
                .andExpect(status().isPayloadTooLarge())
                .andExpect(content().string(
                        "Los archivos adjuntos superan el tamaño permitido. Reduzca su tamaño e intente nuevamente."));
    }

    @Test
    void typeMismatchDoesNotExposeParameterOrRejectedValue() throws Exception {
        mockMvc.perform(get("/transport-probe/parameter").param("privateAmount", "SQL-secret-value"))
                .andExpect(status().isBadRequest())
                .andExpect(content().string(
                        "Los datos enviados no son válidos. Revise la información e intente nuevamente."));
    }

    @Test
    void missingParameterDoesNotExposeFrameworkMessage() throws Exception {
        mockMvc.perform(get("/transport-probe/parameter"))
                .andExpect(status().isBadRequest())
                .andExpect(content().string(
                        "Faltan datos obligatorios. Complete la información e intente nuevamente."));
    }

    @ParameterizedTest
    @ValueSource(strings = {"runtime", "multipart-io", "database", "arithmetic"})
    void internalFailuresRemainGeneric500AndAreLogged(String failure, CapturedOutput output) throws Exception {
        mockMvc.perform(get("/transport-probe/internal").param("failure", failure))
                .andExpect(status().isInternalServerError())
                .andExpect(content().string("Internal server error"));

        assertThat(output.getAll()).contains("Unhandled server error", "Synthetic internal detail");
        if (failure.equals("multipart-io")) {
            assertThat(output.getAll()).contains("MultipartException", "IOException", "Synthetic server I/O");
        }
    }

    @RestController
    static class TransportProbe {

        @GetMapping("/transport-probe/size")
        String size() {
            throw new MaxUploadSizeExceededException(5L * 1024 * 1024);
        }

        @GetMapping("/transport-probe/parameter")
        String parameter(@RequestParam("privateAmount") BigDecimal amount) {
            return amount.toPlainString();
        }

        @GetMapping("/transport-probe/internal")
        String internal(@RequestParam("failure") String failure) {
            throw switch (failure) {
                case "multipart-io" -> new MultipartException(
                        "Synthetic internal detail", new IOException("Synthetic server I/O"));
                case "database" -> new DataIntegrityViolationException("Synthetic internal detail: SQL constraint");
                case "arithmetic" -> new ArithmeticException("Synthetic internal detail: rounding necessary");
                default -> new RuntimeException("Synthetic internal detail");
            };
        }
    }
}
