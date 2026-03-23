package com.agencia.pagos.controllers;

import com.agencia.pagos.TestcontainersConfiguration;
import com.agencia.pagos.dtos.request.BankAccountActiveDTO;
import com.agencia.pagos.dtos.request.BankAccountCreateDTO;
import com.agencia.pagos.dtos.request.BankAccountUpdateDTO;
import com.agencia.pagos.dtos.response.TokenDTO;
import com.agencia.pagos.entities.BankAccount;
import com.agencia.pagos.entities.Currency;
import com.agencia.pagos.repositories.BankAccountRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;

import static org.hamcrest.Matchers.hasSize;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
class BankAccountRestControllerTest extends ControllerIntegrationTestSupport {

    @Autowired
    private BankAccountRepository bankAccountRepository;

    @AfterEach
    void cleanUpBankAccounts() {
        bankAccountRepository.deleteAll();
    }

    @Test
    void create_siendoAdmin_devuelve201() throws Exception {
        TokenDTO adminTokens = signUpAdmin(buildValidUser("admin-bank-account-create"));

        BankAccountCreateDTO dto = new BankAccountCreateDTO(
                "Banco ICBC",
                "Cuenta en pesos",
                "Proyecto VA SRL",
                "0849/02102577/27",
                "30-71131646-5",
                "0150849702000102577271",
                "CUERVO.J23",
                Currency.ARS,
                1
        );

        mockMvc.perform(post("/api/v1/bank-accounts")
                        .header("Authorization", "Bearer " + adminTokens.accessToken())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(dto)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.bankName").value("Banco ICBC"))
                .andExpect(jsonPath("$.currency").value("ARS"))
                .andExpect(jsonPath("$.active").value(true));
    }

    @Test
    void create_sinAutenticacion_devuelve401() throws Exception {
        BankAccountCreateDTO dto = new BankAccountCreateDTO(
                "Banco ICBC",
                "Cuenta en pesos",
                "Proyecto VA SRL",
                "0849/02102577/27",
                "30-71131646-5",
                "0150849702000102577271",
                "CUERVO.J23",
                Currency.ARS,
                1
        );

        mockMvc.perform(post("/api/v1/bank-accounts")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(dto)))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void getAllAccountsForAdmin_siendoUser_devuelve403() throws Exception {
        TokenDTO userTokens = signUp(buildValidUser("user-bank-account-admin-forbidden"));

        mockMvc.perform(get("/api/v1/bank-accounts/admin")
                        .header("Authorization", "Bearer " + userTokens.accessToken()))
                .andExpect(status().isForbidden());
    }

    @Test
    void update_siendoAdmin_devuelve200() throws Exception {
        TokenDTO adminTokens = signUpAdmin(buildValidUser("admin-bank-account-update"));
        BankAccount bankAccount = createBankAccount(Currency.ARS);

        BankAccountUpdateDTO dto = new BankAccountUpdateDTO(
                "Banco Galicia",
                "Cuenta corriente",
                "Proyecto VA SRL",
                "9750147-1367-1",
                "30-71131646-5",
                "0070367131009750147115",
                "PROYECTO.VA.DOLAR",
                Currency.USD,
                4
        );

        mockMvc.perform(put("/api/v1/bank-accounts/{id}", bankAccount.getId())
                        .header("Authorization", "Bearer " + adminTokens.accessToken())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(dto)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.bankName").value("Banco Galicia"))
                .andExpect(jsonPath("$.currency").value("USD"))
                .andExpect(jsonPath("$.displayOrder").value(4));
    }

    @Test
    void updateActive_siendoAdmin_devuelve200() throws Exception {
        TokenDTO adminTokens = signUpAdmin(buildValidUser("admin-bank-account-active"));
        BankAccount bankAccount = createBankAccount(Currency.ARS);

        mockMvc.perform(patch("/api/v1/bank-accounts/{id}/active", bankAccount.getId())
                        .header("Authorization", "Bearer " + adminTokens.accessToken())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new BankAccountActiveDTO(false))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.active").value(false));
    }

    @Test
    void getActiveAccounts_filtraActivasPorMoneda() throws Exception {
        TokenDTO adminTokens = signUpAdmin(buildValidUser("admin-bank-account-list"));
        BankAccount arsActive = createBankAccount(Currency.ARS);
        BankAccount usdActive = createBankAccount(Currency.USD);
        BankAccount arsInactive = createBankAccount(Currency.ARS);
        arsInactive.setActive(false);
        bankAccountRepository.save(arsInactive);

        mockMvc.perform(get("/api/v1/bank-accounts")
                        .header("Authorization", "Bearer " + adminTokens.accessToken())
                        .param("currency", "ARS"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(1)))
                .andExpect(jsonPath("$[0].id").value(arsActive.getId()))
                .andExpect(jsonPath("$[0].currency").value("ARS"));

        mockMvc.perform(get("/api/v1/bank-accounts/admin")
                        .header("Authorization", "Bearer " + adminTokens.accessToken()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(3)))
                .andExpect(jsonPath("$[?(@.id == " + usdActive.getId() + ")]").exists());
    }

    private BankAccount createBankAccount(Currency currency) {
        return bankAccountRepository.save(BankAccount.builder()
                .bankName(currency == Currency.USD ? "Banco Galicia" : "Banco ICBC")
                .accountLabel(currency == Currency.USD ? "Cuenta en dolares" : "Cuenta en pesos")
                .accountHolder("Proyecto VA SRL")
                .accountNumber("0001-" + System.nanoTime())
                .taxId("30-71131646-5")
                .cbu(String.valueOf(System.nanoTime()))
                .alias("ALIAS." + System.nanoTime())
                .currency(currency)
                .active(true)
                .displayOrder(1)
                .build());
    }
}
