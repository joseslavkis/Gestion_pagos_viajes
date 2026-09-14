package com.agencia.pagos.controllers;

import com.agencia.pagos.TestcontainersConfiguration;
import com.agencia.pagos.dtos.request.UserCreateDTO;
import com.agencia.pagos.dtos.response.TokenDTO;
import com.agencia.pagos.entities.BankAccount;
import com.agencia.pagos.entities.Currency;
import com.agencia.pagos.entities.Installment;
import com.agencia.pagos.entities.InstallmentStatus;
import com.agencia.pagos.entities.PendingTripStudent;
import com.agencia.pagos.entities.PaymentAllocation;
import com.agencia.pagos.entities.PaymentMethod;
import com.agencia.pagos.entities.PaymentOutcome;
import com.agencia.pagos.entities.PaymentOutcomeStatus;
import com.agencia.pagos.entities.PaymentReceipt;
import com.agencia.pagos.entities.PaymentSubmission;
import com.agencia.pagos.entities.PaymentSubmissionStatus;
import com.agencia.pagos.entities.ReceiptStatus;
import com.agencia.pagos.entities.Student;
import com.agencia.pagos.entities.Trip;
import com.agencia.pagos.entities.user.User;
import com.agencia.pagos.services.storage.PaymentAttachmentStorageService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.mockito.ArgumentMatchers;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MvcResult;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.LinkedHashSet;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.BDDMockito.given;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.hamcrest.Matchers.containsString;

/**
 * Real integration coverage for {@code DELETE /api/v1/trips/{id}/students/{studentDni}} focused on
 * financial-integrity enforcement: any PaymentSubmission anchored to the scoped installments, any
 * legacy PaymentReceipt, or any paidAmount > 0 must surface as HTTP 409 and leave every financial
 * and structural record intact. No-activity unassigns must complete with 200 and clean only the
 * installments/reminders/parent scope. Reuses the disposable Testcontainers PostgreSQL and the
 * {@link ControllerIntegrationTestSupport} fixtures so no development DB is touched.
 */
@SpringBootTest(properties = {
        "app.mail.to=test@agencia.com",
        "app.mail.from=no-reply@agencia.com"
})
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
class TripUnassignFinancialIntegrityIntegrationTest extends ControllerIntegrationTestSupport {

    @MockBean
    private JavaMailSender javaMailSender;

    @MockBean
    private PaymentAttachmentStorageService paymentAttachmentStorageService;

    @ParameterizedTest
    @EnumSource(PaymentSubmissionStatus.class)
    void deleteStudent_paymentSubmissionAnyStatus_returns409AndPreservesSubmission(PaymentSubmissionStatus status)
            throws Exception {
        FinancialFixture fixture = bootstrapFixture("unassign-sub-" + status.name().toLowerCase());
        PaymentSubmission persisted = persistSubmissionAnchored(fixture, status);
        long submissionCountBefore = paymentSubmissionRepository.count();

        mockMvc.perform(delete("/api/v1/trips/{id}/students/{dni}", fixture.tripId(), fixture.studentDni())
                        .header("Authorization", "Bearer " + fixture.adminToken().accessToken()))
                .andExpect(status().isConflict())
                .andExpect(content().string(containsString("actividad financiera")));

        assertEquals(submissionCountBefore, paymentSubmissionRepository.count());
        PaymentSubmission reloaded = paymentSubmissionRepository.findByIdWithContext(persisted.getId()).orElseThrow();
        assertEquals(status, reloaded.getStatus());
        assertEquals(fixture.installment().getId(), reloaded.getAnchorInstallment().getId());
        assertEquals(persisted.getFileKey(), reloaded.getFileKey());
        assertFinancialRecordsIntact(fixture, BigDecimal.ZERO);
        assertEquals(0, paymentOutcomeRepository.count());
        assertEquals(0, paymentAllocationRepository.count());
        assertEquals(0, paymentReceiptRepository.count());
    }

    @ParameterizedTest
    @EnumSource(ReceiptStatus.class)
    void deleteStudent_legacyPaymentReceiptAnyStatus_returns409AndPreservesReceipt(ReceiptStatus status)
            throws Exception {
        FinancialFixture fixture = bootstrapFixture("unassign-legacy-receipt-" + status.name().toLowerCase());
        assertRejectedForLegacyReceipt(fixture, status);
    }

    /**
     * Shared assertion for all {@link ReceiptStatus} variants of the status-agnostic unassign
     * financial guard. The guard rejects unassignment whenever ANY legacy {@code PaymentReceipt}
     * is attached to the locked installment scope, regardless of its current status — so the
     * parameterized cases (PENDING / APPROVED / REJECTED) must all surface the same typed 409
     * with the receipt, installment, pending row, and parent binding intact.
     */
    private void assertRejectedForLegacyReceipt(FinancialFixture fixture, ReceiptStatus status) throws Exception {
        paymentReceiptRepository.save(PaymentReceipt.builder()
                .installment(fixture.installment())
                .reportedAmount(new BigDecimal("1500.00"))
                .paymentCurrency(Currency.ARS)
                .exchangeRate(new BigDecimal("1.00"))
                .amountInTripCurrency(new BigDecimal("1500.00"))
                .reportedPaymentDate(LocalDate.now())
                .paymentMethod(PaymentMethod.BANK_TRANSFER)
                .status(status)
                .fileKey("legacy-receipt-" + status.name().toLowerCase() + ".png")
                .build());

        long submissionCountBefore = paymentSubmissionRepository.count();
        long outcomeCountBefore = paymentOutcomeRepository.count();
        long allocationCountBefore = paymentAllocationRepository.count();
        long remindersBefore = installmentReminderNotificationRepository.count();

        mockMvc.perform(delete("/api/v1/trips/{id}/students/{dni}", fixture.tripId(), fixture.studentDni())
                        .header("Authorization", "Bearer " + fixture.adminToken().accessToken()))
                .andExpect(status().isConflict())
                .andExpect(content().string(containsString("actividad financiera")));

        // Legacy receipt must still exist and the installment must remain in place.
        assertEquals(1, paymentReceiptRepository.count(),
                "Legacy receipt with status " + status + " must survive");
        PaymentReceipt reloaded = paymentReceiptRepository.findAll().get(0);
        assertEquals(status, reloaded.getStatus(),
                "Legacy receipt status must remain unchanged on 409");
        assertNotNull(installmentRepository.findById(fixture.installment().getId()).orElse(null));
        assertEquals(0, BigDecimal.ZERO.compareTo(
                installmentRepository.findById(fixture.installment().getId()).orElseThrow().getPaidAmount()),
                "Installment.paidAmount must stay at 0 on legacy-receipt 409");
        // No other entity changed — submissions, outcomes, allocations, reminders are stable.
        assertEquals(submissionCountBefore, paymentSubmissionRepository.count(),
                "PaymentSubmission count must not change on legacy-receipt 409");
        assertEquals(outcomeCountBefore, paymentOutcomeRepository.count(),
                "PaymentOutcome count must not change on legacy-receipt 409");
        assertEquals(allocationCountBefore, paymentAllocationRepository.count(),
                "PaymentAllocation count must not change on legacy-receipt 409");
        assertEquals(remindersBefore, installmentReminderNotificationRepository.count(),
                "Reminder notifications must not change on legacy-receipt 409");
        assertTrue(tripRetainsUser(tripRepository.findByIdWithUsers(fixture.tripId()).orElseThrow(), fixture.user()),
                "Parent must stay assigned on legacy-receipt 409");
    }

    @Test
    void deleteStudent_directAllocationOnDifferentInstallment_returns409WithZeroPaidAmount() throws Exception {
        FinancialFixture fixture = bootstrapFixture("unassign-direct-allocation");

        Student anchorStudent = new Student();
        anchorStudent.setParent(fixture.user());
        anchorStudent.setName("Anchor");
        anchorStudent.setLastname("Student");
        anchorStudent.setDni(uniqueDni());
        anchorStudent = studentRepository.save(anchorStudent);

        Installment anchorInstallment = persistInstallment(fixture, anchorStudent, 1);
        PaymentSubmission submission = persistSubmissionAnchored(
                fixture, anchorInstallment, anchorStudent, PaymentSubmissionStatus.RESOLVED
        );
        PaymentOutcome outcome = persistOutcome(submission, PaymentOutcomeStatus.APPROVED, new BigDecimal("1500.00"));
        PaymentAllocation allocation = persistAllocation(
                outcome, fixture.installment(), new BigDecimal("1500.00"), 1
        );
        outcome.setAllocations(new LinkedHashSet<>(List.of(allocation)));
        paymentOutcomeRepository.save(outcome);

        long submissionCountBefore = paymentSubmissionRepository.count();
        long outcomeCountBefore = paymentOutcomeRepository.count();
        long allocationCountBefore = paymentAllocationRepository.count();

        mockMvc.perform(delete("/api/v1/trips/{id}/students/{dni}", fixture.tripId(), fixture.studentDni())
                        .header("Authorization", "Bearer " + fixture.adminToken().accessToken()))
                .andExpect(status().isConflict())
                .andExpect(content().string(containsString("actividad financiera")));

        // The target installment has paidAmount = 0 and is not the submission anchor. The direct
        // PaymentAllocation.installment binding is the only reason this unassignment is rejected.
        assertEquals(0, BigDecimal.ZERO.compareTo(
                installmentRepository.findById(fixture.installment().getId()).orElseThrow().getPaidAmount()));
        assertNotNull(installmentRepository.findById(anchorInstallment.getId()).orElse(null));
        assertEquals(submissionCountBefore, paymentSubmissionRepository.count());
        assertEquals(outcomeCountBefore, paymentOutcomeRepository.count());
        assertEquals(allocationCountBefore, paymentAllocationRepository.count());
        PaymentAllocation reloadedAllocation = paymentAllocationRepository
                .findById(allocation.getId()).orElseThrow();
        assertEquals(fixture.installment().getId(), reloadedAllocation.getInstallment().getId());
        assertEquals(outcome.getId(), reloadedAllocation.getOutcome().getId());
        assertTrue(tripRetainsUser(tripRepository.findByIdWithUsers(fixture.tripId()).orElseThrow(), fixture.user()));
    }

    @Test
    void deleteStudent_multipleInstallmentsWithAnchorAllocationAndCleanQuota_returns409() throws Exception {
        FinancialFixture fixture = bootstrapFixture("unassign-multiple-installments");
        Installment allocatedInstallment = persistInstallment(fixture, fixture.student(), 2);
        Installment cleanInstallment = persistInstallment(fixture, fixture.student(), 3);

        PaymentSubmission submission = persistSubmissionAnchored(fixture, PaymentSubmissionStatus.RESOLVED);
        PaymentOutcome outcome = persistOutcome(submission, PaymentOutcomeStatus.APPROVED, new BigDecimal("1500.00"));
        PaymentAllocation allocation = persistAllocation(
                outcome, allocatedInstallment, new BigDecimal("1500.00"), 1
        );
        outcome.setAllocations(new LinkedHashSet<>(List.of(allocation)));
        paymentOutcomeRepository.save(outcome);

        mockMvc.perform(delete("/api/v1/trips/{id}/students/{dni}", fixture.tripId(), fixture.studentDni())
                        .header("Authorization", "Bearer " + fixture.adminToken().accessToken()))
                .andExpect(status().isConflict());

        assertNotNull(installmentRepository.findById(fixture.installment().getId()).orElse(null));
        assertNotNull(installmentRepository.findById(allocatedInstallment.getId()).orElse(null));
        assertNotNull(installmentRepository.findById(cleanInstallment.getId()).orElse(null));
        assertEquals(0, BigDecimal.ZERO.compareTo(
                installmentRepository.findById(allocatedInstallment.getId()).orElseThrow().getPaidAmount()));
        assertNotNull(paymentSubmissionRepository.findById(submission.getId()).orElse(null));
        assertNotNull(paymentOutcomeRepository.findById(outcome.getId()).orElse(null));
        assertNotNull(paymentAllocationRepository.findById(allocation.getId()).orElse(null));
        assertTrue(tripRetainsUser(tripRepository.findByIdWithUsers(fixture.tripId()).orElseThrow(), fixture.user()));
    }

    @Test
    void deleteStudent_paidAmountGreaterThanZero_returns409AndPreservesInstallmentAndPaidAmount() throws Exception {
        FinancialFixture fixture = bootstrapFixture("unassign-paid-amount");
        fixture.installment().setPaidAmount(new BigDecimal("1500.00"));
        installmentRepository.save(fixture.installment());

        mockMvc.perform(delete("/api/v1/trips/{id}/students/{dni}", fixture.tripId(), fixture.studentDni())
                        .header("Authorization", "Bearer " + fixture.adminToken().accessToken()))
                .andExpect(status().isConflict())
                .andExpect(content().string(containsString("actividad financiera")));

        // paidAmount and installment must be intact.
        Installment reloaded = installmentRepository.findById(fixture.installment().getId()).orElseThrow();
        assertEquals(0, new BigDecimal("1500.00").compareTo(reloaded.getPaidAmount()));
        assertTrue(tripRetainsUser(tripRepository.findByIdWithUsers(fixture.tripId()).orElseThrow(), fixture.user()));
    }

    @Test
    void deleteStudent_noActivity_returns200AndDeletesInstallmentAndDetachesParent() throws Exception {
        FinancialFixture fixture = bootstrapFixture("unassign-no-activity");

        mockMvc.perform(delete("/api/v1/trips/{id}/students/{dni}", fixture.tripId(), fixture.studentDni())
                        .header("Authorization", "Bearer " + fixture.adminToken().accessToken()))
                .andExpect(status().isOk());

        assertTrue(installmentRepository.findById(fixture.installment().getId()).isEmpty(),
                "Installment must be deleted on a no-activity unassignment");
        assertTrue(tripRepository.findByIdWithUsers(fixture.tripId()).orElseThrow()
                .getAssignedUsers().stream()
                .noneMatch(u -> u.getId().equals(fixture.user().getId())),
                "Parent must be detached from the trip after the last student leaves");
    }

    @Test
    void deleteStudent_mixedPendingAndFinancialInstallment_returns409AndPreservesPendingRowAndAllFinancialEntities() throws Exception {
        // [GUARD FIX] Mixed case: a PendingTripStudent row exists for the same student DNI on the
        // same trip while the installment scope carries financial activity. The pending row MUST
        // survive the 409 — explicit semantics, not just transaction rollback.
        FinancialFixture fixture = bootstrapFixtureWithPending("unassign-mixed-pending");
        persistSubmissionAnchored(fixture, PaymentSubmissionStatus.PENDING);

        long pendingRowsBefore = pendingTripStudentRepository.findByTripIdAndStudentDni(
                fixture.tripId(), fixture.studentDni()).size();
        long remindersBefore = installmentReminderNotificationRepository.count();
        long submissionCountBefore = paymentSubmissionRepository.count();
        long outcomeCountBefore = paymentOutcomeRepository.count();
        long allocationCountBefore = paymentAllocationRepository.count();

        mockMvc.perform(delete("/api/v1/trips/{id}/students/{dni}", fixture.tripId(), fixture.studentDni())
                        .header("Authorization", "Bearer " + fixture.adminToken().accessToken()))
                .andExpect(status().isConflict())
                .andExpect(content().string(containsString("actividad financiera")));

        // Pending row for this student on this trip must still be there — pending delete never ran.
        assertEquals(pendingRowsBefore, pendingTripStudentRepository.findByTripIdAndStudentDni(
                fixture.tripId(), fixture.studentDni()).size(),
                "Pending row must survive when the financial guard rejects");
        assertTrue(pendingTripStudentRepository.findByTripIdAndStudentDni(
                fixture.tripId(), fixture.studentDni()).stream()
                .anyMatch(p -> p.getStudentDni().equals(fixture.studentDni())),
                "Pending row for this student DNI must still exist");

        // Every other entity must be exactly as it was before the rejected call.
        assertNotNull(installmentRepository.findById(fixture.installment().getId()).orElse(null));
        assertEquals(0, BigDecimal.ZERO.compareTo(
                installmentRepository.findById(fixture.installment().getId()).orElseThrow().getPaidAmount()));
        assertEquals(submissionCountBefore, paymentSubmissionRepository.count(),
                "PaymentSubmission count must not change on 409");
        assertEquals(outcomeCountBefore, paymentOutcomeRepository.count(),
                "PaymentOutcome count must not change on 409");
        assertEquals(allocationCountBefore, paymentAllocationRepository.count(),
                "PaymentAllocation count must not change on 409");
        assertEquals(remindersBefore, installmentReminderNotificationRepository.count(),
                "Reminder notifications must not change on 409");
        assertTrue(tripRetainsUser(tripRepository.findByIdWithUsers(fixture.tripId()).orElseThrow(), fixture.user()),
                "Parent must remain assigned on 409");
    }

    @Test
    void deleteStudent_mixedPendingAndPaidAmount_returns409AndPreservesPendingRow() throws Exception {
        // Mixed path where the financial guard rejects purely on paidAmount > 0 (no submission).
        FinancialFixture fixture = bootstrapFixtureWithPending("unassign-mixed-paid");
        fixture.installment().setPaidAmount(new BigDecimal("1500.00"));
        installmentRepository.save(fixture.installment());

        mockMvc.perform(delete("/api/v1/trips/{id}/students/{dni}", fixture.tripId(), fixture.studentDni())
                        .header("Authorization", "Bearer " + fixture.adminToken().accessToken()))
                .andExpect(status().isConflict());

        // Pending row still there.
        assertTrue(pendingTripStudentRepository.findByTripIdAndStudentDni(
                fixture.tripId(), fixture.studentDni()).stream()
                .anyMatch(p -> p.getStudentDni().equals(fixture.studentDni())),
                "Pending row must survive the paidAmount-based 409");
        assertEquals(0, new BigDecimal("1500.00").compareTo(
                installmentRepository.findById(fixture.installment().getId()).orElseThrow().getPaidAmount()));
    }

    @Test
    void deleteStudent_productionRegisterPaymentPath_returns409AndPreservesEverything() throws Exception {
        // Production-path regression check: drive an actual PaymentSubmission through the real
        // POST /api/v1/payments (PaymentService.registerPayment), then DELETE the same student
        // and assert the 409 + preservation. Prevents a regression where the unassign guard
        // might leak through only because tests bypassed the production path and persisted
        // PaymentSubmission rows manually.
        FinancialFixture fixture = bootstrapFixture("unassign-prod-register");
        BankAccount bankAccount = bankAccountRepository.save(BankAccount.builder()
                .bankName("Banco ICBC")
                .accountLabel("Cuenta en pesos")
                .accountHolder("Proyecto VA SRL")
                .accountNumber("0001-" + System.nanoTime())
                .taxId("30-71131646-5")
                .cbu(String.valueOf(System.nanoTime()))
                .alias("ALIAS." + System.nanoTime())
                .currency(Currency.ARS)
                .active(true)
                .displayOrder(1)
                .build());
        given(paymentAttachmentStorageService.storeReceipt(
                ArgumentMatchers.any(), ArgumentMatchers.anyLong(),
                ArgumentMatchers.anyLong(), ArgumentMatchers.any()))
                .willReturn("receipts/prod-register/" + fixture.installment().getId() + "/test.jpg");

        MockMultipartFile file = new MockMultipartFile(
                "file", "comprobante.jpg", "image/jpeg", "contenido".getBytes()
        );

        // Drive a real PENDING submission through PaymentService.registerPayment → POST /api/v1/payments.
        MvcResult registerResult = mockMvc.perform(multipart("/api/v1/payments")
                        .file(file)
                        .header("Authorization", "Bearer " + fixture.userToken().accessToken())
                        .param("anchorInstallmentId", String.valueOf(fixture.installment().getId()))
                        .param("reportedAmount", "500.00")
                        .param("reportedPaymentDate", LocalDate.now().toString())
                        .param("paymentCurrency", "ARS")
                        .param("paymentMethod", PaymentMethod.BANK_TRANSFER.name())
                        .param("bankAccountId", String.valueOf(bankAccount.getId()))
                        .with(request -> {
                            request.setMethod("POST");
                            return request;
                        }))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.submissionId").exists())
                .andExpect(jsonPath("$.status").value("PENDING"))
                .andReturn();

        Long submissionId = objectMapper.readTree(
                registerResult.getResponse().getContentAsString()
        ).path("submissionId").asLong();
        assertNotNull(submissionId);
        assertEquals(1L, paymentSubmissionRepository.count(),
                "Production registerPayment must persist exactly one submission");

        // Now DELETE the same student via the admin endpoint and assert the 409 + preservation.
        long submissionCountBefore = paymentSubmissionRepository.count();
        long outcomeCountBefore = paymentOutcomeRepository.count();
        long allocationCountBefore = paymentAllocationRepository.count();
        long receiptCountBefore = paymentReceiptRepository.count();
        long remindersBefore = installmentReminderNotificationRepository.count();

        mockMvc.perform(delete("/api/v1/trips/{id}/students/{dni}", fixture.tripId(), fixture.studentDni())
                        .header("Authorization", "Bearer " + fixture.adminToken().accessToken()))
                .andExpect(status().isConflict())
                .andExpect(content().string(containsString("actividad financiera")));

        // Submission persisted via the real production path survives the 409 with every
        // detail intact.
        assertEquals(submissionCountBefore, paymentSubmissionRepository.count(),
                "Production PaymentSubmission count must not change on 409");
        PaymentSubmission reloadedSubmission = paymentSubmissionRepository
                .findByIdWithContext(submissionId).orElseThrow();
        assertEquals(PaymentSubmissionStatus.PENDING, reloadedSubmission.getStatus(),
                "Production PENDING submission status must remain unchanged on 409");
        assertEquals(fixture.installment().getId(), reloadedSubmission.getAnchorInstallment().getId(),
                "Production submission.anchorInstallment binding must remain unchanged on 409");
        assertEquals(fixture.student().getId(),
                reloadedSubmission.getStudent() == null ? null : reloadedSubmission.getStudent().getId(),
                "Production submission.student binding must remain unchanged on 409");

        // No allocation/receipt/outcome churn on a still-PENDING submission.
        assertEquals(outcomeCountBefore, paymentOutcomeRepository.count());
        assertEquals(allocationCountBefore, paymentAllocationRepository.count());
        assertEquals(receiptCountBefore, paymentReceiptRepository.count());

        // Installment, reminder notifications, and parent binding are preserved.
        Installment reloadedInstallment = installmentRepository
                .findById(fixture.installment().getId()).orElseThrow();
        assertEquals(0, BigDecimal.ZERO.compareTo(reloadedInstallment.getPaidAmount()),
                "Production-path Installment.paidAmount must stay at 0 on PENDING submission 409");
        assertEquals(remindersBefore, installmentReminderNotificationRepository.count(),
                "Reminder notifications must not change on 409");
        assertTrue(tripRetainsUser(tripRepository.findByIdWithUsers(fixture.tripId()).orElseThrow(), fixture.user()),
                "Parent must stay assigned on 409");
    }

    // ── Fixtures / helpers ───────────────────────────────────────────────────

    private FinancialFixture bootstrapFixture(String prefix) throws Exception {
        return bootstrapFixture(prefix, false);
    }

    private FinancialFixture bootstrapFixtureWithPending(String prefix) throws Exception {
        return bootstrapFixture(prefix, true);
    }

    private FinancialFixture bootstrapFixture(String prefix, boolean withPending) throws Exception {
        TokenDTO adminTokens = signUpAdmin(buildValidUser("admin-" + prefix));
        UserCreateDTO parentDto = buildValidUser(prefix);
        TokenDTO userTokens = signUp(parentDto);
        User parent = userRepository.findByEmail(parentDto.email()).orElseThrow();
        Student student = studentRepository.findByParentId(parent.getId()).stream().findFirst().orElseThrow();
        String studentDni = student.getDni();

        Trip trip = new Trip();
        trip.setName("Trip " + prefix);
        trip.setCurrency(Currency.ARS);
        trip.setTotalAmount(BigDecimal.valueOf(3000));
        trip.setFirstInstallmentAmount(BigDecimal.valueOf(1000));
        trip.setInstallmentsCount(3);
        trip.setDueDay(10);
        trip.setYellowWarningDays(5);
        trip.setFixedFineAmount(BigDecimal.ZERO);
        trip.setRetroactiveActive(false);
        trip.setFirstDueDate(LocalDate.now().plusMonths(1));
        trip.getAssignedUsers().add(parent);
        trip = tripRepository.save(trip);

        Installment installment = new Installment();
        installment.setTrip(trip);
        installment.setUser(parent);
        installment.setStudent(student);
        installment.setInstallmentNumber(1);
        installment.setDueDate(LocalDate.now().plusMonths(1));
        installment.setCapitalAmount(BigDecimal.valueOf(1000));
        installment.setRetroactiveAmount(BigDecimal.ZERO);
        installment.setFineAmount(BigDecimal.ZERO);
        installment.setPaidAmount(BigDecimal.ZERO);
        installment.setStatus(InstallmentStatus.YELLOW);
        installment.recalculateTotalDue();
        installment = installmentRepository.save(installment);

        if (withPending) {
            PendingTripStudent pending = new PendingTripStudent();
            pending.setTrip(trip);
            pending.setStudentDni(studentDni);
            pendingTripStudentRepository.save(pending);
        }

        return new FinancialFixture(adminTokens, userTokens, parent, student, studentDni, trip, installment);
    }

    private PaymentSubmission persistSubmissionAnchored(FinancialFixture fixture, PaymentSubmissionStatus status) {
        return persistSubmissionAnchored(fixture, fixture.installment(), fixture.student(), status);
    }

    private PaymentSubmission persistSubmissionAnchored(
            FinancialFixture fixture,
            Installment anchorInstallment,
            Student student,
            PaymentSubmissionStatus status
    ) {
        PaymentSubmission submission = new PaymentSubmission();
        submission.setTrip(fixture.trip());
        submission.setUser(fixture.user());
        submission.setStudent(student);
        submission.setAnchorInstallment(anchorInstallment);
        submission.setReportedAmount(new BigDecimal("1500.00"));
        submission.setPaymentCurrency(Currency.ARS);
        submission.setExchangeRate(new BigDecimal("1.00"));
        submission.setAmountInTripCurrency(new BigDecimal("1500.00"));
        submission.setReportedPaymentDate(LocalDate.now());
        submission.setPaymentMethod(PaymentMethod.BANK_TRANSFER);
        submission.setStatus(status);
        submission.setFileKey(status + "-receipt.png");
        submission.setCreatedAt(LocalDateTime.now());
        return paymentSubmissionRepository.save(submission);
    }

    private Installment persistInstallment(FinancialFixture fixture, Student student, int installmentNumber) {
        Installment installment = new Installment();
        installment.setTrip(fixture.trip());
        installment.setUser(fixture.user());
        installment.setStudent(student);
        installment.setInstallmentNumber(installmentNumber);
        installment.setDueDate(LocalDate.now().plusMonths(installmentNumber));
        installment.setCapitalAmount(BigDecimal.valueOf(1000));
        installment.setRetroactiveAmount(BigDecimal.ZERO);
        installment.setFineAmount(BigDecimal.ZERO);
        installment.setPaidAmount(BigDecimal.ZERO);
        installment.setStatus(InstallmentStatus.YELLOW);
        installment.recalculateTotalDue();
        return installmentRepository.save(installment);
    }

    private PaymentOutcome persistOutcome(PaymentSubmission submission, PaymentOutcomeStatus status, BigDecimal amount) {
        PaymentOutcome outcome = new PaymentOutcome();
        outcome.setSubmission(submission);
        outcome.setStatus(status);
        outcome.setReportedAmount(amount);
        outcome.setAmountInTripCurrency(amount);
        outcome.setResolvedByEmail("admin@test.com");
        return paymentOutcomeRepository.save(outcome);
    }

    private PaymentAllocation persistAllocation(PaymentOutcome outcome, Installment installment,
                                                BigDecimal amount, int order) {
        PaymentAllocation allocation = new PaymentAllocation();
        allocation.setOutcome(outcome);
        allocation.setInstallment(installment);
        allocation.setAllocationOrder(order);
        allocation.setReportedAmount(amount);
        allocation.setAmountInTripCurrency(amount);
        return paymentAllocationRepository.save(allocation);
    }

    private void assertFinancialRecordsIntact(FinancialFixture fixture, BigDecimal expectedPaid) {
        // Installment survives on rejection with its exact prior paidAmount.
        Installment reloaded = installmentRepository.findById(fixture.installment().getId()).orElseThrow();
        assertEquals(0, expectedPaid.compareTo(reloaded.getPaidAmount()),
                "paidAmount must remain unchanged on 409");
        // The installment's identity (trip + user + student + number) must be untouched.
        assertEquals(fixture.trip().getId(), reloaded.getTrip().getId(),
                "Installment.trip must be unchanged on 409");
        assertEquals(fixture.user().getId(), reloaded.getUser().getId(),
                "Installment.user must be unchanged on 409");
        assertEquals(fixture.student().getId(), reloaded.getStudent().getId(),
                "Installment.student must be unchanged on 409");
        assertEquals(fixture.installment().getInstallmentNumber(), reloaded.getInstallmentNumber(),
                "Installment.installmentNumber must be unchanged on 409");
        // Parent stays assigned.
        Trip trip = tripRepository.findByIdWithUsers(fixture.tripId()).orElseThrow();
        assertTrue(tripRetainsUser(trip, fixture.user()),
                "Parent must stay assigned on 409");
        // The student's own identity and parent binding is unchanged (the rejection is scoped
        // to financial activity, not to the student entity).
        Student reloadedStudent = studentRepository.findById(fixture.student().getId()).orElseThrow();
        assertEquals(fixture.user().getId(), reloadedStudent.getParent().getId(),
                "Student.parent binding must remain unchanged on 409");
    }

    private static boolean tripRetainsUser(Trip trip, User user) {
        return trip.getAssignedUsers().stream().anyMatch(u -> u.getId().equals(user.getId()));
    }

    private record FinancialFixture(
            TokenDTO adminToken,
            TokenDTO userToken,
            User user,
            Student student,
            String studentDni,
            Trip trip,
            Installment installment
    ) {
        Long tripId() {
            return trip.getId();
        }
    }
}
