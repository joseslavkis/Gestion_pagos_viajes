package com.agencia.pagos.controllers;

import com.agencia.pagos.dtos.request.AdminCreateDTO;
import com.agencia.pagos.dtos.request.StudentCreateDTO;
import com.agencia.pagos.dtos.request.UserCreateDTO;
import com.agencia.pagos.dtos.request.UserLoginDTO;
import com.agencia.pagos.dtos.response.TokenDTO;
import com.agencia.pagos.entities.Currency;
import com.agencia.pagos.entities.PendingTripStudent;
import com.agencia.pagos.entities.Role;
import com.agencia.pagos.entities.Trip;
import com.agencia.pagos.entities.user.User;
import com.agencia.pagos.repositories.BankAccountRepository;
import com.agencia.pagos.repositories.InstallmentReminderNotificationRepository;
import com.agencia.pagos.repositories.InstallmentRepository;
import com.agencia.pagos.repositories.PasswordResetTokenRepository;
import com.agencia.pagos.repositories.PaymentBatchRepository;
import com.agencia.pagos.repositories.PaymentOutcomeRepository;
import com.agencia.pagos.repositories.PaymentReceiptRepository;
import com.agencia.pagos.repositories.PaymentAllocationRepository;
import com.agencia.pagos.repositories.PaymentSubmissionRepository;
import com.agencia.pagos.repositories.PendingTripStudentRepository;
import com.agencia.pagos.repositories.RefreshTokenRepository;
import com.agencia.pagos.repositories.SchoolRepository;
import com.agencia.pagos.repositories.StudentRepository;
import com.agencia.pagos.repositories.TripRepository;
import com.agencia.pagos.repositories.UserRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Base64;
import java.util.ArrayList;
import java.util.concurrent.atomic.AtomicLong;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

abstract class ControllerIntegrationTestSupport {

    private static final AtomicLong DNI_SEQUENCE = new AtomicLong(10_000_000L);

    @Autowired
    protected MockMvc mockMvc;

    @Autowired
    protected ObjectMapper objectMapper;

    @Autowired
    protected RefreshTokenRepository refreshTokenRepository;

    @Autowired
    protected UserRepository userRepository;

    @Autowired
    protected TripRepository tripRepository;

    @Autowired
    protected PendingTripStudentRepository pendingTripStudentRepository;

    @Autowired
    protected InstallmentRepository installmentRepository;

    @Autowired
    protected PaymentReceiptRepository paymentReceiptRepository;

    @Autowired
    protected PaymentBatchRepository paymentBatchRepository;

    @Autowired
    protected PaymentSubmissionRepository paymentSubmissionRepository;

    @Autowired
    protected PaymentOutcomeRepository paymentOutcomeRepository;

    @Autowired
    protected PaymentAllocationRepository paymentAllocationRepository;

    @Autowired
    protected InstallmentReminderNotificationRepository installmentReminderNotificationRepository;

    @Autowired
    protected BankAccountRepository bankAccountRepository;

    @Autowired
    protected SchoolRepository schoolRepository;

    @Autowired
    protected StudentRepository studentRepository;

    @Autowired
    protected PasswordResetTokenRepository passwordResetTokenRepository;

    @Autowired
    protected PasswordEncoder passwordEncoder;

    @Autowired
    protected TransactionTemplate transactionTemplate;

    @BeforeEach
    void cleanDatabase() {
        installmentReminderNotificationRepository.deleteAll();
        paymentAllocationRepository.deleteAll();
        paymentOutcomeRepository.deleteAll();
        paymentSubmissionRepository.deleteAll();
        paymentReceiptRepository.deleteAll();
        paymentBatchRepository.deleteAll();
        installmentRepository.deleteAll();
        pendingTripStudentRepository.deleteAll();
        bankAccountRepository.deleteAll();
        tripRepository.deleteAll();
        schoolRepository.deleteAll();
        passwordResetTokenRepository.deleteAll();
        refreshTokenRepository.deleteAll();
        userRepository.deleteAll();
    }

    protected TokenDTO signUp(UserCreateDTO dto) throws Exception {
        Trip claimableTrip = seedPendingTrip(
                dto.email(),
                dto.students().stream().map(StudentCreateDTO::dni).toList()
        );
        try {
            return signUpPrepared(dto);
        } finally {
            purgeTripData(claimableTrip.getId());
        }
    }

    protected TokenDTO signUpPrepared(UserCreateDTO dto) throws Exception {
        MvcResult result = mockMvc.perform(post("/api/v1/auth/signup")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(dto)))
                .andExpect(status().isCreated())
                .andReturn();
        return objectMapper.readValue(result.getResponse().getContentAsString(), TokenDTO.class);
    }

    protected ResultActions postSignup(UserCreateDTO dto) throws Exception {
        return mockMvc.perform(post("/api/v1/auth/signup")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(dto)));
    }

    protected TokenDTO login(UserLoginDTO dto) throws Exception {
        MvcResult result = mockMvc.perform(post("/api/v1/auth/token")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(dto)))
                .andExpect(status().isOk())
                .andReturn();
        return objectMapper.readValue(result.getResponse().getContentAsString(), TokenDTO.class);
    }

    protected TokenDTO signUpAdmin(UserCreateDTO dto) throws Exception {
        User user = dto.asUser(passwordEncoder::encode, Role.ADMIN);
        userRepository.save(user);
        return login(new UserLoginDTO(dto.email(), dto.password()));
    }

    protected Trip seedPendingTrip(String studentDni) {
        return seedPendingTrip("pending-" + studentDni, List.of(studentDni));
    }

    protected Trip seedPendingTrip(String prefix, List<String> studentDnis) {
        Trip trip = new Trip();
        trip.setName("Trip setup " + prefix + "-" + System.nanoTime());
        trip.setCurrency(Currency.ARS);
        trip.setTotalAmount(new BigDecimal("120000.00"));
        trip.setInstallmentsCount(3);
        trip.setDueDay(10);
        trip.setYellowWarningDays(5);
        trip.setFixedFineAmount(new BigDecimal("1500.00"));
        trip.setRetroactiveActive(false);
        trip.setFirstDueDate(LocalDate.now().plusMonths(2));
        Trip savedTrip = tripRepository.save(trip);

        List<PendingTripStudent> pendingAssignments = new ArrayList<>();
        for (String studentDni : studentDnis) {
            PendingTripStudent pendingTripStudent = new PendingTripStudent();
            pendingTripStudent.setTrip(savedTrip);
            pendingTripStudent.setStudentDni(studentDni);
            pendingAssignments.add(pendingTripStudent);
        }
        pendingTripStudentRepository.saveAll(pendingAssignments);
        return savedTrip;
    }

    protected void purgeTripData(Long tripId) {
        transactionTemplate.executeWithoutResult(status -> {
            installmentReminderNotificationRepository.deleteByInstallmentTripId(tripId);
            List<Long> batchIds = paymentReceiptRepository.findDistinctBatchIdsByInstallmentTripId(tripId);
            paymentAllocationRepository.deleteByTripId(tripId);
            paymentOutcomeRepository.deleteByTripId(tripId);
            paymentSubmissionRepository.deleteByTripId(tripId);
            paymentReceiptRepository.deleteByInstallmentTripId(tripId);
            paymentBatchRepository.deleteAllById(batchIds);
            installmentRepository.deleteByTripId(tripId);
            pendingTripStudentRepository.deleteByTripId(tripId);
            tripRepository.deleteById(tripId);
        });
    }

    protected Long extractId(String accessToken) throws Exception {
        String[] parts = accessToken.split("\\.");
        String payload = new String(Base64.getUrlDecoder().decode(parts[1]));
        String email = objectMapper.readTree(payload).get("sub").asText();
        return userRepository.findByEmail(email).orElseThrow().getId();
    }

    protected UserCreateDTO buildValidUser(String prefix) {
        return new UserCreateDTO(
                uniqueEmail(prefix),
                "Password123!",
                "Test",
                "User",
                uniqueDni(),
                "123456789",
                List.of(new StudentCreateDTO(
                        "Alumno",
                        "Test",
                        uniqueDni()
                ))
        );
    }

    protected AdminCreateDTO buildValidAdmin(String prefix) {
        return new AdminCreateDTO(
                uniqueEmail(prefix),
                "Password123!",
                "Admin",
                "User",
                uniqueDni(),
                "123456789"
        );
    }

    protected String uniqueEmail(String prefix) {
        return prefix + "-" + System.nanoTime() + "@agencia.com";
    }

    protected String uniqueDni() {
        return String.valueOf(DNI_SEQUENCE.getAndIncrement());
    }

}
