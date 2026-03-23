package com.agencia.pagos.controllers;

import com.agencia.pagos.TestcontainersConfiguration;
import com.agencia.pagos.dtos.request.RegisterPaymentDTO;
import com.agencia.pagos.dtos.request.ReviewPaymentDTO;
import com.agencia.pagos.dtos.request.UserCreateDTO;
import com.agencia.pagos.dtos.response.TokenDTO;
import com.agencia.pagos.entities.BankAccount;
import com.agencia.pagos.entities.Currency;
import com.agencia.pagos.entities.Installment;
import com.agencia.pagos.entities.InstallmentStatus;
import com.agencia.pagos.entities.PaymentMethod;
import com.agencia.pagos.entities.PaymentReceipt;
import com.agencia.pagos.entities.ReceiptStatus;
import com.agencia.pagos.entities.Trip;
import com.agencia.pagos.repositories.BankAccountRepository;
import com.agencia.pagos.entities.user.User;
import com.agencia.pagos.repositories.InstallmentRepository;
import com.agencia.pagos.repositories.PaymentReceiptRepository;
import com.agencia.pagos.repositories.TripRepository;
import com.agencia.pagos.repositories.UserRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.mail.javamail.JavaMailSender;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.containsString;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(properties = {
        "app.mail.to=test@agencia.com",
        "app.mail.from=no-reply@agencia.com"
})
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
class PaymentRestControllerTest extends ControllerIntegrationTestSupport {

    @MockBean
    private JavaMailSender javaMailSender;

    @Autowired
    private TripRepository tripRepository;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private InstallmentRepository installmentRepository;

    @Autowired
    private PaymentReceiptRepository paymentReceiptRepository;

    @Autowired
    private BankAccountRepository bankAccountRepository;

    @AfterEach
    void cleanUpPayments() {
        paymentReceiptRepository.deleteAll();
        bankAccountRepository.deleteAll();
        installmentRepository.deleteAll();
        tripRepository.deleteAll();
    }

    @Test
    void registerPayment_cuotaValida_devuelve201YStatusPending() throws Exception {
        TokenDTO adminTokens = signUpAdmin(buildValidUser("admin-register-ok"));
        Installment installment = createInstallmentWithStatus("register-ok", InstallmentStatus.YELLOW);

        RegisterPaymentDTO dto = new RegisterPaymentDTO(
                installment.getId(),
                BigDecimal.valueOf(10000),
                LocalDate.now(),
                PaymentMethod.BANK_TRANSFER,
                createBankAccount(Currency.ARS).getId()
        );

        mockMvc.perform(post("/api/v1/payments")
                        .header("Authorization", "Bearer " + adminTokens.accessToken())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(dto)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").exists())
                .andExpect(jsonPath("$.installmentId").value(installment.getId()))
                .andExpect(jsonPath("$.status").value("PENDING"));
    }

    @Test
    void registerPayment_cuotaYaPagada_devuelve409() throws Exception {
        TokenDTO adminTokens = signUpAdmin(buildValidUser("admin-register-green"));
        Installment installment = createInstallmentWithStatus("register-green", InstallmentStatus.GREEN);
                installment.setPaidAmount(installment.getTotalDue());
                installment = installmentRepository.save(installment);

        RegisterPaymentDTO dto = new RegisterPaymentDTO(
                installment.getId(),
                BigDecimal.valueOf(10000),
                LocalDate.now(),
                PaymentMethod.CARD,
                createBankAccount(Currency.ARS).getId()
        );

        mockMvc.perform(post("/api/v1/payments")
                        .header("Authorization", "Bearer " + adminTokens.accessToken())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(dto)))
                .andExpect(status().isConflict());
    }

    @Test
    void registerPayment_cuotaConPendiente_devuelve409() throws Exception {
        TokenDTO adminTokens = signUpAdmin(buildValidUser("admin-register-pending"));
        Installment installment = createInstallmentWithStatus("register-pending", InstallmentStatus.YELLOW);

        paymentReceiptRepository.save(PaymentReceipt.builder()
                .installment(installment)
                .reportedAmount(BigDecimal.valueOf(9000))
                .reportedPaymentDate(LocalDate.now())
                .paymentMethod(PaymentMethod.CASH)
                .status(ReceiptStatus.PENDING)
                .fileKey("")
                .adminObservation(null)
                .build());

        RegisterPaymentDTO dto = new RegisterPaymentDTO(
                installment.getId(),
                BigDecimal.valueOf(10000),
                LocalDate.now(),
                PaymentMethod.BANK_TRANSFER,
                createBankAccount(Currency.ARS).getId()
        );

        mockMvc.perform(post("/api/v1/payments")
                        .header("Authorization", "Bearer " + adminTokens.accessToken())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(dto)))
                .andExpect(status().isConflict());
    }

    @Test
    void registerPayment_installmentNoExiste_devuelve404() throws Exception {
        TokenDTO adminTokens = signUpAdmin(buildValidUser("admin-register-404"));

        RegisterPaymentDTO dto = new RegisterPaymentDTO(
                999999L,
                BigDecimal.valueOf(10000),
                LocalDate.now(),
                PaymentMethod.OTHER,
                createBankAccount(Currency.ARS).getId()
        );

        mockMvc.perform(post("/api/v1/payments")
                        .header("Authorization", "Bearer " + adminTokens.accessToken())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(dto)))
                .andExpect(status().isNotFound());
    }

    @Test
    void reviewPayment_aprobar_marcaInstallmentGreenYDevuelve200() throws Exception {
        TokenDTO adminTokens = signUpAdmin(buildValidUser("admin-review-approve"));
        Installment installment = createInstallmentWithStatus("review-approve", InstallmentStatus.RED);

        PaymentReceipt receipt = paymentReceiptRepository.save(PaymentReceipt.builder()
                .installment(installment)
                .reportedAmount(BigDecimal.valueOf(10000))
                .reportedPaymentDate(LocalDate.now())
                .paymentMethod(PaymentMethod.BANK_TRANSFER)
                .status(ReceiptStatus.PENDING)
                .fileKey("")
                .build());

        ReviewPaymentDTO dto = new ReviewPaymentDTO(ReceiptStatus.APPROVED, null);

        mockMvc.perform(patch("/api/v1/payments/{id}/review", receipt.getId())
                        .header("Authorization", "Bearer " + adminTokens.accessToken())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(dto)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("APPROVED"));

        Installment persistedInstallment = installmentRepository.findById(installment.getId()).orElseThrow();
        assertEquals(InstallmentStatus.GREEN, persistedInstallment.getStatus());
    }

    @Test
    void reviewPayment_rechazar_sinObservacion_devuelve409() throws Exception {
        TokenDTO adminTokens = signUpAdmin(buildValidUser("admin-review-reject-no-observation"));
        Installment installment = createInstallmentWithStatus("review-reject-no-observation", InstallmentStatus.YELLOW);

        PaymentReceipt receipt = paymentReceiptRepository.save(PaymentReceipt.builder()
                .installment(installment)
                .reportedAmount(BigDecimal.valueOf(10000))
                .reportedPaymentDate(LocalDate.now())
                .paymentMethod(PaymentMethod.CARD)
                .status(ReceiptStatus.PENDING)
                .fileKey("")
                .build());

        ReviewPaymentDTO dto = new ReviewPaymentDTO(ReceiptStatus.REJECTED, "   ");

        mockMvc.perform(patch("/api/v1/payments/{id}/review", receipt.getId())
                        .header("Authorization", "Bearer " + adminTokens.accessToken())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(dto)))
                .andExpect(status().isConflict());
    }

    @Test
    void reviewPayment_rechazar_conObservacion_noMarcaGreenYDevuelve200() throws Exception {
        TokenDTO adminTokens = signUpAdmin(buildValidUser("admin-review-reject-ok"));
        Installment installment = createInstallmentWithStatus("review-reject-ok", InstallmentStatus.RED);

        PaymentReceipt receipt = paymentReceiptRepository.save(PaymentReceipt.builder()
                .installment(installment)
                .reportedAmount(BigDecimal.valueOf(10000))
                .reportedPaymentDate(LocalDate.now())
                .paymentMethod(PaymentMethod.BANK_TRANSFER)
                .status(ReceiptStatus.PENDING)
                .fileKey("")
                .build());

        ReviewPaymentDTO dto = new ReviewPaymentDTO(ReceiptStatus.REJECTED, "Comprobante ilegible");

        mockMvc.perform(patch("/api/v1/payments/{id}/review", receipt.getId())
                        .header("Authorization", "Bearer " + adminTokens.accessToken())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(dto)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("REJECTED"))
                .andExpect(jsonPath("$.adminObservation").value("Comprobante ilegible"));

        Installment persistedInstallment = installmentRepository.findById(installment.getId()).orElseThrow();
        assertEquals(InstallmentStatus.RED, persistedInstallment.getStatus());
    }

    @Test
    void reviewPayment_receiptYaRevisado_devuelve409() throws Exception {
        TokenDTO adminTokens = signUpAdmin(buildValidUser("admin-review-already-reviewed"));
        Installment installment = createInstallmentWithStatus("review-already-reviewed", InstallmentStatus.RED);

        PaymentReceipt receipt = paymentReceiptRepository.save(PaymentReceipt.builder()
                .installment(installment)
                .reportedAmount(BigDecimal.valueOf(10000))
                .reportedPaymentDate(LocalDate.now())
                .paymentMethod(PaymentMethod.BANK_TRANSFER)
                .status(ReceiptStatus.APPROVED)
                .fileKey("")
                .build());

        ReviewPaymentDTO dto = new ReviewPaymentDTO(ReceiptStatus.APPROVED, null);

        mockMvc.perform(patch("/api/v1/payments/{id}/review", receipt.getId())
                        .header("Authorization", "Bearer " + adminTokens.accessToken())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(dto)))
                .andExpect(status().isConflict());
    }

    @Test
    void voidPayment_anulaAprobado_reviertePagaYDevuelve200() throws Exception {
        TokenDTO adminTokens = signUpAdmin(buildValidUser("admin-void-ok"));
        Installment installment = createInstallmentWithStatus("void-ok", InstallmentStatus.GREEN);
                installment.setPaidAmount(installment.getTotalDue());
                installment = installmentRepository.save(installment);

        PaymentReceipt receipt = paymentReceiptRepository.save(PaymentReceipt.builder()
                .installment(installment)
                .reportedAmount(BigDecimal.valueOf(10000))
                .reportedPaymentDate(LocalDate.now())
                .paymentMethod(PaymentMethod.BANK_TRANSFER)
                .status(ReceiptStatus.APPROVED)
                .fileKey("")
                .build());

        mockMvc.perform(post("/api/v1/payments/{id}/void", receipt.getId())
                        .header("Authorization", "Bearer " + adminTokens.accessToken()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("REJECTED"))
                .andExpect(jsonPath("$.adminObservation").value("Anulado por administrador"));

        Installment persistedInstallment = installmentRepository.findById(installment.getId()).orElseThrow();
        assertEquals(InstallmentStatus.YELLOW, persistedInstallment.getStatus());
    }

    @Test
    void voidPayment_receiptNoPendienteNiAprobado_devuelve409() throws Exception {
        TokenDTO adminTokens = signUpAdmin(buildValidUser("admin-void-conflict"));
        Installment installment = createInstallmentWithStatus("void-conflict", InstallmentStatus.RED);

        PaymentReceipt receipt = paymentReceiptRepository.save(PaymentReceipt.builder()
                .installment(installment)
                .reportedAmount(BigDecimal.valueOf(10000))
                .reportedPaymentDate(LocalDate.now())
                .paymentMethod(PaymentMethod.CARD)
                .status(ReceiptStatus.REJECTED)
                .fileKey("")
                .adminObservation("Rechazado previamente")
                .build());

        mockMvc.perform(post("/api/v1/payments/{id}/void", receipt.getId())
                        .header("Authorization", "Bearer " + adminTokens.accessToken()))
                .andExpect(status().isConflict());
    }

    @Test
    void getReceiptsForInstallment_devuelveLista() throws Exception {
        TokenDTO adminTokens = signUpAdmin(buildValidUser("admin-list-receipts"));
        Installment installment = createInstallmentWithStatus("list-receipts", InstallmentStatus.YELLOW);

        PaymentReceipt first = paymentReceiptRepository.save(PaymentReceipt.builder()
                .installment(installment)
                .reportedAmount(BigDecimal.valueOf(9000))
                .reportedPaymentDate(LocalDate.now().minusDays(1))
                .paymentMethod(PaymentMethod.CASH)
                .status(ReceiptStatus.REJECTED)
                .fileKey("")
                .adminObservation("Monto incompleto")
                .build());

        PaymentReceipt second = paymentReceiptRepository.save(PaymentReceipt.builder()
                .installment(installment)
                .reportedAmount(BigDecimal.valueOf(10000))
                .reportedPaymentDate(LocalDate.now())
                .paymentMethod(PaymentMethod.BANK_TRANSFER)
                .status(ReceiptStatus.PENDING)
                .fileKey("")
                .build());

        assertNotNull(first.getId());
        assertNotNull(second.getId());

        mockMvc.perform(get("/api/v1/payments/installment/{installmentId}", installment.getId())
                        .header("Authorization", "Bearer " + adminTokens.accessToken()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(2)))
                .andExpect(jsonPath("$[0].id").value(second.getId()))
                .andExpect(jsonPath("$[1].id").value(first.getId()));
    }

    @Test
    void getMyInstallments_incluyeYellowWarningDaysDelViaje() throws Exception {
        UserCreateDTO participantDto = buildValidUser("payment-my-installments");
        TokenDTO userTokens = signUp(participantDto);
        User user = userRepository.findByEmail(participantDto.email()).orElseThrow();

        Trip trip = new Trip();
        trip.setName("Trip payment my-installments");
        trip.setCurrency(Currency.USD);
        trip.setTotalAmount(BigDecimal.valueOf(120000));
        trip.setInstallmentsCount(12);
        trip.setDueDay(10);
        trip.setYellowWarningDays(5);
        trip.setFixedFineAmount(BigDecimal.valueOf(5000));
        trip.setRetroactiveActive(false);
        trip.setFirstDueDate(LocalDate.now().plusMonths(1));
        trip.getAssignedUsers().add(user);
        trip = tripRepository.save(trip);

        Installment installment = new Installment();
        installment.setTrip(trip);
        installment.setUser(user);
        installment.setInstallmentNumber(1);
        installment.setDueDate(LocalDate.now().plusDays(10));
        installment.setCapitalAmount(BigDecimal.valueOf(10000));
        installment.setRetroactiveAmount(BigDecimal.ZERO);
        installment.setFineAmount(BigDecimal.ZERO);
        installment.setStatus(InstallmentStatus.YELLOW);
        installment.recalculateTotalDue();
        installmentRepository.save(installment);

        mockMvc.perform(get("/api/v1/payments/my/installments")
                        .header("Authorization", "Bearer " + userTokens.accessToken()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(1)))
                .andExpect(jsonPath("$[0].tripId").value(trip.getId()))
                .andExpect(jsonPath("$[0].yellowWarningDays").value(5))
                .andExpect(jsonPath("$[0].tripCurrency").value("USD"))
                .andExpect(jsonPath("$[0].installmentStatus").value("YELLOW"))
                .andExpect(jsonPath("$[0].uiStatusCode").value("UP_TO_DATE"))
                .andExpect(jsonPath("$[0].uiStatusLabel").value("Al día"))
                .andExpect(jsonPath("$[0].uiStatusTone").value("green"));
    }

    @Test
    void getMyInstallments_conComprobantePendiente_devuelveUiStatusUnderReview() throws Exception {
        signUpAdmin(buildValidUser("admin-payment-under-review"));
        UserCreateDTO participantDto = buildValidUser("payment-under-review-user");
        TokenDTO userTokens = signUp(participantDto);
        User user = userRepository.findByEmail(participantDto.email()).orElseThrow();

        Trip trip = new Trip();
        trip.setName("Trip payment under-review");
        trip.setCurrency(Currency.ARS);
        trip.setTotalAmount(BigDecimal.valueOf(120000));
        trip.setInstallmentsCount(12);
        trip.setDueDay(10);
        trip.setYellowWarningDays(5);
        trip.setFixedFineAmount(BigDecimal.valueOf(5000));
        trip.setRetroactiveActive(false);
        trip.setFirstDueDate(LocalDate.now().plusMonths(1));
        trip.getAssignedUsers().add(user);
        trip = tripRepository.save(trip);

        Installment installment = new Installment();
        installment.setTrip(trip);
        installment.setUser(user);
        installment.setInstallmentNumber(1);
        installment.setDueDate(LocalDate.now().plusDays(2));
        installment.setCapitalAmount(BigDecimal.valueOf(10000));
        installment.setRetroactiveAmount(BigDecimal.ZERO);
        installment.setFineAmount(BigDecimal.ZERO);
        installment.setStatus(InstallmentStatus.YELLOW);
        installment.recalculateTotalDue();
        installment = installmentRepository.save(installment);

        paymentReceiptRepository.save(PaymentReceipt.builder()
                .installment(installment)
                .bankAccount(createBankAccount(Currency.ARS))
                .reportedAmount(BigDecimal.valueOf(10000))
                .reportedPaymentDate(LocalDate.now())
                .paymentMethod(PaymentMethod.BANK_TRANSFER)
                .status(ReceiptStatus.PENDING)
                .fileKey("")
                .build());

        mockMvc.perform(get("/api/v1/payments/my/installments")
                        .header("Authorization", "Bearer " + userTokens.accessToken()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].uiStatusCode").value("UNDER_REVIEW"))
                .andExpect(jsonPath("$[0].uiStatusLabel").value("En revisión"))
                .andExpect(jsonPath("$[0].uiStatusTone").value("yellow"));
    }

    @Test
    void registerPayment_cuentaInactiva_devuelve400() throws Exception {
        TokenDTO adminTokens = signUpAdmin(buildValidUser("admin-register-bank-inactive"));
        Installment installment = createInstallmentWithStatus("register-bank-inactive", InstallmentStatus.YELLOW);
        BankAccount bankAccount = createBankAccount(Currency.ARS);
        bankAccount.setActive(false);
        bankAccount = bankAccountRepository.save(bankAccount);

        RegisterPaymentDTO dto = new RegisterPaymentDTO(
                installment.getId(),
                BigDecimal.valueOf(10000),
                LocalDate.now(),
                PaymentMethod.BANK_TRANSFER,
                bankAccount.getId()
        );

        mockMvc.perform(post("/api/v1/payments")
                        .header("Authorization", "Bearer " + adminTokens.accessToken())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(dto)))
                .andExpect(status().isBadRequest());
    }

    @Test
    void registerPayment_cuentaConMonedaDistinta_devuelve400() throws Exception {
        TokenDTO adminTokens = signUpAdmin(buildValidUser("admin-register-bank-currency"));
        Installment installment = createInstallmentWithStatus("register-bank-currency", InstallmentStatus.YELLOW);

        RegisterPaymentDTO dto = new RegisterPaymentDTO(
                installment.getId(),
                BigDecimal.valueOf(10000),
                LocalDate.now(),
                Currency.USD,
                PaymentMethod.BANK_TRANSFER,
                createBankAccount(Currency.ARS).getId()
        );

        mockMvc.perform(post("/api/v1/payments")
                        .header("Authorization", "Bearer " + adminTokens.accessToken())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(dto)))
                .andExpect(status().isBadRequest());
    }

    @Test
    void registerPayment_persisteCuentaBancariaSeleccionada() throws Exception {
        TokenDTO adminTokens = signUpAdmin(buildValidUser("admin-register-bank-ok"));
        Installment installment = createInstallmentWithStatus("register-bank-ok", InstallmentStatus.YELLOW);
        BankAccount bankAccount = createBankAccount(Currency.ARS);

        RegisterPaymentDTO dto = new RegisterPaymentDTO(
                installment.getId(),
                BigDecimal.valueOf(10000),
                LocalDate.now(),
                PaymentMethod.BANK_TRANSFER,
                bankAccount.getId()
        );

        mockMvc.perform(post("/api/v1/payments")
                        .header("Authorization", "Bearer " + adminTokens.accessToken())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(dto)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.bankAccountId").value(bankAccount.getId()))
                .andExpect(jsonPath("$.bankAccountAlias").value(bankAccount.getAlias()));
    }

    @Test
    void getPendingReview_siendoAdmin_devuelveSoloPendientesConContexto() throws Exception {
        TokenDTO adminTokens = signUpAdmin(buildValidUser("admin-pending-review"));
        Installment installment = createInstallmentWithStatus("pending-review", InstallmentStatus.YELLOW);
        BankAccount bankAccount = createBankAccount(Currency.ARS);

        paymentReceiptRepository.save(PaymentReceipt.builder()
                .installment(installment)
                .bankAccount(bankAccount)
                .reportedAmount(BigDecimal.valueOf(9000))
                .reportedPaymentDate(LocalDate.now().minusDays(1))
                .paymentMethod(PaymentMethod.CASH)
                .status(ReceiptStatus.REJECTED)
                .fileKey("")
                .build());

        PaymentReceipt pendingReceipt = paymentReceiptRepository.save(PaymentReceipt.builder()
                .installment(installment)
                .bankAccount(bankAccount)
                .reportedAmount(BigDecimal.valueOf(10000))
                .reportedPaymentDate(LocalDate.now())
                .paymentMethod(PaymentMethod.BANK_TRANSFER)
                .status(ReceiptStatus.PENDING)
                .fileKey("")
                .build());

        mockMvc.perform(get("/api/v1/payments/pending-review")
                        .header("Authorization", "Bearer " + adminTokens.accessToken()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(1)))
                .andExpect(jsonPath("$[0].receiptId").value(pendingReceipt.getId()))
                .andExpect(jsonPath("$[0].tripName").value(installment.getTrip().getName()))
                .andExpect(jsonPath("$[0].installmentNumber").value(installment.getInstallmentNumber()))
                .andExpect(jsonPath("$[0].userEmail").value(installment.getUser().getEmail()))
                .andExpect(jsonPath("$[0].bankAccountId").value(bankAccount.getId()))
                .andExpect(jsonPath("$[0].bankAccountAlias").value(bankAccount.getAlias()));
    }

    @Test
    void getPendingReview_sinAutenticacion_devuelve401() throws Exception {
        mockMvc.perform(get("/api/v1/payments/pending-review"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void getPendingReview_siendoUserNormal_devuelve403() throws Exception {
        TokenDTO userTokens = signUp(buildValidUser("user-pending-review-forbidden"));

        mockMvc.perform(get("/api/v1/payments/pending-review")
                        .header("Authorization", "Bearer " + userTokens.accessToken()))
                .andExpect(status().isForbidden());
    }

    private Installment createInstallmentWithStatus(String prefix, InstallmentStatus status) throws Exception {
        UserCreateDTO participantDto = buildValidUser("payment-user-" + prefix);
        signUp(participantDto);
        User user = userRepository.findByEmail(participantDto.email()).orElseThrow();

        Trip trip = new Trip();
        trip.setName("Trip payment " + prefix);
        trip.setTotalAmount(BigDecimal.valueOf(120000));
        trip.setInstallmentsCount(12);
        trip.setDueDay(10);
        trip.setYellowWarningDays(5);
        trip.setFixedFineAmount(BigDecimal.valueOf(5000));
        trip.setRetroactiveActive(false);
        trip.setFirstDueDate(LocalDate.now().plusMonths(1));
        trip.getAssignedUsers().add(user);
        trip = tripRepository.save(trip);

        Installment installment = new Installment();
        installment.setTrip(trip);
        installment.setUser(user);
        installment.setInstallmentNumber(1);
        installment.setDueDate(LocalDate.now().plusDays(10));
        installment.setCapitalAmount(BigDecimal.valueOf(10000));
        installment.setRetroactiveAmount(BigDecimal.ZERO);
        installment.setFineAmount(BigDecimal.ZERO);
        installment.setStatus(status);
        installment.recalculateTotalDue();
        return installmentRepository.save(installment);
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
