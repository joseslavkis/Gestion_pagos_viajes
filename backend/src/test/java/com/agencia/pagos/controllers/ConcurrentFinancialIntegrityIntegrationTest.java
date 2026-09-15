package com.agencia.pagos.controllers;

import com.agencia.pagos.TestcontainersConfiguration;
import com.agencia.pagos.dtos.request.ReviewPaymentDTO;
import com.agencia.pagos.dtos.request.StudentCreateDTO;
import com.agencia.pagos.dtos.request.TripUpdateDTO;
import com.agencia.pagos.dtos.request.UserAssignBulkDTO;
import com.agencia.pagos.dtos.request.UserCreateDTO;
import com.agencia.pagos.dtos.response.BulkAssignResultDTO;
import com.agencia.pagos.dtos.response.TokenDTO;
import com.agencia.pagos.entities.Currency;
import com.agencia.pagos.entities.Installment;
import com.agencia.pagos.entities.InstallmentStatus;
import com.agencia.pagos.entities.PaymentAllocation;
import com.agencia.pagos.entities.PaymentMethod;
import com.agencia.pagos.entities.PaymentOutcome;
import com.agencia.pagos.entities.PaymentOutcomeStatus;
import com.agencia.pagos.entities.PaymentSubmission;
import com.agencia.pagos.entities.PaymentSubmissionStatus;
import com.agencia.pagos.entities.Role;
import com.agencia.pagos.entities.Student;
import com.agencia.pagos.entities.Trip;
import com.agencia.pagos.entities.user.User;
import com.agencia.pagos.services.PaymentService;
import com.agencia.pagos.services.TripService;
import com.agencia.pagos.services.storage.PaymentAttachmentStorageService;
import jakarta.persistence.EntityNotFoundException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.boot.test.mock.mockito.SpyBean;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mail.javamail.JavaMailSender;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.LockSupport;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(properties = {
        "app.mail.to=test@agencia.com",
        "app.mail.from=no-reply@agencia.com"
})
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
class ConcurrentFinancialIntegrityIntegrationTest extends ControllerIntegrationTestSupport {

    private enum PaymentOperation {
        REVIEW,
        VOID
    }

    @MockBean
    private JavaMailSender javaMailSender;

    @MockBean
    private PaymentAttachmentStorageService paymentAttachmentStorageService;

    @SpyBean
    private TripService tripServiceSpy;

    @SpyBean
    private PaymentService paymentServiceSpy;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Test
    void claimPendingStudent_serializesConcurrentPendingRegistrationForSameDni() throws Exception {
        String studentDni = uniqueDni();
        Trip firstTrip = seedPendingTrip("claim-race-first", List.of(studentDni));
        Trip secondTrip = createTrip("claim-race-second");
        TokenDTO adminToken = signUpAdmin(buildValidUser("admin-claim-race"));
        UserCreateDTO userDto = new UserCreateDTO(
                uniqueEmail("claim-race-user"),
                "Password123!",
                "Race",
                "Parent",
                uniqueDni(),
                "123456789",
                List.of(new StudentCreateDTO("Race", "Student", studentDni))
        );

        CountDownLatch claimLockAcquired = new CountDownLatch(1);
        CountDownLatch assignmentStarted = new CountDownLatch(1);
        CountDownLatch releaseClaim = new CountDownLatch(1);
        doAnswer(invocation -> {
            Map<Long, Trip> result = (Map<Long, Trip>) invocation.callRealMethod();
            claimLockAcquired.countDown();
            await(releaseClaim, "claim lock release");
            return result;
        }).when(tripServiceSpy).lockCandidateTripsForStudentDni(studentDni);
        doAnswer(invocation -> {
            assignmentStarted.countDown();
            return invocation.callRealMethod();
        }).when(tripServiceSpy).assignUsersInBulk(
                org.mockito.ArgumentMatchers.eq(secondTrip.getId()),
                org.mockito.ArgumentMatchers.any(UserAssignBulkDTO.class)
        );

        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            Future<Integer> claim = executor.submit(() -> mockMvc.perform(post("/api/v1/auth/signup")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(userDto)))
                    .andReturn()
                    .getResponse()
                    .getStatus());

            assertTrue(claimLockAcquired.await(10, TimeUnit.SECONDS));

            Future<Integer> assignment = executor.submit(() -> mockMvc.perform(post(
                            "/api/v1/trips/{id}/users/bulk", secondTrip.getId())
                    .header("Authorization", "Bearer " + adminToken.accessToken())
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("{\"studentDnis\":[\"" + studentDni + "\"]}"))
                    .andReturn()
                    .getResponse()
                    .getStatus());

            assertTrue(assignmentStarted.await(10, TimeUnit.SECONDS));
            assertFalse(assignment.isDone(), "the second registration must wait for the DNI lock");

            releaseClaim.countDown();

            assertEquals(201, claim.get(15, TimeUnit.SECONDS));
            assertEquals(200, assignment.get(15, TimeUnit.SECONDS));
        } finally {
            releaseClaim.countDown();
            executor.shutdownNow();
        }

        Student claimedStudent = studentRepository.findByDni(studentDni).orElseThrow();
        assertTrue(pendingTripStudentRepository.findByTripIdAndStudentDni(firstTrip.getId(), studentDni).isEmpty());
        assertTrue(pendingTripStudentRepository.findByTripIdAndStudentDni(secondTrip.getId(), studentDni).isEmpty());
        assertFalse(installmentRepository.findByTripIdAndStudentDni(firstTrip.getId(), studentDni).isEmpty());
        assertFalse(installmentRepository.findByTripIdAndStudentDni(secondTrip.getId(), studentDni).isEmpty());
        assertTrue(claimedStudent.getId() > 0);
    }

    @ParameterizedTest
    @EnumSource(PaymentOperation.class)
    void deleteTrip_locksSubmissionsBeforeConcurrentPaymentMutation(PaymentOperation operation) throws Exception {
        PaymentFixture fixture = createPaymentFixture(operation == PaymentOperation.VOID);
        CountDownLatch submissionLockAcquired = new CountDownLatch(1);
        CountDownLatch deleteStarted = new CountDownLatch(1);
        CountDownLatch paymentStarted = new CountDownLatch(1);
        CountDownLatch releaseSubmissionLock = new CountDownLatch(1);

        ExecutorService executor = Executors.newFixedThreadPool(3);
        try {
            Future<?> lockHolder = executor.submit(() -> transactionTemplate.executeWithoutResult(status -> {
                paymentSubmissionRepository.findByIdForUpdate(fixture.submission().getId());
                submissionLockAcquired.countDown();
                await(releaseSubmissionLock, "submission lock release");
            }));
            assertTrue(submissionLockAcquired.await(10, TimeUnit.SECONDS));

            doAnswer(invocation -> {
                deleteStarted.countDown();
                return invocation.callRealMethod();
            }).when(tripServiceSpy).deleteTrip(fixture.trip().getId());

            Future<?> delete = executor.submit(() -> tripServiceSpy.deleteTrip(fixture.trip().getId()));
            Future<?> payment;
            if (operation == PaymentOperation.REVIEW) {
                doAnswer(invocation -> {
                    paymentStarted.countDown();
                    return invocation.callRealMethod();
                }).when(paymentServiceSpy).reviewPayment(
                        org.mockito.ArgumentMatchers.eq(fixture.submission().getId()),
                        org.mockito.ArgumentMatchers.any(ReviewPaymentDTO.class),
                        org.mockito.ArgumentMatchers.eq("admin@test.com")
                );
                payment = executor.submit(() -> paymentServiceSpy.reviewPayment(
                        fixture.submission().getId(),
                        new ReviewPaymentDTO(new BigDecimal("100.00"), null),
                        "admin@test.com"
                ));
            } else {
                doAnswer(invocation -> {
                    paymentStarted.countDown();
                    return invocation.callRealMethod();
                }).when(paymentServiceSpy).voidPayment(
                        org.mockito.ArgumentMatchers.eq(fixture.submission().getId()),
                        org.mockito.ArgumentMatchers.eq("admin@test.com")
                );
                payment = executor.submit(() -> paymentServiceSpy.voidPayment(
                        fixture.submission().getId(), "admin@test.com"
                ));
            }

            assertTrue(deleteStarted.await(10, TimeUnit.SECONDS));
            assertTrue(paymentStarted.await(10, TimeUnit.SECONDS));

            // deleteTrip must lock submissions before deleting outcomes. The held payment lock
            // therefore keeps the whole financial graph intact until the lock holder releases.
            Thread.sleep(250);
            assertTrue(paymentSubmissionRepository.findById(fixture.submission().getId()).isPresent());
            if (fixture.outcome() != null) {
                assertTrue(paymentOutcomeRepository.findById(fixture.outcome().getId()).isPresent());
            }

            releaseSubmissionLock.countDown();

            delete.get(15, TimeUnit.SECONDS);
            try {
                payment.get(15, TimeUnit.SECONDS);
            } catch (ExecutionException failure) {
                assertInstanceOf(EntityNotFoundException.class, failure.getCause());
            }
            lockHolder.get(15, TimeUnit.SECONDS);
        } finally {
            releaseSubmissionLock.countDown();
            executor.shutdownNow();
        }

        assertTrue(tripRepository.findById(fixture.trip().getId()).isEmpty());
        assertTrue(paymentSubmissionRepository.findById(fixture.submission().getId()).isEmpty());
    }

    private Trip createTrip(String name) {
        Trip trip = new Trip();
        trip.setName(name);
        trip.setCurrency(Currency.ARS);
        trip.setTotalAmount(new BigDecimal("3000.00"));
        trip.setFirstInstallmentAmount(new BigDecimal("1000.00"));
        trip.setInstallmentsCount(3);
        trip.setDueDay(10);
        trip.setYellowWarningDays(5);
        trip.setFixedFineAmount(BigDecimal.ZERO);
        trip.setRetroactiveActive(false);
        trip.setFirstDueDate(LocalDate.now().plusMonths(1));
        return tripRepository.save(trip);
    }

    /**
     * Calendar integrity under concurrent materialization. Thread A invokes the real
     * {@code assignUsersInBulk} flow and is held open by the {@link TripService}
     * {@code @SpyBean} after {@code callRealMethod()} returns — at that point the
     * Trip FOR UPDATE has been acquired and installments have been persisted
     * (uncommitted, inside A's still-open transaction). Thread B invokes the real
     * PATCH {@code updateTrip} with a different {@code dueDay}; the spy captures
     * B's PostgreSQL backend PID from the transaction-bound connection BEFORE
     * {@code callRealMethod()} runs. The test thread then uses
     * {@code pg_blocking_pids(?)} on a JDBC connection from the same pool to
     * <em>observe</em> that B's session is genuinely blocked on A's lock before
     * asserting anything else. This DB-state observation is the primary proof; the
     * spy's {@code bCapturedPid} latch only signals that B's call has been entered,
     * not that B has reached the lock attempt yet (which is a real race that
     * {@code Future.isDone()} alone cannot exclude — a regression that drops
     * {@code findByIdForUpdate} would let B finish without ever being a waiter).
     * Once the waiter is observed, releasing A lets B acquire the lock, observe the
     * freshly committed installment, and return HTTP 409.
     *
     * <p>Production-hook-free seam: the only test instrumentation is two
     * {@code doAnswer} stubs on the pre-existing {@link TripService}
     * {@code @SpyBean}. No new {@code @SpyBean} on a Spring Data repository, no
     * mutable production code, no synchronization on a production field. The
     * PostgreSQL {@code pg_backend_pid()} / {@code pg_blocking_pids(?)} queries are
     * diagnostic reads on a Testcontainers-managed database.
     */
    @Test
    void updateTrip_bloqueaEnTripForUpdate_hastaQueCommitDeCuotaTermine_yDevuelve409() throws Exception {
        TokenDTO adminTokens = signUpAdmin(buildValidUser("admin-update-concurrency"));
        UserCreateDTO userDto = buildValidUser("user-update-concurrency");
        signUp(userDto);
        String studentDni = userDto.students().get(0).dni();

        LocalDate originalFirstDueDate = LocalDate.now().plusMonths(2);
        int originalDueDay = 10;
        Trip created = createTrip("update-concurrency-trip");
        created.setFirstDueDate(originalFirstDueDate);
        created.setDueDay(originalDueDay);
        final Long tripId = tripRepository.save(created).getId();
        final int expectedInstallmentCount = created.getInstallmentsCount();

        CountDownLatch tripLockAcquired = new CountDownLatch(1);
        CountDownLatch releaseTripLock = new CountDownLatch(1);
        AtomicLong bBackendPid = new AtomicLong(-1L);
        CountDownLatch bCapturedPid = new CountDownLatch(1);

        // Spy stub for A: hold the transaction open after callRealMethod until releaseTripLock.
        // The safety timeout is set generously above B's bounded lock-observation window
        // (10s) and the future-completion window (15s) so a slow CI cannot consume the
        // margin before pg_blocking_pids is observed.
        doAnswer(invocation -> {
            BulkAssignResultDTO result = (BulkAssignResultDTO) invocation.callRealMethod();
            tripLockAcquired.countDown();
            try {
                releaseTripLock.await(60, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new RuntimeException(e);
            }
            return result;
        }).when(tripServiceSpy).assignUsersInBulk(eq(tripId), any(UserAssignBulkDTO.class));

        // Spy stub for B: capture B's backend PID from the transaction-bound connection
        // BEFORE callRealMethod() runs. The CGLIB @Transactional proxy opens the
        // transaction before delegating to the spy, so JdbcTemplate here uses the same
        // connection that will run findByIdForUpdate and observe the lock wait.
        doAnswer(invocation -> {
            Long pid = jdbcTemplate.queryForObject("SELECT pg_backend_pid()", Long.class);
            bBackendPid.set(pid);
            bCapturedPid.countDown();
            return invocation.callRealMethod();
        }).when(tripServiceSpy).updateTrip(eq(tripId), any(TripUpdateDTO.class));

        ExecutorService executor = Executors.newFixedThreadPool(2);
        Future<Integer> assignResult = null;
        Future<Integer> updateResult = null;
        Throwable primaryException = null;
        try {
            // A: real POST /api/v1/trips/{id}/users/bulk. The endpoint goes through the
            // CGLIB @Transactional proxy on TripService, which opens a transaction
            // BEFORE the spy intercepts assignUsersInBulk — so the Trip FOR UPDATE is
            // held inside an active transaction that the spy keeps open.
            assignResult = executor.submit(() -> mockMvc.perform(
                            post("/api/v1/trips/{id}/users/bulk", tripId)
                                    .header("Authorization", "Bearer " + adminTokens.accessToken())
                                    .contentType(MediaType.APPLICATION_JSON)
                                    .content(objectMapper.writeValueAsString(new UserAssignBulkDTO(List.of(studentDni)))))
                    .andReturn()
                    .getResponse()
                    .getStatus());

            assertTrue(tripLockAcquired.await(10, TimeUnit.SECONDS),
                    "transaction A must acquire the Trip FOR UPDATE inside assignUsersInBulk and signal it");

            // B: real PATCH /api/v1/trips/{id} with a different dueDay.
            TripUpdateDTO patchDto = new TripUpdateDTO(null, 25, null, null, null, null);
            updateResult = executor.submit(() -> mockMvc.perform(
                            patch("/api/v1/trips/{id}", tripId)
                                    .header("Authorization", "Bearer " + adminTokens.accessToken())
                                    .contentType(MediaType.APPLICATION_JSON)
                                    .content(objectMapper.writeValueAsString(patchDto)))
                    .andReturn()
                    .getResponse()
                    .getStatus());

            assertTrue(bCapturedPid.await(10, TimeUnit.SECONDS),
                    "transaction B must have entered TripService.updateTrip and captured its backend PID");
            long bPid = bBackendPid.get();
            assertTrue(bPid > 0L, "B's backend PID must be valid, got " + bPid);

            // PRIMARY DB-STATE PROOF: poll pg_blocking_pids(?) for the bounded deadline.
            // This catches a regression that removes findByIdForUpdate — B would finish
            // without ever being a waiter and the observation would time out before any
            // release. The small non-Thread.sleep backoff uses LockSupport.parkNanos so no
            // thread suspension API is used as the primary synchronization.
            int bPidInt = (int) bPid;
            long deadlineNanos = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
            boolean bObservedBlocked = false;
            while (System.nanoTime() < deadlineNanos) {
                Boolean blocked = jdbcTemplate.queryForObject(
                        "SELECT cardinality(pg_blocking_pids(?)) > 0",
                        Boolean.class,
                        bPidInt
                );
                if (Boolean.TRUE.equals(blocked)) {
                    bObservedBlocked = true;
                    break;
                }
                LockSupport.parkNanos(TimeUnit.MILLISECONDS.toNanos(10));
            }
            assertTrue(bObservedBlocked,
                    "PostgreSQL must observe B's PID " + bPid + " as blocked (waiting on A's Trip FOR UPDATE) within the bounded deadline");

            assertFalse(updateResult.isDone(),
                    "B's updateTrip PATCH must still be incomplete while blocked on A's lock");

            releaseTripLock.countDown();

            assertEquals(409, updateResult.get(15, TimeUnit.SECONDS),
                    "after A commits, B must observe the new installment and return 409");
            assertEquals(200, assignResult.get(15, TimeUnit.SECONDS),
                    "A's bulk assignment must complete normally once the lock is released");
        } catch (Throwable t) {
            primaryException = t;
            throw t;
        } finally {
            Throwable teardownFailure = null;
            try {
                releaseTripLock.countDown();
                executor.shutdown();
                boolean terminated = false;
                try {
                    if (executor.awaitTermination(3, TimeUnit.SECONDS)) {
                        terminated = true;
                    } else {
                        executor.shutdownNow();
                        if (executor.awaitTermination(3, TimeUnit.SECONDS)) {
                            terminated = true;
                        }
                    }
                } catch (InterruptedException e) {
                    executor.shutdownNow();
                    Thread.currentThread().interrupt();
                }
                if (!terminated) {
                    teardownFailure = new IllegalStateException(
                            "Executor failed to terminate after graceful + forced shutdown");
                }
            } catch (Throwable t) {
                teardownFailure = t;
            }
            if (assignResult != null && !assignResult.isDone()) {
                assignResult.cancel(true);
            }
            if (updateResult != null && !updateResult.isDone()) {
                updateResult.cancel(true);
            }
            // Fail explicitly if the executor leaked live workers, but do not mask a
            // primary assertion failure that is already in flight — attach as suppressed.
            if (teardownFailure != null) {
                if (primaryException != null) {
                    primaryException.addSuppressed(teardownFailure);
                } else if (teardownFailure instanceof RuntimeException) {
                    throw (RuntimeException) teardownFailure;
                } else {
                    throw new RuntimeException(teardownFailure);
                }
            }
        }

        Trip persistedTrip = tripRepository.findById(tripId).orElseThrow();
        assertEquals(originalDueDay, persistedTrip.getDueDay(),
                "Trip dueDay must remain unchanged when updateTrip returns 409");
        assertEquals(originalFirstDueDate, persistedTrip.getFirstDueDate(),
                "Trip firstDueDate must remain unchanged when updateTrip returns 409");
        List<Installment> installments = installmentRepository.findByTripIdWithUsers(tripId).stream()
                .sorted(Comparator.comparingInt(Installment::getInstallmentNumber))
                .toList();
        assertEquals(expectedInstallmentCount, installments.size(),
                "all " + expectedInstallmentCount + " installments must have been materialized by the concurrent assignUsersInBulk flow");
        for (Installment inst : installments) {
            LocalDate expected;
            if (inst.getInstallmentNumber() == 1) {
                expected = originalFirstDueDate;
            } else {
                LocalDate baseDate = originalFirstDueDate.plusMonths(inst.getInstallmentNumber() - 1L);
                int validDay = Math.min(originalDueDay, baseDate.lengthOfMonth());
                expected = baseDate.withDayOfMonth(validDay);
            }
            assertEquals(expected, inst.getDueDate(),
                    "installment #" + inst.getInstallmentNumber() + " dueDate must remain unchanged against the original calendar");
        }
    }

    private PaymentFixture createPaymentFixture(boolean approved) {
        User user = new User("Payment", "secret", uniqueEmail("payment-race"), "User", Role.USER);
        user.setDni(uniqueDni());
        user = userRepository.save(user);

        Student student = Student.builder()
                .parent(user)
                .name("Payment")
                .lastname("Student")
                .dni(uniqueDni())
                .build();
        student = studentRepository.save(student);

        Trip trip = createTrip("payment-race-trip");
        trip.getAssignedUsers().add(user);
        trip = tripRepository.save(trip);

        Installment installment = new Installment();
        installment.setTrip(trip);
        installment.setUser(user);
        installment.setStudent(student);
        installment.setInstallmentNumber(1);
        installment.setDueDate(LocalDate.now().plusMonths(1));
        installment.setCapitalAmount(new BigDecimal("100.00"));
        installment.setRetroactiveAmount(BigDecimal.ZERO);
        installment.setFineAmount(BigDecimal.ZERO);
        installment.setPaidAmount(approved ? new BigDecimal("100.00") : BigDecimal.ZERO);
        installment.setStatus(InstallmentStatus.YELLOW);
        installment.recalculateTotalDue();
        installment = installmentRepository.save(installment);

        PaymentSubmission submission = new PaymentSubmission();
        submission.setTrip(trip);
        submission.setUser(user);
        submission.setStudent(student);
        submission.setAnchorInstallment(installment);
        submission.setReportedAmount(new BigDecimal("100.00"));
        submission.setPaymentCurrency(Currency.ARS);
        submission.setExchangeRate(BigDecimal.ONE);
        submission.setAmountInTripCurrency(new BigDecimal("100.00"));
        submission.setReportedPaymentDate(LocalDate.now());
        submission.setPaymentMethod(PaymentMethod.BANK_TRANSFER);
        submission.setStatus(approved ? PaymentSubmissionStatus.RESOLVED : PaymentSubmissionStatus.PENDING);
        submission.setFileKey("concurrency-test.png");
        submission.setCreatedAt(LocalDateTime.now());
        submission = paymentSubmissionRepository.save(submission);

        PaymentOutcome outcome = null;
        if (approved) {
            outcome = new PaymentOutcome();
            outcome.setSubmission(submission);
            outcome.setStatus(PaymentOutcomeStatus.APPROVED);
            outcome.setReportedAmount(new BigDecimal("100.00"));
            outcome.setAmountInTripCurrency(new BigDecimal("100.00"));
            outcome.setResolvedByEmail("admin@test.com");
            outcome = paymentOutcomeRepository.save(outcome);

            PaymentAllocation allocation = new PaymentAllocation();
            allocation.setOutcome(outcome);
            allocation.setInstallment(installment);
            allocation.setAllocationOrder(1);
            allocation.setReportedAmount(new BigDecimal("100.00"));
            allocation.setAmountInTripCurrency(new BigDecimal("100.00"));
            allocation = paymentAllocationRepository.save(allocation);

            outcome.setAllocations(new LinkedHashSet<>(List.of(allocation)));
            paymentOutcomeRepository.save(outcome);
        }

        return new PaymentFixture(trip, submission, outcome);
    }

    private static void await(CountDownLatch latch, String name) {
        try {
            if (!latch.await(15, TimeUnit.SECONDS)) {
                throw new AssertionError("Timed out waiting for " + name);
            }
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new AssertionError("Interrupted while waiting for " + name, exception);
        }
    }

    private record PaymentFixture(Trip trip, PaymentSubmission submission, PaymentOutcome outcome) {
    }
}
