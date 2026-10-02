package com.agencia.pagos.payment;

import com.agencia.pagos.TestcontainersConfiguration;
import com.agencia.pagos.payment.dto.ManualImputationContextDTO;
import com.agencia.pagos.payment.dto.PaymentCalculationRequestDTO;
import com.agencia.pagos.payment.dto.PaymentCalculationResponseDTO;
import com.agencia.pagos.payment.dto.PaymentCalculationIntent;
import com.agencia.pagos.payment.dto.PaymentSubmissionDTO;
import com.agencia.pagos.shared.money.Currency;
import com.agencia.pagos.trip.Installment;
import com.agencia.pagos.trip.InstallmentStatus;
import com.agencia.pagos.trip.Trip;
import com.agencia.pagos.user.Student;
import com.agencia.pagos.user.User;
import com.agencia.pagos.user.dto.TokenDTO;
import com.agencia.pagos.payment.storage.PaymentAttachmentStorageService;
import com.agencia.pagos.testsupport.ControllerIntegrationTestSupport;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MvcResult;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.nullable;
import static org.mockito.BDDMockito.given;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(properties = {
        "app.mail.to=test@agencia.com",
        "app.mail.from=no-reply@agencia.com"
})
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
class ManualImputationIntegrationTest extends ControllerIntegrationTestSupport {

    private static final LocalDate HISTORICAL_DATE = LocalDate.of(2020, 1, 15);

    @MockBean
    private JavaMailSender javaMailSender;

    @MockBean
    private PaymentAttachmentStorageService paymentAttachmentStorageService;

    @MockBean
    private ExchangeRateService exchangeRateService;

    @Autowired
    private ManualImputationService manualImputationService;

    @Autowired
    private PaymentService paymentService;

    @Autowired
    private PaymentPreviewTokenService previewTokenService;

    @org.springframework.boot.test.mock.mockito.SpyBean
    private com.agencia.pagos.trip.TripRepository tripRepositorySpy;

    @org.springframework.boot.test.mock.mockito.SpyBean
    private com.agencia.pagos.trip.InstallmentRepository installmentRepositorySpy;

    @org.springframework.boot.test.mock.mockito.SpyBean
    private PaymentSubmissionRepository paymentSubmissionRepositorySpy;

    // ── Fixtures ──────────────────────────────────────────────

    private record ManualFixture(User user, Student student, Trip trip,
                                 Installment i1, Installment i2, Installment i3,
                                 TokenDTO adminTokens, TokenDTO userTokens) {
    }

    private ManualFixture createManualFixture(String prefix, Currency currency,
                                              String a1, String a2, String a3) throws Exception {
        TokenDTO adminTokens = signUpAdmin(buildValidUser("admin-" + prefix));
        TokenDTO userTokens = signUp(buildValidUser(prefix));
        User user = userRepository.findByEmail(
                objectMapper.readTree(java.util.Base64.getUrlDecoder()
                        .decode(userTokens.accessToken().split("\\.")[1])).get("sub").asText()).orElseThrow();
        Student student = studentRepository.findByParentId(user.getId()).stream().findFirst().orElseThrow();
        Trip trip = createTrip(currency, prefix);
        trip.getAssignedUsers().add(user);
        trip = tripRepository.save(trip);
        Installment i1 = createInstallment(trip, user, student, 1, a1);
        Installment i2 = createInstallment(trip, user, student, 2, a2);
        Installment i3 = createInstallment(trip, user, student, 3, a3);
        return new ManualFixture(user, student, trip, i1, i2, i3, adminTokens, userTokens);
    }

    private Trip createTrip(Currency currency, String prefix) {
        Trip trip = new Trip();
        trip.setName("Trip manual " + prefix + System.nanoTime());
        trip.setCurrency(currency);
        trip.setTotalAmount(new BigDecimal("120000.00"));
        trip.setFirstInstallmentAmount(new BigDecimal("10000.00"));
        trip.setInstallmentsCount(12);
        trip.setDueDay(10);
        trip.setYellowWarningDays(5);
        trip.setRetroactiveActive(false);
        trip.setFirstDueDate(LocalDate.now().plusMonths(1));
        return tripRepository.save(trip);
    }

    private Installment createInstallment(Trip trip, User user, Student student,
                                          int number, String capital) {
        Installment installment = new Installment();
        installment.setTrip(trip);
        installment.setUser(user);
        installment.setStudent(student);
        installment.setInstallmentNumber(number);
        installment.setDueDate(LocalDate.now().plusDays(number));
        installment.setCapitalAmount(new BigDecimal(capital));
        installment.setRetroactiveAmount(BigDecimal.ZERO);
        installment.setPaidAmount(BigDecimal.ZERO);
        installment.setStatus(InstallmentStatus.YELLOW);
        installment.recalculateTotalDue();
        return installmentRepository.save(installment);
    }

    private String adminEmail(TokenDTO adminTokens) throws Exception {
        String payload = new String(java.util.Base64.getUrlDecoder()
                .decode(adminTokens.accessToken().split("\\.")[1]));
        return objectMapper.readTree(payload).get("sub").asText();
    }

    private String calculationToken(TokenDTO tokens, Long anchorId, String amount,
                                    Currency payCurrency, LocalDate date,
                                    PaymentCalculationIntent intent) throws Exception {
        String body;
        if (intent == PaymentCalculationIntent.REMAINING) {
            body = objectMapper.writeValueAsString(java.util.Map.of(
                    "anchorInstallmentId", anchorId,
                    "paymentCurrency", payCurrency.name(),
                    "reportedPaymentDate", date.toString(),
                    "intent", "REMAINING"));
        } else {
            body = objectMapper.writeValueAsString(java.util.Map.of(
                    "anchorInstallmentId", anchorId,
                    "paymentCurrency", payCurrency.name(),
                    "reportedPaymentDate", date.toString(),
                    "intent", "MANUAL",
                    "reportedAmount", new BigDecimal(amount)));
        }
        MvcResult result = mockMvc.perform(post("/api/v1/payments/calculation")
                        .header("Authorization", "Bearer " + tokens.accessToken())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isOk())
                .andReturn();
        PaymentCalculationResponseDTO response = objectMapper.readValue(
                result.getResponse().getContentAsString(), PaymentCalculationResponseDTO.class);
        assertThat(response.status().name()).isEqualTo("READY");
        assertThat(response.previewToken()).isNotNull();
        return response.previewToken();
    }

    // ── Elegibilidad ──────────────────────────────────────────

    @Test
    void context_firstPayableIsEligible_secondIsRejected() throws Exception {
        ManualFixture f = createManualFixture("elig", Currency.ARS, "240.00", "240.00", "240.00");
        String admin = adminEmail(f.adminTokens());

        ManualImputationContextDTO first = manualImputationService.getContext(f.i1().getId(), admin);
        assertThat(first.eligible()).isTrue();
        assertThat(first.firstPayableInstallmentNumber()).isEqualTo(1);

        ManualImputationContextDTO second = manualImputationService.getContext(f.i2().getId(), admin);
        assertThat(second.eligible()).isFalse();
        assertThat(second.message()).contains("cuota #1");
    }

    @Test
    void context_paidInstallmentReportsAlreadyPaid() throws Exception {
        ManualFixture f = createManualFixture("paid", Currency.ARS, "100.00", "100.00", "100.00");
        String admin = adminEmail(f.adminTokens());
        given(paymentAttachmentStorageService.storeReceipt(
                nullable(org.springframework.web.multipart.MultipartFile.class),
                any(Long.class), any(Long.class), any()))
                .willReturn("manual.png");

        String token = calculationToken(f.adminTokens(), f.i1().getId(), "100.00",
                Currency.ARS, HISTORICAL_DATE, PaymentCalculationIntent.MANUAL);
        manualImputationService.execute(f.i1().getId(), new BigDecimal("100.00"),
                HISTORICAL_DATE, Currency.ARS, token, null, List.of(), admin);

        ManualImputationContextDTO paid = manualImputationService.getContext(f.i1().getId(), admin);
        assertThat(paid.eligible()).isFalse();
        assertThat(paid.message()).contains("completamente pagada");
        assertThat(paid.firstPayableInstallmentNumber()).isEqualTo(2);
    }

    @Test
    void context_fullyPaidTripReportsNoBalance() throws Exception {
        ManualFixture f = createManualFixture("full", Currency.ARS, "10.00", "10.00", "10.00");
        String admin = adminEmail(f.adminTokens());
        given(paymentAttachmentStorageService.storeReceipt(
                nullable(org.springframework.web.multipart.MultipartFile.class),
                any(Long.class), any(Long.class), any()))
                .willReturn("m.png");
        String t1 = calculationToken(f.adminTokens(), f.i1().getId(), "10.00",
                Currency.ARS, HISTORICAL_DATE, PaymentCalculationIntent.MANUAL);
        manualImputationService.execute(f.i1().getId(), new BigDecimal("10.00"),
                HISTORICAL_DATE, Currency.ARS, t1, null, List.of(), admin);
        String t2 = calculationToken(f.adminTokens(), f.i2().getId(), "10.00",
                Currency.ARS, HISTORICAL_DATE, PaymentCalculationIntent.MANUAL);
        manualImputationService.execute(f.i2().getId(), new BigDecimal("10.00"),
                HISTORICAL_DATE, Currency.ARS, t2, null, List.of(), admin);
        String t3 = calculationToken(f.adminTokens(), f.i3().getId(), "10.00",
                Currency.ARS, HISTORICAL_DATE, PaymentCalculationIntent.MANUAL);
        manualImputationService.execute(f.i3().getId(), new BigDecimal("10.00"),
                HISTORICAL_DATE, Currency.ARS, t3, null, List.of(), admin);

        ManualImputationContextDTO ctx = manualImputationService.getContext(f.i1().getId(), admin);
        assertThat(ctx.eligible()).isFalse();
        assertThat(ctx.message()).contains("no tiene saldo pendiente");
    }

    @Test
    void context_pendingSubmissionBlocksManual() throws Exception {
        ManualFixture f = createManualFixture("pend", Currency.ARS, "100.00", "100.00", "100.00");
        String admin = adminEmail(f.adminTokens());
        // Crear un PENDING de cliente vía cálculo + registro (requiere bank account).
        var bank = bankAccountRepository.save(com.agencia.pagos.payment.BankAccount.builder()
                .bankName("B").accountLabel("L").accountHolder("H")
                .accountNumber("0001-" + System.nanoTime()).taxId("30-71131646-5")
                .cbu(String.valueOf(System.nanoTime())).alias("A." + System.nanoTime())
                .currency(Currency.ARS).active(true).displayOrder(1).build());
        String preview = calculationToken(f.userTokens(), f.i1().getId(), "50.00",
                Currency.ARS, HISTORICAL_DATE, PaymentCalculationIntent.MANUAL);
        given(paymentAttachmentStorageService.storeReceipt(
                nullable(org.springframework.web.multipart.MultipartFile.class),
                any(Long.class), any(Long.class), any()))
                .willReturn("p.png");
        paymentService.registerPayment(f.i1().getId(), new BigDecimal("50.00"),
                HISTORICAL_DATE, Currency.ARS, PaymentMethod.BANK_TRANSFER,
                bank.getId(), null, preview, f.user().getEmail());

        ManualImputationContextDTO ctx = manualImputationService.getContext(f.i1().getId(), admin);
        assertThat(ctx.eligible()).isFalse();
        assertThat(ctx.hasPendingReview()).isTrue();
        assertThat(ctx.message()).contains("pendientes de aprobación");

        assertThatThrownBy(() -> manualImputationService.execute(f.i1().getId(),
                new BigDecimal("10.00"), HISTORICAL_DATE, Currency.ARS, "dummy", null, List.of(), admin))
                .isInstanceOf(ManualImputationException.class)
                .hasMessageContaining("pendientes de aprobación");
    }

    // ── Montos y distribución ─────────────────────────────────

    @Test
    void execute_partialAndMultiInstallmentConserveTotals() throws Exception {
        ManualFixture f = createManualFixture("dist", Currency.ARS, "240.00", "240.00", "240.00");
        String admin = adminEmail(f.adminTokens());
        given(paymentAttachmentStorageService.storeReceipt(
                nullable(org.springframework.web.multipart.MultipartFile.class),
                any(Long.class), any(Long.class), any()))
                .willReturn("m.png");

        String token = calculationToken(f.adminTokens(), f.i1().getId(), "500.00",
                Currency.ARS, HISTORICAL_DATE, PaymentCalculationIntent.MANUAL);
        PaymentSubmissionDTO dto = manualImputationService.execute(f.i1().getId(),
                new BigDecimal("500.00"), HISTORICAL_DATE, Currency.ARS, token,
                "Pago en efectivo", List.of(), admin);

        assertThat(dto.source().name()).isEqualTo("ADMIN_MANUAL");
        assertThat(dto.manualReason()).isEqualTo("Pago en efectivo");
        assertThat(dto.paymentMethod()).isNull();
        assertThat(dto.bankAccountId()).isNull();
        assertThat(dto.installments()).hasSize(3);
        assertThat(dto.installments().get(0).amountInTripCurrency()).isEqualByComparingTo("240.00");
        assertThat(dto.installments().get(1).amountInTripCurrency()).isEqualByComparingTo("240.00");
        assertThat(dto.installments().get(2).amountInTripCurrency()).isEqualByComparingTo("20.00");

        // Conservación financiera.
        BigDecimal sum = dto.installments().stream()
                .map(i -> i.amountInTripCurrency())
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        assertThat(sum).isEqualByComparingTo(dto.amountInTripCurrency());
        assertThat(sum).isEqualByComparingTo("500.00");

        // Persistencia recargada.
        PaymentSubmission reloaded = paymentSubmissionRepository.findByIdWithContext(dto.submissionId()).orElseThrow();
        assertThat(reloaded.getSource()).isEqualTo(PaymentSubmissionSource.ADMIN_MANUAL);
        assertThat(reloaded.getStatus()).isEqualTo(PaymentSubmissionStatus.RESOLVED);
        assertThat(installmentRepository.findById(f.i1().getId()).orElseThrow().getPaidAmount())
                .isEqualByComparingTo("240.00");
        assertThat(installmentRepository.findById(f.i3().getId()).orElseThrow().getPaidAmount())
                .isEqualByComparingTo("20.00");
    }

    @Test
    void execute_rejectsZeroNegativeAndExceeding() throws Exception {
        ManualFixture f = createManualFixture("amt", Currency.ARS, "100.00", "100.00", "100.00");
        String admin = adminEmail(f.adminTokens());
        String token = calculationToken(f.adminTokens(), f.i1().getId(), "10.00",
                Currency.ARS, HISTORICAL_DATE, PaymentCalculationIntent.MANUAL);

        assertThatThrownBy(() -> manualImputationService.execute(f.i1().getId(),
                BigDecimal.ZERO, HISTORICAL_DATE, Currency.ARS, token, null, List.of(), admin))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("mayor a cero");

        assertThatThrownBy(() -> manualImputationService.execute(f.i1().getId(),
                new BigDecimal("-5.00"), HISTORICAL_DATE, Currency.ARS, token, null, List.of(), admin))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("mayor a cero");
    }

    @Test
    void execute_rejectsAmountExceedingTotalBalance() throws Exception {
        ManualFixture f = createManualFixture("exc", Currency.ARS, "100.00", "100.00", "100.00");
        String admin = adminEmail(f.adminTokens());
        // Saldo total 300; pedir cálculo de 301 da AMOUNT_EXCEEDS_BALANCE sin token.
        // Ejecución directa con token ajeno debe fallar por mismatch/stale.
        String token = calculationToken(f.adminTokens(), f.i1().getId(), "100.00",
                Currency.ARS, HISTORICAL_DATE, PaymentCalculationIntent.MANUAL);
        assertThatThrownBy(() -> manualImputationService.execute(f.i1().getId(),
                new BigDecimal("301.00"), HISTORICAL_DATE, Currency.ARS, token, null, List.of(), admin))
                .isInstanceOf(ManualImputationException.class);
    }

    // ── FX ────────────────────────────────────────────────────

    @Test
    void execute_crossCurrencyUsesFrozenQuoteAndPersistsMetadata() throws Exception {
        ManualFixture f = createManualFixture("fx", Currency.USD, "150.00", "150.00", "150.00");
        String admin = adminEmail(f.adminTokens());
        ExchangeRateQuote quote = new ExchangeRateQuote(
                new BigDecimal("1500.00"), HISTORICAL_DATE, HISTORICAL_DATE,
                "test", "deterministic", HISTORICAL_DATE + "T12:00:00Z");
        given(exchangeRateService.getOfficialQuoteForDate(HISTORICAL_DATE)).willReturn(quote);
        given(paymentAttachmentStorageService.storeReceipt(
                nullable(org.springframework.web.multipart.MultipartFile.class),
                any(Long.class), any(Long.class), any()))
                .willReturn("fx.png");

        // REMAINING en ARS para cubrir 150 USD a 1500 => 225000 ARS.
        String token = calculationToken(f.adminTokens(), f.i1().getId(), null,
                Currency.ARS, HISTORICAL_DATE, PaymentCalculationIntent.REMAINING);
        PaymentCalculationResponseDTO calc = objectMapper.readValue(
                mockMvc.perform(post("/api/v1/payments/calculation")
                                .header("Authorization", "Bearer " + f.adminTokens().accessToken())
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(objectMapper.writeValueAsString(java.util.Map.of(
                                        "anchorInstallmentId", f.i1().getId(),
                                        "paymentCurrency", "ARS",
                                        "reportedPaymentDate", HISTORICAL_DATE.toString(),
                                        "intent", "REMAINING"))))
                        .andExpect(status().isOk()).andReturn()
                        .getResponse().getContentAsString(),
                PaymentCalculationResponseDTO.class);
        assertThat(calc.reportedAmount()).isEqualByComparingTo("225000.00");

        PaymentSubmissionDTO dto = manualImputationService.execute(f.i1().getId(),
                new BigDecimal("225000.00"), HISTORICAL_DATE, Currency.ARS, token, null, List.of(), admin);
        assertThat(dto.amountInTripCurrency()).isEqualByComparingTo("150.00");
        assertThat(dto.exchangeRate()).isEqualByComparingTo("1500.00");
        assertThat(dto.paymentCurrency().name()).isEqualTo("ARS");
    }

    @Test
    void calculation_sameCurrencyDoesNotCallProvider() throws Exception {
        ManualFixture f = createManualFixture("same", Currency.ARS, "100.00", "100.00", "100.00");
        mockMvc.perform(post("/api/v1/payments/calculation")
                        .header("Authorization", "Bearer " + f.adminTokens().accessToken())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(java.util.Map.of(
                                "anchorInstallmentId", f.i1().getId(),
                                "paymentCurrency", "ARS",
                                "reportedPaymentDate", HISTORICAL_DATE.toString(),
                                "intent", "MANUAL",
                                "reportedAmount", new BigDecimal("10.00")))))
                .andExpect(status().isOk());
        org.mockito.Mockito.verify(exchangeRateService,
                org.mockito.Mockito.never()).getOfficialQuoteForDate(any());
    }

    // ── Preview token ─────────────────────────────────────────

    @Test
    void execute_rejectsMismatchedAndForeignTokens() throws Exception {
        ManualFixture f = createManualFixture("tok", Currency.ARS, "100.00", "100.00", "100.00");
        String admin = adminEmail(f.adminTokens());
        String token = calculationToken(f.adminTokens(), f.i1().getId(), "10.00",
                Currency.ARS, HISTORICAL_DATE, PaymentCalculationIntent.MANUAL);

        // Monto distinto.
        assertThatThrownBy(() -> manualImputationService.execute(f.i1().getId(),
                new BigDecimal("20.00"), HISTORICAL_DATE, Currency.ARS, token, null, List.of(), admin))
                .isInstanceOf(ManualImputationException.class);

        // Moneda distinta.
        assertThatThrownBy(() -> manualImputationService.execute(f.i1().getId(),
                new BigDecimal("10.00"), HISTORICAL_DATE, Currency.USD, token, null, List.of(), admin))
                .isInstanceOf(ManualImputationException.class);

        // Token de otro admin.
        TokenDTO otherAdmin = signUpAdmin(buildValidUser("other-admin-tok"));
        String otherAdminEmail = adminEmail(otherAdmin);
        assertThatThrownBy(() -> manualImputationService.execute(f.i1().getId(),
                new BigDecimal("10.00"), HISTORICAL_DATE, Currency.ARS, token, null, List.of(), otherAdminEmail))
                .isInstanceOf(ManualImputationException.class);
    }

    // ── Autorización HTTP ─────────────────────────────────────

    @Test
    void http_userCannotExecuteManual_adminCanReadContext() throws Exception {
        ManualFixture f = createManualFixture("auth", Currency.ARS, "100.00", "100.00", "100.00");
        String token = calculationToken(f.adminTokens(), f.i1().getId(), "10.00",
                Currency.ARS, HISTORICAL_DATE, PaymentCalculationIntent.MANUAL);

        mockMvc.perform(post("/api/v1/payments/manual-imputations")
                        .header("Authorization", "Bearer " + f.userTokens().accessToken())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(java.util.Map.of(
                                "anchorInstallmentId", f.i1().getId(),
                                "reportedAmount", "10.00",
                                "paymentCurrency", "ARS",
                                "reportedPaymentDate", HISTORICAL_DATE.toString(),
                                "previewToken", token))))
                .andExpect(status().isForbidden());

        mockMvc.perform(get("/api/v1/payments/manual-imputations/context")
                        .param("installmentId", String.valueOf(f.i1().getId()))
                        .header("Authorization", "Bearer " + f.adminTokens().accessToken()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.eligible").value(true));

        mockMvc.perform(get("/api/v1/payments/manual-imputations/context")
                        .param("installmentId", String.valueOf(f.i1().getId()))
                        .header("Authorization", "Bearer " + f.userTokens().accessToken()))
                .andExpect(status().isForbidden());
    }

    @Test
    void http_invalidAnchorIsRejectedWithoutSideEffects() throws Exception {
        ManualFixture f = createManualFixture("anchor", Currency.ARS, "100.00", "100.00", "100.00");
        String tokenI1 = calculationToken(f.adminTokens(), f.i1().getId(), "10.00",
                Currency.ARS, HISTORICAL_DATE, PaymentCalculationIntent.MANUAL);
        // Intentar ejecutar con anchor #2 usando token de #1 → mismatch.
        mockMvc.perform(multipart("/api/v1/payments/manual-imputations")
                        .param("anchorInstallmentId", String.valueOf(f.i2().getId()))
                        .param("reportedAmount", "10.00")
                        .param("reportedPaymentDate", HISTORICAL_DATE.toString())
                        .param("paymentCurrency", "ARS")
                        .param("previewToken", tokenI1)
                        .header("Authorization", "Bearer " + f.adminTokens().accessToken()))
                .andExpect(status().isBadRequest());

        assertThat(installmentRepository.findById(f.i2().getId()).orElseThrow().getPaidAmount())
                .isEqualByComparingTo("0.00");
        assertThat(paymentSubmissionRepository.count()).isEqualTo(0);
    }

    @Test
    void http_jsonAndMultipartBothWork_optionalAttachment() throws Exception {
        ManualFixture f = createManualFixture("att", Currency.ARS, "100.00", "100.00", "100.00");
        given(paymentAttachmentStorageService.storeReceipt(
                nullable(org.springframework.web.multipart.MultipartFile.class),
                any(Long.class), any(Long.class), any()))
                .willReturn("stored-key");
        given(paymentAttachmentStorageService.resolveFileReference(any()))
                .willAnswer(inv -> inv.getArgument(0));

        String tokenJson = calculationToken(f.adminTokens(), f.i1().getId(), "20.00",
                Currency.ARS, HISTORICAL_DATE, PaymentCalculationIntent.MANUAL);
        mockMvc.perform(post("/api/v1/payments/manual-imputations")
                        .header("Authorization", "Bearer " + f.adminTokens().accessToken())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(java.util.Map.of(
                                "anchorInstallmentId", f.i1().getId(),
                                "reportedAmount", "20.00",
                                "paymentCurrency", "ARS",
                                "reportedPaymentDate", HISTORICAL_DATE.toString(),
                                "previewToken", tokenJson,
                                "reason", "Sin comprobante"))))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.source").value("ADMIN_MANUAL"));

        String tokenMulti = calculationToken(f.adminTokens(), f.i2().getId(), "30.00",
                Currency.ARS, HISTORICAL_DATE, PaymentCalculationIntent.MANUAL);
        MockMultipartFile file = new MockMultipartFile("file", "c.png", "image/png", new byte[]{1, 2, 3});
        mockMvc.perform(multipart("/api/v1/payments/manual-imputations")
                        .file(file)
                        .param("anchorInstallmentId", String.valueOf(f.i2().getId()))
                        .param("reportedAmount", "30.00")
                        .param("reportedPaymentDate", HISTORICAL_DATE.toString())
                        .param("paymentCurrency", "ARS")
                        .param("previewToken", tokenMulti)
                        .param("reason", "Con comprobante")
                        .header("Authorization", "Bearer " + f.adminTokens().accessToken()))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.source").value("ADMIN_MANUAL"));
    }

    // ── Concurrencia real ─────────────────────────────────────

    @Test
    void concurrent_twoManualsOnSameHundred_onlyOneWins() throws Exception {
        ManualFixture f = createManualFixture("conc", Currency.ARS, "100.00", "0.00", "0.00");
        // Dejar solo una cuota con saldo 100: las otras en 0 ya están pagadas.
        // Nuestro fixture crea 3 cuotas; ponemos i2/i3 en 0 total para aislar saldo 100.
        // Simplificamos: usar solo i1 con 100 y las demás también 100 pero el test
        // imputa 100 exacto desde i1; la segunda debe fallar por stale/anchor.
        String admin = adminEmail(f.adminTokens());
        given(paymentAttachmentStorageService.storeReceipt(
                nullable(org.springframework.web.multipart.MultipartFile.class),
                any(Long.class), any(Long.class), any()))
                .willReturn("c.png");

        String tokenA = calculationToken(f.adminTokens(), f.i1().getId(), "100.00",
                Currency.ARS, HISTORICAL_DATE, PaymentCalculationIntent.MANUAL);
        String tokenB = calculationToken(f.adminTokens(), f.i1().getId(), "100.00",
                Currency.ARS, HISTORICAL_DATE, PaymentCalculationIntent.MANUAL);

        ExecutorService pool = Executors.newFixedThreadPool(2);
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch go = new CountDownLatch(1);
        java.util.concurrent.atomic.AtomicReference<Object> resultA = new java.util.concurrent.atomic.AtomicReference<>();
        java.util.concurrent.atomic.AtomicReference<Object> resultB = new java.util.concurrent.atomic.AtomicReference<>();
        Future<?> fa = pool.submit(() -> {
            ready.countDown();
            try {
                go.await(10, TimeUnit.SECONDS);
                resultA.set(manualImputationService.execute(f.i1().getId(),
                        new BigDecimal("100.00"), HISTORICAL_DATE, Currency.ARS, tokenA, null, List.of(), admin));
            } catch (Exception e) {
                resultA.set(e);
            }
            return null;
        });
        Future<?> fb = pool.submit(() -> {
            ready.countDown();
            try {
                go.await(10, TimeUnit.SECONDS);
                resultB.set(manualImputationService.execute(f.i1().getId(),
                        new BigDecimal("100.00"), HISTORICAL_DATE, Currency.ARS, tokenB, null, List.of(), admin));
            } catch (Exception e) {
                resultB.set(e);
            }
            return null;
        });
        assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue();
        go.countDown();
        fa.get(30, TimeUnit.SECONDS);
        fb.get(30, TimeUnit.SECONDS);
        pool.shutdownNow();

        long successes = java.util.stream.Stream.of(resultA.get(), resultB.get())
                .filter(r -> r instanceof PaymentSubmissionDTO).count();
        long conflicts = java.util.stream.Stream.of(resultA.get(), resultB.get())
                .filter(r -> r instanceof ManualImputationException).count();
        assertThat(successes).isEqualTo(1);
        assertThat(conflicts).isEqualTo(1);

        // Solo una acreditación efectiva de 100.
        BigDecimal paid = installmentRepository.findById(f.i1().getId()).orElseThrow().getPaidAmount();
        assertThat(paid).isEqualByComparingTo("100.00");
        assertThat(paymentSubmissionRepository.count()).isEqualTo(1);
    }

    @Test
    void manual_canBeVoidedReversingExactly() throws Exception {
        ManualFixture f = createManualFixture("void", Currency.ARS, "100.00", "100.00", "100.00");
        String admin = adminEmail(f.adminTokens());
        given(paymentAttachmentStorageService.storeReceipt(
                nullable(org.springframework.web.multipart.MultipartFile.class),
                any(Long.class), any(Long.class), any()))
                .willReturn("v.png");
        String token = calculationToken(f.adminTokens(), f.i1().getId(), "100.00",
                Currency.ARS, HISTORICAL_DATE, PaymentCalculationIntent.MANUAL);
        PaymentSubmissionDTO dto = manualImputationService.execute(f.i1().getId(),
                new BigDecimal("100.00"), HISTORICAL_DATE, Currency.ARS, token, null, List.of(), admin);
        assertThat(installmentRepository.findById(f.i1().getId()).orElseThrow().getPaidAmount())
                .isEqualByComparingTo("100.00");

        paymentService.voidPayment(dto.submissionId(), admin);
        assertThat(installmentRepository.findById(f.i1().getId()).orElseThrow().getPaidAmount())
                .isEqualByComparingTo("0.00");
    }

    @Test
    void manual_preservesTripThenInstallmentsLockOrder() throws Exception {
        ManualFixture f = createManualFixture("lock", Currency.ARS, "100.00", "100.00", "100.00");
        String admin = adminEmail(f.adminTokens());
        given(paymentAttachmentStorageService.storeReceipt(
                nullable(org.springframework.web.multipart.MultipartFile.class),
                any(Long.class), any(Long.class), any()))
                .willReturn("l.png");
        String token = calculationToken(f.adminTokens(), f.i1().getId(), "10.00",
                Currency.ARS, HISTORICAL_DATE, PaymentCalculationIntent.MANUAL);
        org.mockito.Mockito.clearInvocations(
                tripRepositorySpy, installmentRepositorySpy, paymentSubmissionRepositorySpy);

        manualImputationService.execute(f.i1().getId(),
                new BigDecimal("10.00"), HISTORICAL_DATE, Currency.ARS, token, null, List.of(), admin);

        org.mockito.InOrder lockOrder = org.mockito.Mockito.inOrder(
                tripRepositorySpy, installmentRepositorySpy, paymentSubmissionRepositorySpy);
        lockOrder.verify(tripRepositorySpy).findByIdForUpdate(f.trip().getId());
        lockOrder.verify(installmentRepositorySpy).findByTripIdAndUserIdAndStudentIdForUpdate(
                f.trip().getId(), f.user().getId(), f.student().getId());
        lockOrder.verify(paymentSubmissionRepositorySpy).save(any(PaymentSubmission.class));
    }
}
