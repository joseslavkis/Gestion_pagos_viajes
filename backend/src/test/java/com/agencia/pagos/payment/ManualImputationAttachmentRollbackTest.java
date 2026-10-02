package com.agencia.pagos.payment;

import com.agencia.pagos.TestcontainersConfiguration;
import com.agencia.pagos.payment.dto.PaymentCalculationIntent;
import com.agencia.pagos.payment.dto.PaymentCalculationResponseDTO;
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

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.nullable;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * El archivo se escribe ANTES de persistir submission/outcome/allocations.
 * Si la persistencia posterior falla, la transacción hace rollback y el
 * archivo huérfano debe eliminarse; si la limpieza también falla, el error
 * financiero original debe propagarse igual (la limpieza solo se loguea).
 */
@SpringBootTest(properties = {
        "app.mail.to=test@agencia.com",
        "app.mail.from=no-reply@agencia.com"
})
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
class ManualImputationAttachmentRollbackTest extends ControllerIntegrationTestSupport {

    private static final LocalDate HISTORICAL_DATE = LocalDate.of(2020, 1, 15);

    @MockBean
    private JavaMailSender javaMailSender;

    @MockBean
    private PaymentAttachmentStorageService paymentAttachmentStorageService;

    @MockBean
    private ExchangeRateService exchangeRateService;

    @MockBean
    private PaymentAllocationRepository paymentAllocationRepository;

    @Autowired
    private ManualImputationService manualImputationService;

    @Test
    void persistenceFailureAfterStore_deletesOrphanAttachmentAndRollsBack() throws Exception {
        Installment anchor = createFixture();
        String admin = adminEmail(adminTokens);
        given(paymentAttachmentStorageService.storeReceipt(
                nullable(org.springframework.web.multipart.MultipartFile.class),
                any(Long.class), any(Long.class), any()))
                .willReturn("orphan-key");
        given(paymentAllocationRepository.saveAll(any()))
                .willThrow(new RuntimeException("boom-persist"));

        String token = calculationToken(adminTokens, anchor.getId(), "10.00");
        MockMultipartFile file = new MockMultipartFile("file", "c.png", "image/png", new byte[]{1, 2, 3});
        assertThatThrownBy(() -> manualImputationService.execute(anchor.getId(),
                new BigDecimal("10.00"), HISTORICAL_DATE, Currency.ARS, token, null, List.of(file), admin))
                .isInstanceOf(RuntimeException.class)
                .hasMessageContaining("boom-persist");

        verify(paymentAttachmentStorageService, org.mockito.Mockito.times(1)).deleteReceipt("orphan-key");
        assertThat(paymentSubmissionRepository.count()).isZero();
        assertThat(installmentRepository.findById(anchor.getId()).orElseThrow().getPaidAmount())
                .isEqualByComparingTo("0.00");
    }

    private TokenDTO adminTokens;
    private User user;
    private Student student;
    private Trip trip;

    private Installment createFixture() throws Exception {
        adminTokens = signUpAdmin(buildValidUser("admin-rollback"));
        TokenDTO userTokens = signUp(buildValidUser("rollback"));
        user = userRepository.findByEmail(
                objectMapper.readTree(java.util.Base64.getUrlDecoder()
                        .decode(userTokens.accessToken().split("\\.")[1])).get("sub").asText()).orElseThrow();
        student = studentRepository.findByParentId(user.getId()).stream().findFirst().orElseThrow();
        trip = new Trip();
        trip.setName("Trip rollback " + System.nanoTime());
        trip.setCurrency(Currency.ARS);
        trip.setTotalAmount(new BigDecimal("120000.00"));
        trip.setFirstInstallmentAmount(new BigDecimal("10000.00"));
        trip.setInstallmentsCount(12);
        trip.setDueDay(10);
        trip.setYellowWarningDays(5);
        trip.setRetroactiveActive(false);
        trip.setFirstDueDate(LocalDate.now().plusMonths(1));
        trip = tripRepository.save(trip);
        trip.getAssignedUsers().add(user);
        trip = tripRepository.save(trip);
        Installment installment = new Installment();
        installment.setTrip(trip);
        installment.setUser(user);
        installment.setStudent(student);
        installment.setInstallmentNumber(1);
        installment.setDueDate(LocalDate.now().plusDays(1));
        installment.setCapitalAmount(new BigDecimal("100.00"));
        installment.setRetroactiveAmount(BigDecimal.ZERO);
        installment.setPaidAmount(BigDecimal.ZERO);
        installment.setStatus(InstallmentStatus.YELLOW);
        installment.recalculateTotalDue();
        return installmentRepository.save(installment);
    }

    private String adminEmail(TokenDTO tokens) throws Exception {
        String payload = new String(java.util.Base64.getUrlDecoder()
                .decode(tokens.accessToken().split("\\.")[1]));
        return objectMapper.readTree(payload).get("sub").asText();
    }

    private String calculationToken(TokenDTO tokens, Long anchorId, String amount) throws Exception {
        String body = objectMapper.writeValueAsString(java.util.Map.of(
                "anchorInstallmentId", anchorId,
                "paymentCurrency", Currency.ARS.name(),
                "reportedPaymentDate", HISTORICAL_DATE.toString(),
                "intent", "MANUAL",
                "reportedAmount", new BigDecimal(amount)));
        MvcResult result = mockMvc.perform(post("/api/v1/payments/calculation")
                        .header("Authorization", "Bearer " + tokens.accessToken())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isOk())
                .andReturn();
        PaymentCalculationResponseDTO response = objectMapper.readValue(
                result.getResponse().getContentAsString(), PaymentCalculationResponseDTO.class);
        assertThat(response.status().name()).isEqualTo("READY");
        return response.previewToken();
    }
}
