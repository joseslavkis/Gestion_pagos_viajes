package com.agencia.pagos.payment;

import com.agencia.pagos.TestcontainersConfiguration;
import com.agencia.pagos.payment.dto.PaymentBatchPreviewDTO;
import com.agencia.pagos.payment.dto.PaymentPreviewRequestDTO;
import com.agencia.pagos.payment.dto.PaymentSubmissionDTO;
import com.agencia.pagos.payment.dto.RegisterPaymentDTO;
import com.agencia.pagos.payment.dto.ReviewPaymentDTO;
import com.agencia.pagos.user.dto.UserCreateDTO;
import com.agencia.pagos.user.dto.TokenDTO;
import com.agencia.pagos.payment.BankAccount;
import com.agencia.pagos.shared.money.Currency;
import com.agencia.pagos.trip.Installment;
import com.agencia.pagos.trip.InstallmentStatus;
import com.agencia.pagos.user.Student;
import com.agencia.pagos.trip.Trip;
import com.agencia.pagos.user.User;
import com.agencia.pagos.payment.BankAccountRepository;
import com.agencia.pagos.trip.InstallmentRepository;
import com.agencia.pagos.payment.PaymentSubmissionRepository;
import com.agencia.pagos.payment.PaymentAllocationRepository;
import com.agencia.pagos.payment.PaymentOutcomeRepository;
import com.agencia.pagos.user.StudentRepository;
import com.agencia.pagos.trip.TripRepository;
import com.agencia.pagos.user.UserRepository;
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

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Date;

import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.io.Decoders;
import io.jsonwebtoken.security.Keys;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.BDDMockito.given;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.hamcrest.Matchers.containsString;

@SpringBootTest(properties = {
        "app.mail.to=test@agencia.com",
        "app.mail.from=no-reply@agencia.com"
})
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
class PaymentRestControllerFreeAmountTest extends ControllerIntegrationTestSupport {

    private static final String TEST_JWT_SECRET =
            "dGVzdC1zZWNyZXQtcGFyYS1jaS1vbmx5LXF1ZS1zZWEtbG8tc3VmaWNpZW50ZW1lbnRlLWxhcmdvLXBhcmEtaG1hYw==";

    private record PaymentFixture(TokenDTO userTokens, User user, Student student, Trip trip) {}

    @MockBean
    private JavaMailSender javaMailSender;

    @MockBean
    private com.agencia.pagos.payment.ExchangeRateService exchangeRateService;

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

    @Autowired
    private PaymentOutcomeRepository paymentOutcomeRepository;

    @Autowired
    private PaymentAllocationRepository paymentAllocationRepository;

    @Autowired
    private PaymentService paymentService;

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
                .andExpect(jsonPath("$.reportedAmount").value("250.00"))
                .andExpect(jsonPath("$.maxAllowedAmount").value("300.00"))
                .andExpect(jsonPath("$.totalPendingAmountInTripCurrency").value("300.00"))
                .andExpect(jsonPath("$.amountInTripCurrency").value("250.00"))
                .andExpect(jsonPath("$.installments.length()").value(3))
                .andExpect(jsonPath("$.installments[0].installmentNumber").value(1))
                .andExpect(jsonPath("$.installments[0].amountInTripCurrency").value("100.00"))
                .andExpect(jsonPath("$.installments[1].installmentNumber").value(2))
                .andExpect(jsonPath("$.installments[1].amountInTripCurrency").value("100.00"))
                .andExpect(jsonPath("$.installments[2].installmentNumber").value(3))
                .andExpect(jsonPath("$.installments[2].amountInTripCurrency").value("50.00"));
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
                .andExpect(jsonPath("$.totalPendingAmountInTripCurrency").value("170.00"))
                .andExpect(jsonPath("$.installments.length()").value(2))
                .andExpect(jsonPath("$.installments[0].installmentNumber").value(1))
                .andExpect(jsonPath("$.installments[0].amountInTripCurrency").value("70.00"))
                .andExpect(jsonPath("$.installments[1].installmentNumber").value(2))
                .andExpect(jsonPath("$.installments[1].amountInTripCurrency").value("50.00"));
    }

    @Test
    void previewPayment_monedaDistintaAplicaTipoDeCambio() throws Exception {
        PaymentFixture fixture = createPaymentFixture("pmt-free-usd", Currency.USD);
        Installment first = createInstallment(fixture.trip(), fixture.user(), fixture.student(), 1, "100.00", InstallmentStatus.YELLOW);
        createInstallment(fixture.trip(), fixture.user(), fixture.student(), 2, "100.00", InstallmentStatus.YELLOW);

        org.mockito.Mockito.when(exchangeRateService.getOfficialQuoteForDate(org.mockito.ArgumentMatchers.any(LocalDate.class)))
                .thenReturn(new com.agencia.pagos.payment.ExchangeRateQuote(
                        new BigDecimal("1000.00"),
                        LocalDate.now(),
                        LocalDate.now(),
                        "test",
                        "test",
                        null));

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
                .andExpect(jsonPath("$.amountInTripCurrency").value("150.00"))
                .andExpect(jsonPath("$.totalPendingAmountInTripCurrency").value("200.00"))
                .andExpect(jsonPath("$.installments.length()").value(2))
                .andExpect(jsonPath("$.installments[0].installmentNumber").value(1))
                .andExpect(jsonPath("$.installments[0].amountInTripCurrency").value("100.00"))
                .andExpect(jsonPath("$.installments[1].installmentNumber").value(2))
                .andExpect(jsonPath("$.installments[1].amountInTripCurrency").value("50.00"));
    }

    @Test
    void calculateRemaining_caseGUsesAuthoritativeHalfUpSuggestionAndOneQuote() throws Exception {
        LocalDate paymentDate = LocalDate.now();
        PaymentFixture fixture = createPaymentFixture("payment-calculation-case-g", Currency.USD);
        Installment installment = createInstallment(
                fixture.trip(), fixture.user(), fixture.student(), 1, "0.01", InstallmentStatus.YELLOW);
        given(exchangeRateService.getOfficialQuoteForDate(paymentDate)).willReturn(new ExchangeRateQuote(
                new BigDecimal("1015.50"),
                paymentDate,
                paymentDate,
                "official",
                "provider-a",
                "2026-09-19T12:00:00Z"));

        mockMvc.perform(post("/api/v1/payments/calculation")
                        .header("Authorization", "Bearer " + fixture.userTokens().accessToken())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "anchorInstallmentId": %d,
                                  "paymentCurrency": "ARS",
                                  "reportedPaymentDate": "%s",
                                  "intent": "REMAINING"
                                }
                                """.formatted(installment.getId(), paymentDate)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("READY"))
                .andExpect(jsonPath("$.intent").value("REMAINING"))
                .andExpect(jsonPath("$.tripCurrency").value("USD"))
                .andExpect(jsonPath("$.paymentCurrency").value("ARS"))
                .andExpect(jsonPath("$.remainingAmount").value("0.01"))
                .andExpect(jsonPath("$.reportedAmount").value("10.16"))
                .andExpect(jsonPath("$.amountInTripCurrency").value("0.01"))
                .andExpect(jsonPath("$.tripCurrencyResidual").value("0.00"))
                .andExpect(jsonPath("$.exchangeRate").value("1015.50"))
                .andExpect(jsonPath("$.quoteProvider").value("provider-a"))
                .andExpect(jsonPath("$.calculationVersion").value("2"))
                .andExpect(jsonPath("$.previewToken").isNotEmpty());

        org.mockito.Mockito.verify(exchangeRateService, org.mockito.Mockito.times(1))
                .getOfficialQuoteForDate(paymentDate);
    }

    @Test
    void calculateRemaining_preservesValidNinetyNinePointTwentyNineCents() throws Exception {
        LocalDate paymentDate = LocalDate.now();
        PaymentFixture fixture = createPaymentFixture("payment-calculation-cents", Currency.USD);
        Installment installment = createInstallment(
                fixture.trip(), fixture.user(), fixture.student(), 1, "99.29", InstallmentStatus.YELLOW);

        mockMvc.perform(post("/api/v1/payments/calculation")
                        .header("Authorization", "Bearer " + fixture.userTokens().accessToken())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "anchorInstallmentId": %d,
                                  "paymentCurrency": "USD",
                                  "reportedPaymentDate": "%s",
                                  "intent": "REMAINING"
                                }
                                """.formatted(installment.getId(), paymentDate)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("READY"))
                .andExpect(jsonPath("$.remainingAmount").value("99.29"))
                .andExpect(jsonPath("$.reportedAmount").value("99.29"))
                .andExpect(jsonPath("$.maxAllowedAmount").value("99.29"))
                .andExpect(jsonPath("$.amountInTripCurrency").value("99.29"));

        org.mockito.Mockito.verify(exchangeRateService, org.mockito.Mockito.never())
                .getOfficialQuoteForDate(org.mockito.ArgumentMatchers.any(LocalDate.class));
    }

    @Test
    void calculateManualAmount_usesTheSubmittedMoneyAsIntentWithoutFrontendDerivedFields() throws Exception {
        LocalDate paymentDate = LocalDate.now();
        PaymentFixture fixture = createPaymentFixture("payment-calculation-manual", Currency.ARS);
        Installment installment = createInstallment(
                fixture.trip(), fixture.user(), fixture.student(), 1, "99.29", InstallmentStatus.YELLOW);

        mockMvc.perform(post("/api/v1/payments/calculation")
                        .header("Authorization", "Bearer " + fixture.userTokens().accessToken())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "anchorInstallmentId": %d,
                                  "paymentCurrency": "ARS",
                                  "reportedPaymentDate": "%s",
                                  "intent": "MANUAL",
                                  "reportedAmount": 25.50
                                }
                                """.formatted(installment.getId(), paymentDate)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("READY"))
                .andExpect(jsonPath("$.intent").value("MANUAL"))
                .andExpect(jsonPath("$.remainingAmount").value("99.29"))
                .andExpect(jsonPath("$.reportedAmount").value("25.50"))
                .andExpect(jsonPath("$.amountInTripCurrency").value("25.50"))
                .andExpect(jsonPath("$.tripCurrencyResidual").value("73.79"))
                .andExpect(jsonPath("$.installments[0].amountInTripCurrency").value("25.50"));
    }

    @Test
    void calculateManualAmount_exposesSafeLimitAndResidualWhenAmountExceedsBalance() throws Exception {
        LocalDate paymentDate = LocalDate.now();
        PaymentFixture fixture = createPaymentFixture("payment-calculation-over-balance", Currency.ARS);
        Installment installment = createInstallment(
                fixture.trip(), fixture.user(), fixture.student(), 1, "100.00", InstallmentStatus.YELLOW);

        mockMvc.perform(post("/api/v1/payments/calculation")
                        .header("Authorization", "Bearer " + fixture.userTokens().accessToken())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "anchorInstallmentId": %d,
                                  "paymentCurrency": "ARS",
                                  "reportedPaymentDate": "%s",
                                  "intent": "MANUAL",
                                  "reportedAmount": 100.01
                                }
                                """.formatted(installment.getId(), paymentDate)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("AMOUNT_EXCEEDS_BALANCE"))
                .andExpect(jsonPath("$.reportedAmount").value("100.01"))
                .andExpect(jsonPath("$.amountInTripCurrency").value("100.01"))
                .andExpect(jsonPath("$.remainingAmount").value("100.00"))
                .andExpect(jsonPath("$.maxAllowedAmount").value("100.00"))
                .andExpect(jsonPath("$.tripCurrencyResidual").value("0.01"))
                .andExpect(jsonPath("$.previewToken").doesNotExist());
    }

    @Test
    void calculateRemaining_returnsExplicitUnpayableStateWhenNoPaymentCentCanBeImputed() throws Exception {
        LocalDate paymentDate = LocalDate.now();
        PaymentFixture fixture = createPaymentFixture("payment-calculation-unpayable", Currency.ARS);
        Installment installment = createInstallment(
                fixture.trip(), fixture.user(), fixture.student(), 1, "0.01", InstallmentStatus.YELLOW);
        given(exchangeRateService.getOfficialQuoteForDate(paymentDate)).willReturn(new ExchangeRateQuote(
                new BigDecimal("1015.50"), paymentDate, paymentDate, "official", "provider-a", null));

        mockMvc.perform(post("/api/v1/payments/calculation")
                        .header("Authorization", "Bearer " + fixture.userTokens().accessToken())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "anchorInstallmentId": %d,
                                  "paymentCurrency": "USD",
                                  "reportedPaymentDate": "%s",
                                  "intent": "REMAINING"
                                }
                                """.formatted(installment.getId(), paymentDate)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("UNPAYABLE"))
                .andExpect(jsonPath("$.remainingAmount").value("0.01"))
                .andExpect(jsonPath("$.maxAllowedAmount").value("0.00"))
                .andExpect(jsonPath("$.reportedAmount").doesNotExist())
                .andExpect(jsonPath("$.previewToken").doesNotExist())
                .andExpect(jsonPath("$.message").isNotEmpty());
    }

    @Test
    void calculateRemaining_returnsExplicitQuoteUnavailableStateWithoutToken() throws Exception {
        LocalDate paymentDate = LocalDate.now();
        PaymentFixture fixture = createPaymentFixture("payment-calculation-no-quote", Currency.USD);
        Installment installment = createInstallment(
                fixture.trip(), fixture.user(), fixture.student(), 1, "100.00", InstallmentStatus.YELLOW);
        given(exchangeRateService.getOfficialQuoteForDate(paymentDate))
                .willThrow(new IllegalStateException("provider unavailable"));

        mockMvc.perform(post("/api/v1/payments/calculation")
                        .header("Authorization", "Bearer " + fixture.userTokens().accessToken())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "anchorInstallmentId": %d,
                                  "paymentCurrency": "ARS",
                                  "reportedPaymentDate": "%s",
                                  "intent": "REMAINING"
                                }
                                """.formatted(installment.getId(), paymentDate)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("QUOTE_UNAVAILABLE"))
                .andExpect(jsonPath("$.remainingAmount").value("100.00"))
                .andExpect(jsonPath("$.exchangeRate").doesNotExist())
                .andExpect(jsonPath("$.previewToken").doesNotExist())
                .andExpect(jsonPath("$.message").value("provider unavailable"));
    }

    @Test
    void calculateRemaining_returnsExpiredOnlyForAuthenticExpiredVersionTwoToken() throws Exception {
        LocalDate paymentDate = LocalDate.now();
        PaymentFixture fixture = createPaymentFixture("payment-calculation-expired", Currency.ARS);
        Installment installment = createInstallment(
                fixture.trip(), fixture.user(), fixture.student(), 1, "100.00", InstallmentStatus.YELLOW);
        String expiredToken = expiredPreviewToken(
                fixture.user().getId(), installment.getId(), paymentDate, Currency.ARS);

        mockMvc.perform(post("/api/v1/payments/calculation")
                        .header("Authorization", "Bearer " + fixture.userTokens().accessToken())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "anchorInstallmentId": %d,
                                  "paymentCurrency": "ARS",
                                  "reportedPaymentDate": "%s",
                                  "intent": "REMAINING",
                                  "previewToken": "%s"
                                }
                                """.formatted(installment.getId(), paymentDate, expiredToken)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("EXPIRED"))
                .andExpect(jsonPath("$.anchorInstallmentId").value(installment.getId()))
                .andExpect(jsonPath("$.remainingAmount").value("100.00"))
                .andExpect(jsonPath("$.previewToken").doesNotExist())
                .andExpect(jsonPath("$.message").isNotEmpty());
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
                .andExpect(jsonPath("$.amountInTripCurrency").value("300.00"))
                .andExpect(jsonPath("$.installments.length()").value(3))
                .andExpect(jsonPath("$.installments[2].installmentNumber").value(3))
                .andExpect(jsonPath("$.installments[2].amountInTripCurrency").value("100.00"));
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
                .andExpect(jsonPath("$.reportedAmount").value("250.00"))
                .andExpect(jsonPath("$.amountInTripCurrency").value("250.00"))
                .andExpect(jsonPath("$.fileKey").value("https://backend.example/api/v1/payment-attachments/receipt-token"))
                .andExpect(jsonPath("$.installments.length()").value(3))
                .andExpect(jsonPath("$.installments[2].amountInTripCurrency").value("50.00"))
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
    void registerPayment_montoMayorAlSaldoPendienteDevuelve400() throws Exception {
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
                .andExpect(status().isBadRequest());
    }

    @Test
    void registerPayment_crossCurrencyBalanceChangedAfterPreviewReturns400WithoutMutation() throws Exception {
        LocalDate paymentDate = LocalDate.now();
        PaymentFixture fixture = createPaymentFixture("pmt-register-fin-001", Currency.ARS);
        Installment first = createInstallment(
                fixture.trip(), fixture.user(), fixture.student(), 1, "100.01", InstallmentStatus.YELLOW);
        createInstallment(
                fixture.trip(), fixture.user(), fixture.student(), 2, "100.00", InstallmentStatus.YELLOW);
        BankAccount bankAccount = createBankAccount(Currency.USD);
        given(exchangeRateService.getOfficialQuoteForDate(paymentDate)).willReturn(new ExchangeRateQuote(
                new BigDecimal("3"), paymentDate, paymentDate, "test", "test", null));

        PaymentBatchPreviewDTO preview = paymentService.previewPayment(
                new PaymentPreviewRequestDTO(
                        first.getId(), new BigDecimal("66.67"), paymentDate, Currency.USD),
                fixture.user().getEmail());
        first.setCapitalAmount(new BigDecimal("100.00"));
        first.recalculateTotalDue();
        installmentRepository.saveAndFlush(first);

        long submissionsBefore = paymentSubmissionRepository.count();
        long outcomesBefore = paymentOutcomeRepository.count();
        long allocationsBefore = paymentAllocationRepository.count();

        mockMvc.perform(multipart("/api/v1/payments")
                        .header("Authorization", "Bearer " + fixture.userTokens().accessToken())
                        .param("anchorInstallmentId", String.valueOf(first.getId()))
                        .param("reportedAmount", "66.67")
                        .param("reportedPaymentDate", paymentDate.toString())
                        .param("paymentCurrency", "USD")
                        .param("paymentMethod", "BANK_TRANSFER")
                        .param("bankAccountId", String.valueOf(bankAccount.getId()))
                        .param("previewToken", preview.previewToken())
                        .with(request -> {
                            request.setMethod("POST");
                            return request;
                        }))
                .andExpect(status().isBadRequest())
                .andExpect(content().string(containsString("66.66")))
                .andExpect(content().string(containsString("0.01")));

        assertEquals(submissionsBefore, paymentSubmissionRepository.count());
        assertEquals(outcomesBefore, paymentOutcomeRepository.count());
        assertEquals(allocationsBefore, paymentAllocationRepository.count());
        assertEquals(new BigDecimal("0.00"), installmentRepository.findById(first.getId()).orElseThrow().getPaidAmount());
    }

    @Test
    void reviewPayment_balanceChangedUnderLockReturns409AndLeavesSubmissionPending() throws Exception {
        TokenDTO adminTokens = signUpAdmin(buildValidUser("admin-review-fin-001"));
        LocalDate paymentDate = LocalDate.now();
        PaymentFixture fixture = createPaymentFixture("pmt-review-fin-001", Currency.ARS);
        Installment first = createInstallment(
                fixture.trip(), fixture.user(), fixture.student(), 1, "100.01", InstallmentStatus.YELLOW);
        createInstallment(
                fixture.trip(), fixture.user(), fixture.student(), 2, "100.00", InstallmentStatus.YELLOW);
        BankAccount bankAccount = createBankAccount(Currency.USD);
        given(exchangeRateService.getOfficialQuoteForDate(paymentDate)).willReturn(new ExchangeRateQuote(
                new BigDecimal("3"), paymentDate, paymentDate, "test", "test", null));
        given(paymentAttachmentStorageService.storeReceipt(any(), anyLong(), anyLong(), any()))
                .willReturn("receipts/fin-001.jpg");

        PaymentBatchPreviewDTO preview = paymentService.previewPayment(
                new PaymentPreviewRequestDTO(
                        first.getId(), new BigDecimal("66.67"), paymentDate, Currency.USD),
                fixture.user().getEmail());
        PaymentSubmissionDTO registered = paymentService.registerPayment(
                new RegisterPaymentDTO(
                        first.getId(),
                        new BigDecimal("66.67"),
                        paymentDate,
                        Currency.USD,
                        PaymentMethod.BANK_TRANSFER,
                        bankAccount.getId(),
                        preview.previewToken()),
                fixture.user().getEmail());

        first.setCapitalAmount(new BigDecimal("100.00"));
        first.recalculateTotalDue();
        installmentRepository.saveAndFlush(first);

        long outcomesBefore = paymentOutcomeRepository.count();
        long allocationsBefore = paymentAllocationRepository.count();

        mockMvc.perform(patch("/api/v1/payments/{id}/review", registered.submissionId())
                        .header("Authorization", "Bearer " + adminTokens.accessToken())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "approvedAmount": 66.67,
                                  "adminObservation": null
                                }
                                """))
                .andExpect(status().isConflict())
                .andExpect(content().string(containsString("66.66")))
                .andExpect(content().string(containsString("0.01")));

        PaymentSubmission reloaded = paymentSubmissionRepository.findById(registered.submissionId()).orElseThrow();
        assertEquals(PaymentSubmissionStatus.PENDING, reloaded.getStatus());
        assertEquals(outcomesBefore, paymentOutcomeRepository.count());
        assertEquals(allocationsBefore, paymentAllocationRepository.count());
        assertEquals(new BigDecimal("0.00"), installmentRepository.findById(first.getId()).orElseThrow().getPaidAmount());
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
    void registerPayment_crossCurrencyWithoutTokenReturns400() throws Exception {
        PaymentFixture fixture = createPaymentFixture("pmt-free-cross-no-token", Currency.USD);
        Installment first = createInstallment(fixture.trip(), fixture.user(), fixture.student(), 1, "100.00", InstallmentStatus.YELLOW);
        createInstallment(fixture.trip(), fixture.user(), fixture.student(), 2, "100.00", InstallmentStatus.YELLOW);
        BankAccount bankAccount = createBankAccount(Currency.ARS);
        given(paymentAttachmentStorageService.storeReceipt(any(), anyLong(), anyLong(), any()))
                .willReturn("receipts/trip-1/user-2/test.jpg");

        mockMvc.perform(multipart("/api/v1/payments")
                        .header("Authorization", "Bearer " + fixture.userTokens().accessToken())
                        .param("anchorInstallmentId", String.valueOf(first.getId()))
                        .param("reportedAmount", "150000.00")
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
    void registerPayment_crossCurrencyWithValidTokenDoesNotCallProvider() throws Exception {
        PaymentFixture fixture = createPaymentFixture("pmt-free-cross-valid-token", Currency.USD);
        Installment first = createInstallment(fixture.trip(), fixture.user(), fixture.student(), 1, "100.00", InstallmentStatus.YELLOW);
        createInstallment(fixture.trip(), fixture.user(), fixture.student(), 2, "100.00", InstallmentStatus.YELLOW);
        BankAccount bankAccount = createBankAccount(Currency.ARS);
        given(paymentAttachmentStorageService.storeReceipt(any(), anyLong(), anyLong(), any()))
                .willReturn("receipts/trip-1/user-2/test.jpg");
        given(paymentAttachmentStorageService.resolveFileReference("receipts/trip-1/user-2/test.jpg"))
                .willReturn("https://backend.example/api/v1/payment-attachments/receipt-token");
        org.mockito.Mockito.when(exchangeRateService.getOfficialQuoteForDate(org.mockito.ArgumentMatchers.any(LocalDate.class)))
                .thenReturn(new com.agencia.pagos.payment.ExchangeRateQuote(
                        new BigDecimal("1000.00"),
                        LocalDate.now(),
                        LocalDate.now(),
                        "test",
                        "test",
                        null));

        org.mockito.Mockito.clearInvocations(exchangeRateService);

        String previewResponse = mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                        .post("/api/v1/payments/preview")
                        .header("Authorization", "Bearer " + fixture.userTokens().accessToken())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(("""
                                {
                                  "anchorInstallmentId": %d,
                                  "reportedAmount": 150000.00,
                                  "reportedPaymentDate": "%s",
                                  "paymentCurrency": "ARS"
                                }
                                """).formatted(first.getId(), LocalDate.now())))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        String previewToken = objectMapper.readTree(previewResponse).path("previewToken").asText();
        org.junit.jupiter.api.Assertions.assertNotNull(previewToken);

        org.mockito.Mockito.clearInvocations(exchangeRateService);

        mockMvc.perform(multipart("/api/v1/payments")
                        .header("Authorization", "Bearer " + fixture.userTokens().accessToken())
                        .param("anchorInstallmentId", String.valueOf(first.getId()))
                        .param("reportedAmount", "150000.00")
                        .param("reportedPaymentDate", LocalDate.now().toString())
                        .param("paymentCurrency", "ARS")
                        .param("paymentMethod", "BANK_TRANSFER")
                        .param("bankAccountId", String.valueOf(bankAccount.getId()))
                        .param("previewToken", previewToken)
                        .with(request -> {
                            request.setMethod("POST");
                            return request;
                        }))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.submissionId").exists());

        org.mockito.Mockito.verify(exchangeRateService, org.mockito.Mockito.never())
                .getOfficialQuoteForDate(org.mockito.ArgumentMatchers.any(LocalDate.class));
        org.mockito.Mockito.verify(exchangeRateService, org.mockito.Mockito.never())
                .getOfficialRateForDate(org.mockito.ArgumentMatchers.any(LocalDate.class));
    }

    @Test
    void serviceChain_usesPreviewSnapshotWithoutProviderRefetchDuringRegisterReviewOrVoid() throws Exception {
        LocalDate paymentDate = LocalDate.now();
        PaymentFixture fixture = createPaymentFixture("pmt-service-no-refetch", Currency.USD);
        Installment first = createInstallment(
                fixture.trip(), fixture.user(), fixture.student(), 1, "100.00", InstallmentStatus.YELLOW);
        createInstallment(
                fixture.trip(), fixture.user(), fixture.student(), 2, "100.00", InstallmentStatus.YELLOW);
        BankAccount bankAccount = createBankAccount(Currency.ARS);
        org.mockito.Mockito.when(exchangeRateService.getOfficialQuoteForDate(paymentDate))
                .thenReturn(new ExchangeRateQuote(
                        new BigDecimal("1000.000"),
                        paymentDate,
                        paymentDate,
                        "official-closing",
                        "test-provider",
                        "2026-09-19T12:00:00Z"));

        PaymentBatchPreviewDTO preview = paymentService.previewPayment(
                new PaymentPreviewRequestDTO(
                        first.getId(),
                        new BigDecimal("150000.00"),
                        paymentDate,
                        Currency.ARS),
                fixture.user().getEmail());
        assertNotNull(preview.previewToken());
        org.mockito.Mockito.clearInvocations(exchangeRateService);

        PaymentSubmissionDTO registered = paymentService.registerPayment(
                new RegisterPaymentDTO(
                        first.getId(),
                        new BigDecimal("150000.00"),
                        paymentDate,
                        Currency.ARS,
                        PaymentMethod.BANK_TRANSFER,
                        bankAccount.getId(),
                        preview.previewToken()),
                fixture.user().getEmail());
        paymentService.reviewPayment(
                registered.submissionId(),
                new ReviewPaymentDTO(new BigDecimal("150000.00"), null),
                "admin@test.com");
        paymentService.voidPayment(registered.submissionId(), "admin@test.com");

        org.mockito.Mockito.verify(exchangeRateService, org.mockito.Mockito.never())
                .getOfficialQuoteForDate(org.mockito.ArgumentMatchers.any(LocalDate.class));
        org.mockito.Mockito.verify(exchangeRateService, org.mockito.Mockito.never())
                .getOfficialRateForDate(org.mockito.ArgumentMatchers.any(LocalDate.class));
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
                .andExpect(jsonPath("$.approvedAmount").value("180.00"))
                .andExpect(jsonPath("$.rejectedAmount").value("70.00"));
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

    private String expiredPreviewToken(
            Long userId,
            Long anchorInstallmentId,
            LocalDate paymentDate,
            Currency paymentCurrency
    ) {
        Instant now = Instant.now();
        return Jwts.builder()
                .claim("type", "payment-preview")
                .subject(userId.toString())
                .claim("userId", userId)
                .claim("anchorInstallmentId", anchorInstallmentId)
                .claim("paymentCurrency", paymentCurrency.name())
                .claim("reportedAmount", "100.00")
                .claim("reportedPaymentDate", paymentDate.toString())
                .claim("cv", PaymentPreviewTokenService.CURRENT_CALCULATION_VERSION)
                .issuedAt(Date.from(now.minusSeconds(600)))
                .expiration(Date.from(now.minusSeconds(300)))
                .signWith(Keys.hmacShaKeyFor(Decoders.BASE64.decode(TEST_JWT_SECRET)), Jwts.SIG.HS256)
                .compact();
    }
}
