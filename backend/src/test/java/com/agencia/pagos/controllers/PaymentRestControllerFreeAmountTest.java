package com.agencia.pagos.controllers;

import com.agencia.pagos.TestcontainersConfiguration;
import com.agencia.pagos.dtos.request.UserCreateDTO;
import com.agencia.pagos.dtos.response.TokenDTO;
import com.agencia.pagos.entities.BankAccount;
import com.agencia.pagos.entities.Currency;
import com.agencia.pagos.entities.Installment;
import com.agencia.pagos.entities.InstallmentStatus;
import com.agencia.pagos.entities.Student;
import com.agencia.pagos.entities.Trip;
import com.agencia.pagos.entities.user.User;
import com.agencia.pagos.repositories.BankAccountRepository;
import com.agencia.pagos.repositories.InstallmentRepository;
import com.agencia.pagos.repositories.PaymentSubmissionRepository;
import com.agencia.pagos.repositories.StudentRepository;
import com.agencia.pagos.repositories.TripRepository;
import com.agencia.pagos.repositories.UserRepository;
import com.agencia.pagos.services.storage.PaymentAttachmentStorageService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mock.web.MockMultipartFile;

import java.math.BigDecimal;
import java.time.LocalDate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.BDDMockito.given;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
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
class PaymentRestControllerFreeAmountTest extends ControllerIntegrationTestSupport {

    private record PaymentFixture(TokenDTO userTokens, User user, Student student, Trip trip) {}

    @MockBean
    private JavaMailSender javaMailSender;

    @MockBean
    private com.agencia.pagos.services.ExchangeRateService exchangeRateService;

    @MockBean
    private PaymentAttachmentStorageService paymentAttachmentStorageService;

    @Autowired
    private TripRepository tripRepository;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private InstallmentRepository installmentRepository;

    @Autowired
    private BankAccountRepository bankAccountRepository;

    @Autowired
    private StudentRepository studentRepository;

    @Autowired
    private PaymentSubmissionRepository paymentSubmissionRepository;

    @Test
    void previewPayment_montoLibreDevuelveImputacionSecuencial() throws Exception {
        PaymentFixture fixture = createPaymentFixture("payment-free-preview", Currency.ARS);
        Installment first = createInstallment(fixture.trip(), fixture.user(), fixture.student(), 1, "100.00", InstallmentStatus.YELLOW);
        createInstallment(fixture.trip(), fixture.user(), fixture.student(), 2, "100.00", InstallmentStatus.YELLOW);
        createInstallment(fixture.trip(), fixture.user(), fixture.student(), 3, "100.00", InstallmentStatus.YELLOW);

        mockMvc.perform(post("/api/v1/payments/preview")
                        .header("Authorization", "Bearer " + fixture.userTokens().accessToken())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "anchorInstallmentId": %d,
                                  "reportedAmount": 250.00,
                                  "reportedPaymentDate": "%s",
                                  "paymentCurrency": "ARS"
                                }
                                """.formatted(first.getId(), LocalDate.now())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.anchorInstallmentId").value(first.getId()))
                .andExpect(jsonPath("$.reportedAmount").value(250))
                .andExpect(jsonPath("$.maxAllowedAmount").value(300))
                .andExpect(jsonPath("$.totalPendingAmountInTripCurrency").value(300))
                .andExpect(jsonPath("$.amountInTripCurrency").value(250))
                .andExpect(jsonPath("$.installments.length()").value(3))
                .andExpect(jsonPath("$.installments[0].installmentNumber").value(1))
                .andExpect(jsonPath("$.installments[0].amountInTripCurrency").value(100))
                .andExpect(jsonPath("$.installments[1].installmentNumber").value(2))
                .andExpect(jsonPath("$.installments[1].amountInTripCurrency").value(100))
                .andExpect(jsonPath("$.installments[2].installmentNumber").value(3))
                .andExpect(jsonPath("$.installments[2].amountInTripCurrency").value(50));
    }

    @Test
    void previewPayment_conPagosParcialesPrevios() throws Exception {
        PaymentFixture fixture = createPaymentFixture("pmt-free-prev", Currency.ARS);
        Installment first = createInstallment(fixture.trip(), fixture.user(), fixture.student(), 1, "100.00", InstallmentStatus.YELLOW);
        first.setPaidAmount(new BigDecimal("30.00"));
        installmentRepository.save(first);
        
        createInstallment(fixture.trip(), fixture.user(), fixture.student(), 2, "100.00", InstallmentStatus.YELLOW);

        mockMvc.perform(post("/api/v1/payments/preview")
                        .header("Authorization", "Bearer " + fixture.userTokens().accessToken())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "anchorInstallmentId": %d,
                                  "reportedAmount": 120.00,
                                  "reportedPaymentDate": "%s",
                                  "paymentCurrency": "ARS"
                                }
                                """.formatted(first.getId(), LocalDate.now())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalPendingAmountInTripCurrency").value(170))
                .andExpect(jsonPath("$.installments.length()").value(2))
                .andExpect(jsonPath("$.installments[0].installmentNumber").value(1))
                .andExpect(jsonPath("$.installments[0].amountInTripCurrency").value(70))
                .andExpect(jsonPath("$.installments[1].installmentNumber").value(2))
                .andExpect(jsonPath("$.installments[1].amountInTripCurrency").value(50));
    }

    @Test
    void previewPayment_monedaDistintaAplicaTipoDeCambio() throws Exception {
        PaymentFixture fixture = createPaymentFixture("pmt-free-usd", Currency.USD);
        Installment first = createInstallment(fixture.trip(), fixture.user(), fixture.student(), 1, "100.00", InstallmentStatus.YELLOW);
        createInstallment(fixture.trip(), fixture.user(), fixture.student(), 2, "100.00", InstallmentStatus.YELLOW);

        org.mockito.Mockito.when(exchangeRateService.getOfficialRateForDate(org.mockito.ArgumentMatchers.any(LocalDate.class)))
                .thenReturn(new BigDecimal("1000.00"));

        mockMvc.perform(post("/api/v1/payments/preview")
                        .header("Authorization", "Bearer " + fixture.userTokens().accessToken())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "anchorInstallmentId": %d,
                                  "reportedAmount": 150000.00,
                                  "reportedPaymentDate": "%s",
                                  "paymentCurrency": "ARS",
                                  "exchangeRate": 1000.00
                                }
                                """.formatted(first.getId(), LocalDate.now())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.amountInTripCurrency").value(150))
                .andExpect(jsonPath("$.totalPendingAmountInTripCurrency").value(200))
                .andExpect(jsonPath("$.installments.length()").value(2))
                .andExpect(jsonPath("$.installments[0].installmentNumber").value(1))
                .andExpect(jsonPath("$.installments[0].amountInTripCurrency").value(100))
                .andExpect(jsonPath("$.installments[1].installmentNumber").value(2))
                .andExpect(jsonPath("$.installments[1].amountInTripCurrency").value(50));
    }

    @Test
    void previewPayment_montoExactoATotalCancelaTodas() throws Exception {
        PaymentFixture fixture = createPaymentFixture("pmt-free-exact", Currency.ARS);
        Installment first = createInstallment(fixture.trip(), fixture.user(), fixture.student(), 1, "100.00", InstallmentStatus.YELLOW);
        createInstallment(fixture.trip(), fixture.user(), fixture.student(), 2, "100.00", InstallmentStatus.YELLOW);
        createInstallment(fixture.trip(), fixture.user(), fixture.student(), 3, "100.00", InstallmentStatus.YELLOW);

        mockMvc.perform(post("/api/v1/payments/preview")
                        .header("Authorization", "Bearer " + fixture.userTokens().accessToken())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "anchorInstallmentId": %d,
                                  "reportedAmount": 300.00,
                                  "reportedPaymentDate": "%s",
                                  "paymentCurrency": "ARS"
                                }
                                """.formatted(first.getId(), LocalDate.now())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.amountInTripCurrency").value(300))
                .andExpect(jsonPath("$.installments.length()").value(3))
                .andExpect(jsonPath("$.installments[2].installmentNumber").value(3))
                .andExpect(jsonPath("$.installments[2].amountInTripCurrency").value(100));
    }

    @Test
    void registerPayment_montoLibreCreaSubmissionPendiente() throws Exception {
        PaymentFixture fixture = createPaymentFixture("payment-free-register", Currency.ARS);
        Installment first = createInstallment(fixture.trip(), fixture.user(), fixture.student(), 1, "100.00", InstallmentStatus.YELLOW);
        createInstallment(fixture.trip(), fixture.user(), fixture.student(), 2, "100.00", InstallmentStatus.YELLOW);
        createInstallment(fixture.trip(), fixture.user(), fixture.student(), 3, "100.00", InstallmentStatus.YELLOW);
        BankAccount bankAccount = createBankAccount(Currency.ARS);
        given(paymentAttachmentStorageService.storeReceipt(any(), anyLong(), anyLong(), any()))
                .willReturn("receipts/trip-1/user-2/test.jpg");
        given(paymentAttachmentStorageService.resolveFileReference("receipts/trip-1/user-2/test.jpg"))
                .willReturn("https://backend.example/api/v1/payment-attachments/receipt-token");

        MockMultipartFile file = new MockMultipartFile(
                "file",
                "comprobante.jpg",
                "image/jpeg",
                "contenido".getBytes()
        );

        String responseBody = mockMvc.perform(multipart("/api/v1/payments")
                        .file(file)
                        .header("Authorization", "Bearer " + fixture.userTokens().accessToken())
                        .param("anchorInstallmentId", String.valueOf(first.getId()))
                        .param("reportedAmount", "250.00")
                        .param("reportedPaymentDate", LocalDate.now().toString())
                        .param("paymentCurrency", "ARS")
                        .param("paymentMethod", "BANK_TRANSFER")
                        .param("bankAccountId", String.valueOf(bankAccount.getId()))
                        .with(request -> {
                            request.setMethod("POST");
                            return request;
                        }))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.submissionId").exists())
                .andExpect(jsonPath("$.status").value("PENDING"))
                .andExpect(jsonPath("$.reportedAmount").value(250))
                .andExpect(jsonPath("$.amountInTripCurrency").value(250))
                .andExpect(jsonPath("$.fileKey").value("https://backend.example/api/v1/payment-attachments/receipt-token"))
                .andExpect(jsonPath("$.installments.length()").value(3))
                .andExpect(jsonPath("$.installments[2].amountInTripCurrency").value(50))
                .andReturn()
                .getResponse()
                .getContentAsString();

        Long submissionId = objectMapper.readTree(responseBody).path("submissionId").asLong();
        assertNotNull(submissionId);
        assertEquals(
                "receipts/trip-1/user-2/test.jpg",
                paymentSubmissionRepository.findById(submissionId).orElseThrow().getFileKey()
        );
    }

    @Test
    void registerPayment_montoMayorAlSaldoPendienteDevuelve409() throws Exception {
        PaymentFixture fixture = createPaymentFixture("payment-free-overflow", Currency.ARS);
        Installment first = createInstallment(fixture.trip(), fixture.user(), fixture.student(), 1, "100.00", InstallmentStatus.YELLOW);
        createInstallment(fixture.trip(), fixture.user(), fixture.student(), 2, "100.00", InstallmentStatus.YELLOW);
        BankAccount bankAccount = createBankAccount(Currency.ARS);

        mockMvc.perform(multipart("/api/v1/payments")
                        .header("Authorization", "Bearer " + fixture.userTokens().accessToken())
                        .param("anchorInstallmentId", String.valueOf(first.getId()))
                        .param("reportedAmount", "250.01")
                        .param("reportedPaymentDate", LocalDate.now().toString())
                        .param("paymentCurrency", "ARS")
                        .param("paymentMethod", "BANK_TRANSFER")
                        .param("bankAccountId", String.valueOf(bankAccount.getId()))
                        .with(request -> {
                            request.setMethod("POST");
                            return request;
                        }))
                .andExpect(status().isConflict());
    }

    @Test
    void registerPayment_montoNegativoDevuelve400() throws Exception {
        PaymentFixture fixture = createPaymentFixture("pmt-free-neg", Currency.ARS);
        Installment first = createInstallment(fixture.trip(), fixture.user(), fixture.student(), 1, "100.00", InstallmentStatus.YELLOW);
        BankAccount bankAccount = createBankAccount(Currency.ARS);

        mockMvc.perform(multipart("/api/v1/payments")
                        .header("Authorization", "Bearer " + fixture.userTokens().accessToken())
                        .param("anchorInstallmentId", String.valueOf(first.getId()))
                        .param("reportedAmount", "-50.00")
                        .param("reportedPaymentDate", LocalDate.now().toString())
                        .param("paymentCurrency", "ARS")
                        .param("paymentMethod", "BANK_TRANSFER")
                        .param("bankAccountId", String.valueOf(bankAccount.getId()))
                        .with(request -> {
                            request.setMethod("POST");
                            return request;
                        }))
                .andExpect(status().isBadRequest());
    }

    @Test
    void reviewPayment_aprobacionParcialApruebaYRechazaElRestoDelMismoPago() throws Exception {
        TokenDTO adminTokens = signUpAdmin(buildValidUser("admin-free-review"));
        PaymentFixture fixture = createPaymentFixture("payment-free-review", Currency.ARS);
        Installment first = createInstallment(fixture.trip(), fixture.user(), fixture.student(), 1, "100.00", InstallmentStatus.YELLOW);
        createInstallment(fixture.trip(), fixture.user(), fixture.student(), 2, "100.00", InstallmentStatus.YELLOW);
        createInstallment(fixture.trip(), fixture.user(), fixture.student(), 3, "100.00", InstallmentStatus.YELLOW);
        BankAccount bankAccount = createBankAccount(Currency.ARS);

        String responseBody = mockMvc.perform(multipart("/api/v1/payments")
                        .header("Authorization", "Bearer " + fixture.userTokens().accessToken())
                        .param("anchorInstallmentId", String.valueOf(first.getId()))
                        .param("reportedAmount", "250.00")
                        .param("reportedPaymentDate", LocalDate.now().toString())
                        .param("paymentCurrency", "ARS")
                        .param("paymentMethod", "BANK_TRANSFER")
                        .param("bankAccountId", String.valueOf(bankAccount.getId()))
                        .with(request -> {
                            request.setMethod("POST");
                            return request;
                        }))
                .andExpect(status().isCreated())
                .andReturn()
                .getResponse()
                .getContentAsString();

        Long submissionId = objectMapper.readTree(responseBody).path("submissionId").asLong();
        assertNotNull(submissionId);

        mockMvc.perform(patch("/api/v1/payments/{id}/review", submissionId)
                        .header("Authorization", "Bearer " + adminTokens.accessToken())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "approvedAmount": 180.00,
                                  "adminObservation": "El banco solo acreditó una parte"
                                }
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.submissionId").value(submissionId))
                .andExpect(jsonPath("$.status").value("PARTIALLY_APPROVED"))
                .andExpect(jsonPath("$.approvedAmount").value(180))
                .andExpect(jsonPath("$.rejectedAmount").value(70));
    }

    private PaymentFixture createPaymentFixture(String prefix, Currency currency) throws Exception {
        UserCreateDTO participantDto = buildValidUser(prefix);
        TokenDTO userTokens = signUp(participantDto);
        User user = userRepository.findByEmail(participantDto.email()).orElseThrow();
        Student student = getFirstStudent(user);
        Trip trip = createTripForUser(user, prefix, currency);
        return new PaymentFixture(userTokens, user, student, trip);
    }

    private Student getFirstStudent(User user) {
        return studentRepository.findByParentId(user.getId()).stream().findFirst().orElseThrow();
    }

    private Trip createTripForUser(User user, String prefix, Currency currency) {
        Trip trip = new Trip();
        trip.setName("Trip payment " + prefix);
        trip.setCurrency(currency);
        trip.setTotalAmount(BigDecimal.valueOf(120000));
        trip.setFirstInstallmentAmount(BigDecimal.valueOf(10000));
        trip.setInstallmentsCount(12);
        trip.setDueDay(10);
        trip.setYellowWarningDays(5);
        trip.setFixedFineAmount(BigDecimal.valueOf(5000));
        trip.setRetroactiveActive(false);
        trip.setFirstDueDate(LocalDate.now().plusMonths(1));
        trip.getAssignedUsers().add(user);
        return tripRepository.save(trip);
    }

    private Installment createInstallment(
            Trip trip,
            User user,
            Student student,
            int installmentNumber,
            String capitalAmount,
            InstallmentStatus status
    ) {
        Installment installment = new Installment();
        installment.setTrip(trip);
        installment.setUser(user);
        installment.setStudent(student);
        installment.setInstallmentNumber(installmentNumber);
        installment.setDueDate(LocalDate.now().plusDays(installmentNumber));
        installment.setCapitalAmount(new BigDecimal(capitalAmount));
        installment.setRetroactiveAmount(BigDecimal.ZERO);
        installment.setFineAmount(BigDecimal.ZERO);
        installment.setPaidAmount(BigDecimal.ZERO);
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
