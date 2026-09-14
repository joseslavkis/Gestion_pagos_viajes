package com.agencia.pagos.controllers;

import com.agencia.pagos.TestcontainersConfiguration;
import com.agencia.pagos.dtos.request.ReviewPaymentDTO;
import com.agencia.pagos.dtos.request.StudentCreateDTO;
import com.agencia.pagos.dtos.request.UserAssignBulkDTO;
import com.agencia.pagos.dtos.request.UserCreateDTO;
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
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.boot.test.mock.mockito.SpyBean;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.mail.javamail.JavaMailSender;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.doAnswer;
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
