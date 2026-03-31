package com.agencia.pagos.controllers;

import com.agencia.pagos.TestcontainersConfiguration;
import com.agencia.pagos.dtos.request.TripCreateDTO;
import com.agencia.pagos.dtos.request.TripUpdateDTO;
import com.agencia.pagos.dtos.request.UserAssignBulkDTO;
import com.agencia.pagos.dtos.request.UserCreateDTO;
import com.agencia.pagos.dtos.response.TokenDTO;
import com.agencia.pagos.entities.Installment;
import com.agencia.pagos.entities.InstallmentReminderNotification;
import com.agencia.pagos.entities.InstallmentReminderNotificationType;
import com.agencia.pagos.entities.InstallmentStatus;
import com.agencia.pagos.entities.PaymentReceipt;
import com.agencia.pagos.entities.PaymentMethod;
import com.agencia.pagos.entities.Trip;
import com.agencia.pagos.entities.user.User;
import com.agencia.pagos.repositories.InstallmentRepository;
import com.agencia.pagos.repositories.InstallmentReminderNotificationRepository;
import com.agencia.pagos.repositories.PaymentReceiptRepository;
import com.agencia.pagos.repositories.StudentRepository;
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
import java.util.Comparator;
import java.util.List;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.greaterThan;
import static org.hamcrest.Matchers.hasSize;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

@SpringBootTest(properties = {
    "app.mail.to=test@agencia.com",
    "app.mail.from=no-reply@agencia.com"
})
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
class TripRestControllerTest extends ControllerIntegrationTestSupport {

    @MockBean
    private JavaMailSender javaMailSender;

    @Autowired
    private TripRepository tripRepository;

    @Autowired
    private InstallmentRepository installmentRepository;

    @Autowired
    private PaymentReceiptRepository paymentReceiptRepository;

    @Autowired
    private InstallmentReminderNotificationRepository installmentReminderNotificationRepository;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private StudentRepository studentRepository;

    @AfterEach
    void cleanUpTrips() {
        installmentReminderNotificationRepository.deleteAll();
        paymentReceiptRepository.deleteAll();
        installmentRepository.deleteAll();
        tripRepository.deleteAll();
    }

    private TripCreateDTO buildValidTrip() {
        return new TripCreateDTO(
                "Viaje a Bariloche",
                BigDecimal.valueOf(1000000),
                12,
                10,
                5,
                BigDecimal.valueOf(5000),
                true,
                LocalDate.now().plusMonths(1)
        );
    }

    private Trip buildTripForBulk(
            String name,
            BigDecimal totalAmount,
            int installmentsCount,
            int dueDay,
            BigDecimal fixedFineAmount,
            boolean retroactiveActive,
            LocalDate firstDueDate
    ) {
        Trip trip = new Trip();
        trip.setName(name);
        trip.setTotalAmount(totalAmount);
        trip.setInstallmentsCount(installmentsCount);
        trip.setDueDay(dueDay);
        trip.setYellowWarningDays(5);
        trip.setFixedFineAmount(fixedFineAmount);
        trip.setRetroactiveActive(retroactiveActive);
        trip.setFirstDueDate(firstDueDate);
        return tripRepository.save(trip);
    }

    @Test
    void createTrip_siendoAdmin_devuelve201() throws Exception {
        TokenDTO adminTokens = signUpAdmin(buildValidUser("admin-trip-creator"));
        TripCreateDTO dto = buildValidTrip();

        mockMvc.perform(post("/api/v1/trips")
                .header("Authorization", "Bearer " + adminTokens.accessToken())
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(dto)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").exists())
                .andExpect(jsonPath("$.name").value("Viaje a Bariloche"));
    }

    @Test
    void createTrip_siendoUserNormal_devuelve403() throws Exception {
        TokenDTO userTokens = signUp(buildValidUser("user-trip-creator"));
        TripCreateDTO dto = buildValidTrip();

        mockMvc.perform(post("/api/v1/trips")
                .header("Authorization", "Bearer " + userTokens.accessToken())
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(dto)))
                .andExpect(status().isForbidden());
    }

    @Test
    void getAllTrips_siendoAdmin_devuelve200() throws Exception {
        TokenDTO adminTokens = signUpAdmin(buildValidUser("admin-trip-reader"));

        TripCreateDTO dto = buildValidTrip();
        mockMvc.perform(post("/api/v1/trips")
                .header("Authorization", "Bearer " + adminTokens.accessToken())
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(dto)))
                .andExpect(status().isCreated());

        mockMvc.perform(get("/api/v1/trips")
                .header("Authorization", "Bearer " + adminTokens.accessToken()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(1)))
                .andExpect(jsonPath("$[0].name").value("Viaje a Bariloche"));
    }

    @Test
    void getTripById_siendoAdminYTripExiste_devuelve200() throws Exception {
        TokenDTO adminTokens = signUpAdmin(buildValidUser("admin-trip-reader2"));

        Trip trip = new Trip();
        trip.setName("Viaje a Carlos Paz");
        trip.setTotalAmount(BigDecimal.valueOf(500000));
        trip.setInstallmentsCount(6);
        trip.setDueDay(15);
        trip.setYellowWarningDays(3);
        trip.setFixedFineAmount(BigDecimal.valueOf(2000));
        trip.setRetroactiveActive(true);
        trip.setFirstDueDate(LocalDate.now().plusMonths(1));
        trip = tripRepository.save(trip);

        mockMvc.perform(get("/api/v1/trips/{id}", trip.getId())
                .header("Authorization", "Bearer " + adminTokens.accessToken()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(trip.getId()))
                .andExpect(jsonPath("$.name").value("Viaje a Carlos Paz"));
    }

    @Test
    void getTripById_noExiste_devuelve404() throws Exception {
        TokenDTO adminTokens = signUpAdmin(buildValidUser("admin-trip-not-found"));

        mockMvc.perform(get("/api/v1/trips/99999")
                .header("Authorization", "Bearer " + adminTokens.accessToken()))
                .andExpect(status().isNotFound());
    }

    @Test
    void updateTrip_siendoAdmin_devuelve200() throws Exception {
        TokenDTO adminTokens = signUpAdmin(buildValidUser("admin-trip-updater"));

        Trip trip = new Trip();
        trip.setName("Original Name");
        trip.setTotalAmount(BigDecimal.valueOf(100));
        trip.setInstallmentsCount(1);
        trip.setDueDay(1);
        trip.setYellowWarningDays(1);
        trip.setFixedFineAmount(BigDecimal.ZERO);
        trip.setRetroactiveActive(false);
        trip.setFirstDueDate(LocalDate.now());
        trip = tripRepository.save(trip);

        TripUpdateDTO patchDto = new TripUpdateDTO("Updated Name", 10, 5, BigDecimal.valueOf(100), true, LocalDate.now().plusMonths(2));

        mockMvc.perform(patch("/api/v1/trips/{id}", trip.getId())
                .header("Authorization", "Bearer " + adminTokens.accessToken())
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(patchDto)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.name").value("Updated Name"))
                .andExpect(jsonPath("$.dueDay").value(10));
    }

    @Test
    void deleteTrip_sinUsuarios_eliminaCorrectamente() throws Exception {
        TokenDTO adminTokens = signUpAdmin(buildValidUser("admin-trip-deleter"));

        Trip trip = new Trip();
        trip.setName("To Delete");
        trip.setTotalAmount(BigDecimal.valueOf(100));
        trip.setInstallmentsCount(1);
        trip.setDueDay(1);
        trip.setYellowWarningDays(1);
        trip.setFixedFineAmount(BigDecimal.ZERO);
        trip.setRetroactiveActive(false);
        trip.setFirstDueDate(LocalDate.now());
        trip = tripRepository.save(trip);

        mockMvc.perform(delete("/api/v1/trips/{id}", trip.getId())
                .header("Authorization", "Bearer " + adminTokens.accessToken()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("success"));

        mockMvc.perform(get("/api/v1/trips/{id}", trip.getId())
                .header("Authorization", "Bearer " + adminTokens.accessToken()))
                .andExpect(status().isNotFound());
    }

    @Test
    void assignUsersInBulk_retroactiveActive_calculatesCorrectly() throws Exception {
        TokenDTO adminTokens = signUpAdmin(buildValidUser("admin-bulk"));
        UserCreateDTO userDto = buildValidUser("user-bulk");
        signUp(userDto);

        Trip trip = new Trip();
        trip.setName("Viaje Retroactivo");
        trip.setTotalAmount(BigDecimal.valueOf(120000));
        trip.setInstallmentsCount(12); // Base capital = 10000
        trip.setDueDay(10);
        trip.setYellowWarningDays(5);
        trip.setFixedFineAmount(BigDecimal.valueOf(1000));
        trip.setRetroactiveActive(true);
        // Start date 2 months ago -> 2 quotas overdue
        trip.setFirstDueDate(LocalDate.now().minusMonths(2).withDayOfMonth(10));
        trip = tripRepository.save(trip);

        UserAssignBulkDTO dto = new UserAssignBulkDTO(List.of(userDto.students().get(0).dni()));

        mockMvc.perform(post("/api/v1/trips/{id}/users/bulk", trip.getId())
                .header("Authorization", "Bearer " + adminTokens.accessToken())
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(dto)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.assignedCount").value(1));

        LocalDate today = LocalDate.now();
        int expectedPast = 0;
        for (int i = 0; i < 12; i++) {
            LocalDate baseDate = trip.getFirstDueDate().plusMonths(i);
            int validDay = Math.min(trip.getDueDay(), baseDate.lengthOfMonth());
            if (baseDate.withDayOfMonth(validDay).isBefore(today)) { expectedPast++; }
        }
        int expectedFuture = 12 - expectedPast;

        List<Installment> installments = installmentRepository.findAll();

        // [Eje-1] All 12 installments are now persisted: past ones with RETROACTIVE, future ones with YELLOW
        assertEquals(12, installments.size());

        // Validate past installments have RETROACTIVE status and correct capital
        if (expectedPast > 0) {
            Installment firstPast = installments.stream()
                    .filter(i -> i.getInstallmentNumber() == 1)
                    .findFirst().orElseThrow();
            assertEquals(BigDecimal.valueOf(10000).setScale(2), firstPast.getCapitalAmount());
            assertEquals(BigDecimal.ZERO.setScale(2), firstPast.getRetroactiveAmount());
            assertEquals(BigDecimal.ZERO.setScale(2), firstPast.getFineAmount());
            assertEquals(InstallmentStatus.RETROACTIVE, firstPast.getStatus());
        }

        // Validate future installments no longer carry accumulated retroactive amount
        if (expectedFuture > 0) {
            final int targetInstallmentNum = expectedPast + 1;
            Installment firstFuture = installments.stream()
                    .filter(i -> i.getInstallmentNumber() == targetInstallmentNum)
                    .findFirst().orElseThrow();
            assertEquals(BigDecimal.valueOf(10000).setScale(2), firstFuture.getCapitalAmount());
            assertEquals(BigDecimal.ZERO.setScale(2), firstFuture.getRetroactiveAmount());
            assertEquals(InstallmentStatus.YELLOW, firstFuture.getStatus());
        }
    }


    @Test
    void assignUsersInBulk_retroactiveInactive_calculatesCorrectly() throws Exception {
        TokenDTO adminTokens = signUpAdmin(buildValidUser("admin-bulk-inactive"));
        UserCreateDTO userDto = buildValidUser("user-bulk-inactive");
        signUp(userDto);

        Trip trip = new Trip();
        trip.setName("Viaje No Retroactivo");
        trip.setTotalAmount(BigDecimal.valueOf(120000));
        trip.setInstallmentsCount(12); // Base capital = 10000
        trip.setDueDay(10);
        trip.setYellowWarningDays(5);
        trip.setFixedFineAmount(BigDecimal.valueOf(1000));
        trip.setRetroactiveActive(false); // No retroactive tracking!
        // Start date 2 months ago -> 2 quotas overdue
        trip.setFirstDueDate(LocalDate.now().minusMonths(2).withDayOfMonth(10));
        trip = tripRepository.save(trip);

        UserAssignBulkDTO dto = new UserAssignBulkDTO(List.of(userDto.students().get(0).dni()));

        mockMvc.perform(post("/api/v1/trips/{id}/users/bulk", trip.getId())
                .header("Authorization", "Bearer " + adminTokens.accessToken())
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(dto)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.assignedCount").value(1));

        LocalDate today = LocalDate.now();
        int expectedPast = 0;
        for (int i = 0; i < 12; i++) {
            LocalDate baseDate = trip.getFirstDueDate().plusMonths(i);
            int validDay = Math.min(trip.getDueDay(), baseDate.lengthOfMonth());
            if (baseDate.withDayOfMonth(validDay).isBefore(today)) { expectedPast++; }
        }

        List<Installment> installments = installmentRepository.findAll();
        assertEquals(12, installments.size());
        
        if (expectedPast > 0) {
            Installment quota1 = installments.stream().filter(i -> i.getInstallmentNumber() == 1).findFirst().orElseThrow();
            assertEquals(BigDecimal.valueOf(10000).setScale(2), quota1.getCapitalAmount());
            assertEquals(BigDecimal.ZERO.setScale(2), quota1.getRetroactiveAmount());
            assertEquals(BigDecimal.valueOf(1000).setScale(2), quota1.getFineAmount());
            assertEquals(InstallmentStatus.RED, quota1.getStatus());
        }

        if (expectedPast < 12) {
            final int targetFutureNum = expectedPast + 1;
            Installment firstFuture = installments.stream().filter(i -> i.getInstallmentNumber() == targetFutureNum).findFirst().orElseThrow();
            assertEquals(BigDecimal.valueOf(10000).setScale(2), firstFuture.getCapitalAmount());
            assertEquals(BigDecimal.ZERO.setScale(2), firstFuture.getRetroactiveAmount());
            assertEquals(BigDecimal.ZERO.setScale(2), firstFuture.getFineAmount());
            assertEquals(InstallmentStatus.YELLOW, firstFuture.getStatus());
        }
    }

            @Test
            void assignUsersInBulk_siendoUserNormal_devuelve403() throws Exception {
            TokenDTO userTokens = signUp(buildValidUser("user-bulk-forbidden"));
            UserCreateDTO userDto = buildValidUser("candidate-bulk-forbidden");
            signUp(userDto);

            Trip trip = buildTripForBulk(
                "Trip Forbidden",
                BigDecimal.valueOf(1000),
                2,
                10,
                BigDecimal.valueOf(100),
                false,
                LocalDate.now().plusMonths(2)
            );

            UserAssignBulkDTO dto = new UserAssignBulkDTO(List.of(userDto.students().get(0).dni()));

            mockMvc.perform(post("/api/v1/trips/{id}/users/bulk", trip.getId())
                .header("Authorization", "Bearer " + userTokens.accessToken())
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(dto)))
                .andExpect(status().isForbidden());
            }

            @Test
            void assignUsersInBulk_tripNoExiste_devuelve404() throws Exception {
            TokenDTO adminTokens = signUpAdmin(buildValidUser("admin-bulk-trip-not-found"));
            UserCreateDTO userDto = buildValidUser("user-bulk-trip-not-found");
            signUp(userDto);
            UserAssignBulkDTO dto = new UserAssignBulkDTO(List.of(userDto.students().get(0).dni()));

            mockMvc.perform(post("/api/v1/trips/{id}/users/bulk", 999999L)
                .header("Authorization", "Bearer " + adminTokens.accessToken())
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(dto)))
                .andExpect(status().isNotFound());
            }

            @Test
            void assignUsersInBulk_usuarioNoExiste_loCargaComoPendiente() throws Exception {
            TokenDTO adminTokens = signUpAdmin(buildValidUser("admin-bulk-user-not-found"));

            Trip trip = buildTripForBulk(
                "Trip Missing User",
                BigDecimal.valueOf(3000),
                3,
                10,
                BigDecimal.valueOf(100),
                false,
                LocalDate.now().plusMonths(3)
            );

            UserAssignBulkDTO dto = new UserAssignBulkDTO(List.of("99999999"));

            mockMvc.perform(post("/api/v1/trips/{id}/users/bulk", trip.getId())
                .header("Authorization", "Bearer " + adminTokens.accessToken())
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(dto)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.assignedCount").value(0))
                .andExpect(jsonPath("$.pendingCount").value(1));
            }

            @Test
            void assignUsersInBulk_userIdsDuplicados_devuelve400() throws Exception {
            TokenDTO adminTokens = signUpAdmin(buildValidUser("admin-bulk-duplicates"));
            UserCreateDTO userDto = buildValidUser("user-bulk-duplicates");
            signUp(userDto);
            Trip trip = buildTripForBulk(
                "Trip Duplicate IDs",
                BigDecimal.valueOf(2000),
                2,
                10,
                BigDecimal.valueOf(100),
                false,
                LocalDate.now().plusMonths(2)
            );

            UserAssignBulkDTO dto = new UserAssignBulkDTO(List.of(
                    userDto.students().get(0).dni(),
                    userDto.students().get(0).dni()
            ));

            mockMvc.perform(post("/api/v1/trips/{id}/users/bulk", trip.getId())
                .header("Authorization", "Bearer " + adminTokens.accessToken())
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(dto)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors").isArray());
            }

            @Test
    void assignUsersInBulk_reasignacion_devuelve409ConElDniRechazado() throws Exception {
            TokenDTO adminTokens = signUpAdmin(buildValidUser("admin-bulk-idempotent"));
            UserCreateDTO userDto = buildValidUser("user-bulk-idempotent");
            signUp(userDto);
            Trip trip = buildTripForBulk(
                "Trip Idempotent",
                BigDecimal.valueOf(1200),
                3,
                10,
                BigDecimal.valueOf(100),
                false,
                LocalDate.now().plusMonths(2)
            );

            UserAssignBulkDTO dto = new UserAssignBulkDTO(List.of(userDto.students().get(0).dni()));

            mockMvc.perform(post("/api/v1/trips/{id}/users/bulk", trip.getId())
                .header("Authorization", "Bearer " + adminTokens.accessToken())
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(dto)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.assignedCount").value(1));

            mockMvc.perform(post("/api/v1/trips/{id}/users/bulk", trip.getId())
                .header("Authorization", "Bearer " + adminTokens.accessToken())
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(dto)))
                .andExpect(status().isConflict())
                .andExpect(content().string(org.hamcrest.Matchers.containsString(userDto.students().get(0).dni())));

            List<Installment> installments = installmentRepository.findByTripIdWithUsers(trip.getId());
            assertEquals(3, installments.size());

            Trip persistedTrip = tripRepository.findByIdWithUsers(trip.getId()).orElseThrow();
            assertEquals(1, persistedTrip.getAssignedUsers().size());
            }

            @Test
            void getTripStudents_mezclaAsignadosYPendientes() throws Exception {
            TokenDTO adminTokens = signUpAdmin(buildValidUser("admin-trip-students"));
            UserCreateDTO userDto = buildValidUser("user-trip-students");
            signUp(userDto);
            Trip trip = buildTripForBulk(
                "Trip Students",
                BigDecimal.valueOf(1200),
                3,
                10,
                BigDecimal.valueOf(100),
                false,
                LocalDate.now().plusMonths(2)
            );

            String assignedDni = userDto.students().get(0).dni();
            String pendingDni = uniqueDni();

            mockMvc.perform(post("/api/v1/trips/{id}/users/bulk", trip.getId())
                .header("Authorization", "Bearer " + adminTokens.accessToken())
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(new UserAssignBulkDTO(List.of(assignedDni, pendingDni)))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.assignedCount").value(1))
                .andExpect(jsonPath("$.pendingCount").value(1));

            mockMvc.perform(get("/api/v1/trips/{id}/students", trip.getId())
                .header("Authorization", "Bearer " + adminTokens.accessToken()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(2)))
                .andExpect(jsonPath("$[0].studentDni").value(assignedDni))
                .andExpect(jsonPath("$[0].status").value("ASSIGNED"))
                .andExpect(jsonPath("$[0].installmentsCount").value(3))
                .andExpect(jsonPath("$[1].studentDni").value(pendingDni))
                .andExpect(jsonPath("$[1].status").value("PENDING"))
                .andExpect(jsonPath("$[1].installmentsCount").value(0));
            }

            @Test
            void unassignStudent_pendingOnly_eliminaSoloElPendiente() throws Exception {
            TokenDTO adminTokens = signUpAdmin(buildValidUser("admin-trip-unassign-pending"));
            Trip trip = buildTripForBulk(
                "Trip Pending Only",
                BigDecimal.valueOf(1200),
                3,
                10,
                BigDecimal.valueOf(100),
                false,
                LocalDate.now().plusMonths(2)
            );

            String pendingDni = uniqueDni();
            mockMvc.perform(post("/api/v1/trips/{id}/users/bulk", trip.getId())
                .header("Authorization", "Bearer " + adminTokens.accessToken())
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(new UserAssignBulkDTO(List.of(pendingDni)))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.assignedCount").value(0))
                .andExpect(jsonPath("$.pendingCount").value(1));

            mockMvc.perform(delete("/api/v1/trips/{id}/students/{studentDni}", trip.getId(), pendingDni)
                .header("Authorization", "Bearer " + adminTokens.accessToken()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("success"));

            mockMvc.perform(get("/api/v1/trips/{id}/students", trip.getId())
                .header("Authorization", "Bearer " + adminTokens.accessToken()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(0)));
            assertTrue(installmentRepository.findByTripIdWithUsers(trip.getId()).isEmpty());
            }

            @Test
            void unassignStudent_siElPadreTieneOtroHijoEnElViaje_seMantieneAsignado() throws Exception {
            TokenDTO adminTokens = signUpAdmin(buildValidUser("admin-trip-unassign-keep-parent"));
            UserCreateDTO userDto = buildValidUser("user-trip-unassign-keep-parent");
            signUp(userDto);
            User user = userRepository.findByEmail(userDto.email()).orElseThrow();

            var secondStudent = com.agencia.pagos.entities.Student.builder()
                .parent(user)
                .name("Segundo Hijo")
                .dni(uniqueDni())
                .schoolName("Colegio Demo")
                .courseName("5A")
                .build();
            secondStudent = studentRepository.save(secondStudent);

            Trip trip = buildTripForBulk(
                "Trip Keep Parent",
                BigDecimal.valueOf(1800),
                3,
                10,
                BigDecimal.valueOf(100),
                false,
                LocalDate.now().plusMonths(2)
            );

            String firstDni = userDto.students().get(0).dni();
            String secondDni = secondStudent.getDni();

            mockMvc.perform(post("/api/v1/trips/{id}/users/bulk", trip.getId())
                .header("Authorization", "Bearer " + adminTokens.accessToken())
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(new UserAssignBulkDTO(List.of(firstDni, secondDni)))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.assignedCount").value(2));

            mockMvc.perform(delete("/api/v1/trips/{id}/students/{studentDni}", trip.getId(), firstDni)
                .header("Authorization", "Bearer " + adminTokens.accessToken()))
                .andExpect(status().isOk());

            Trip persistedTrip = tripRepository.findByIdWithUsers(trip.getId()).orElseThrow();
            assertEquals(1, persistedTrip.getAssignedUsers().size());
            assertEquals(user.getId(), persistedTrip.getAssignedUsers().get(0).getId());
            assertEquals(3, installmentRepository.findByTripIdAndStudentDni(trip.getId(), secondDni).size());
            assertTrue(installmentRepository.findByTripIdAndStudentDni(trip.getId(), firstDni).isEmpty());
            }

            @Test
            void assignUsersInBulk_totalNoDivisible_ultimaCuotaAbsorbeResto() throws Exception {
            TokenDTO adminTokens = signUpAdmin(buildValidUser("admin-bulk-remainder"));
            UserCreateDTO userDto = buildValidUser("user-bulk-remainder");
            signUp(userDto);
            Trip trip = buildTripForBulk(
                "Trip Remainder",
                new BigDecimal("100.00"),
                3,
                10,
                BigDecimal.ZERO,
                false,
                LocalDate.now().plusMonths(2)
            );

            UserAssignBulkDTO dto = new UserAssignBulkDTO(List.of(userDto.students().get(0).dni()));

            mockMvc.perform(post("/api/v1/trips/{id}/users/bulk", trip.getId())
                .header("Authorization", "Bearer " + adminTokens.accessToken())
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(dto)))
                .andExpect(status().isOk());

            List<Installment> userInstallments = installmentRepository.findByTripIdWithUsers(trip.getId()).stream()
                .filter(i -> i.getUser().getEmail().equals(userDto.email()))
                .sorted(Comparator.comparing(Installment::getInstallmentNumber))
                .toList();

            assertEquals(3, userInstallments.size());
            assertEquals(new BigDecimal("33.33"), userInstallments.get(0).getCapitalAmount());
            assertEquals(new BigDecimal("33.33"), userInstallments.get(1).getCapitalAmount());
            assertEquals(new BigDecimal("33.34"), userInstallments.get(2).getCapitalAmount());

            BigDecimal total = userInstallments.stream()
                .map(Installment::getCapitalAmount)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
            assertEquals(0, total.compareTo(new BigDecimal("100.00")));
            }

            @Test
            void assignUsersInBulk_dueDay31_ajustaFechaAlUltimoDiaDelMes() throws Exception {
            TokenDTO adminTokens = signUpAdmin(buildValidUser("admin-bulk-due-day"));
            UserCreateDTO userDto = buildValidUser("user-bulk-due-day");
            signUp(userDto);
            Trip trip = buildTripForBulk(
                "Trip Due Day 31",
                BigDecimal.valueOf(3100),
                3,
                31,
                BigDecimal.ZERO,
                false,
                LocalDate.of(2027, 1, 1)
            );

            UserAssignBulkDTO dto = new UserAssignBulkDTO(List.of(userDto.students().get(0).dni()));

            mockMvc.perform(post("/api/v1/trips/{id}/users/bulk", trip.getId())
                .header("Authorization", "Bearer " + adminTokens.accessToken())
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(dto)))
                .andExpect(status().isOk());

            Installment secondInstallment = installmentRepository.findByTripIdWithUsers(trip.getId()).stream()
                .filter(i -> i.getUser().getEmail().equals(userDto.email()))
                .filter(i -> i.getInstallmentNumber() == 2)
                .findFirst()
                .orElseThrow();

            assertEquals(LocalDate.of(2027, 2, 28), secondInstallment.getDueDate());
            }

            @Test
            void deleteTrip_conUsuariosAsignados_eliminaViajeCuotasYDependencias() throws Exception {
            TokenDTO adminTokens = signUpAdmin(buildValidUser("admin-trip-delete-conflict"));
            UserCreateDTO userDto = buildValidUser("user-trip-delete-conflict");
            signUp(userDto);
            User u = userRepository.findByEmail(userDto.email()).orElseThrow();

            Trip trip = buildTripForBulk(
                "Trip Delete Conflict",
                BigDecimal.valueOf(1000),
                2,
                10,
                BigDecimal.valueOf(100),
                false,
                LocalDate.now().plusMonths(2)
            );
            trip.getAssignedUsers().add(u);
            trip = tripRepository.save(trip);

            Installment installment = new Installment();
            installment.setTrip(trip);
            installment.setUser(u);
            installment.setInstallmentNumber(1);
            installment.setDueDate(LocalDate.now().plusDays(10));
            installment.setCapitalAmount(BigDecimal.valueOf(500));
            installment.setRetroactiveAmount(BigDecimal.ZERO);
            installment.setFineAmount(BigDecimal.ZERO);
            installment.setStatus(InstallmentStatus.YELLOW);
            installment.recalculateTotalDue();
            installment = installmentRepository.save(installment);

            paymentReceiptRepository.save(PaymentReceipt.builder()
                .installment(installment)
                .reportedAmount(BigDecimal.valueOf(200))
                .reportedPaymentDate(LocalDate.now())
                .paymentMethod(PaymentMethod.BANK_TRANSFER)
                .fileKey("test-file")
                .build());

            installmentReminderNotificationRepository.save(InstallmentReminderNotification.builder()
                .installment(installment)
                .type(InstallmentReminderNotificationType.DUE_SOON)
                .sentOn(LocalDate.now())
                .build());

            mockMvc.perform(delete("/api/v1/trips/{id}", trip.getId())
                .header("Authorization", "Bearer " + adminTokens.accessToken()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("success"));

            assertTrue(tripRepository.findById(trip.getId()).isEmpty());
            assertTrue(installmentRepository.findByTripIdWithUsers(trip.getId()).isEmpty());
            assertEquals(0, paymentReceiptRepository.count());
            assertEquals(0, installmentReminderNotificationRepository.count());
            }

            @Test
            void deleteTrip_conPendientes_eliminaPendientesTambien() throws Exception {
            TokenDTO adminTokens = signUpAdmin(buildValidUser("admin-trip-delete-pending"));
            Trip trip = buildTripForBulk(
                "Trip Delete Pending",
                BigDecimal.valueOf(1000),
                2,
                10,
                BigDecimal.valueOf(100),
                false,
                LocalDate.now().plusMonths(2)
            );

            String pendingDni = uniqueDni();
            mockMvc.perform(post("/api/v1/trips/{id}/users/bulk", trip.getId())
                .header("Authorization", "Bearer " + adminTokens.accessToken())
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(new UserAssignBulkDTO(List.of(pendingDni)))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.pendingCount").value(1));

            mockMvc.perform(delete("/api/v1/trips/{id}", trip.getId())
                .header("Authorization", "Bearer " + adminTokens.accessToken()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("success"));

            assertTrue(tripRepository.findById(trip.getId()).isEmpty());
            assertTrue(pendingTripStudentRepository.findByTripIdAndStudentDni(trip.getId(), pendingDni).isEmpty());
            }

            @Test
            void updateTrip_cambiarFirstDueDate_conUsuariosAsignados_devuelve409() throws Exception {
            TokenDTO adminTokens = signUpAdmin(buildValidUser("admin-trip-update-conflict"));
            UserCreateDTO userDto = buildValidUser("user-trip-update-conflict");
            signUp(userDto);
            User u = userRepository.findByEmail(userDto.email()).orElseThrow();

            LocalDate originalFirstDueDate = LocalDate.now().plusMonths(2);
            Trip trip = buildTripForBulk(
                "Trip Update Conflict",
                BigDecimal.valueOf(1000),
                2,
                10,
                BigDecimal.valueOf(100),
                false,
                originalFirstDueDate
            );
            trip.getAssignedUsers().add(u);
            tripRepository.save(trip);

            TripUpdateDTO patchDto = new TripUpdateDTO(
                null,
                null,
                null,
                null,
                null,
                LocalDate.now().plusMonths(6)
            );

            mockMvc.perform(patch("/api/v1/trips/{id}", trip.getId())
                .header("Authorization", "Bearer " + adminTokens.accessToken())
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(patchDto)))
                .andExpect(status().isConflict());

            Trip persistedTrip = tripRepository.findByIdWithUsers(trip.getId()).orElseThrow();
            assertEquals(originalFirstDueDate, persistedTrip.getFirstDueDate());
            }

            @Test
            void assignUsersInBulk_dosUsuarios_generaCuotasParaCadaUno() throws Exception {
            TokenDTO adminTokens = signUpAdmin(buildValidUser("admin-bulk-two-users"));

            UserCreateDTO user1Dto = buildValidUser("user-bulk-two-1");
            UserCreateDTO user2Dto = buildValidUser("user-bulk-two-2");
            signUp(user1Dto);
            signUp(user2Dto);
            Trip trip = buildTripForBulk(
                "Trip Two Users",
                BigDecimal.valueOf(8000),
                4,
                10,
                BigDecimal.valueOf(100),
                false,
                LocalDate.now().plusMonths(2)
            );

            UserAssignBulkDTO dto = new UserAssignBulkDTO(List.of(
                    user1Dto.students().get(0).dni(),
                    user2Dto.students().get(0).dni()
            ));

            mockMvc.perform(post("/api/v1/trips/{id}/users/bulk", trip.getId())
                .header("Authorization", "Bearer " + adminTokens.accessToken())
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(dto)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.assignedCount").value(2));

            List<Installment> installments = installmentRepository.findByTripIdWithUsers(trip.getId());
            assertNotNull(installments);
            assertEquals(8, installments.size());

            long user1Installments = installments.stream().filter(i -> i.getUser().getEmail().equals(user1Dto.email())).count();
            long user2Installments = installments.stream().filter(i -> i.getUser().getEmail().equals(user2Dto.email())).count();
            assertEquals(4, user1Installments);
            assertEquals(4, user2Installments);
            assertTrue(installments.stream().allMatch(i -> i.getStatus() == InstallmentStatus.YELLOW));
            }

            @Test
    void getSpreadsheet_cuotasFuturas_devuelveStatusYellowEnLaRespuesta() throws Exception {
        TokenDTO adminTokens = signUpAdmin(buildValidUser("admin-spreadsheet-green"));

            UserCreateDTO userDto = buildValidUser("user-spreadsheet-green");
            signUp(userDto);
            Trip trip = buildTripForBulk(
                "Trip Spreadsheet Green",
                BigDecimal.valueOf(9000),
                3,
                10,
                BigDecimal.valueOf(100),
                false,
                LocalDate.now().plusMonths(3)
            );

            UserAssignBulkDTO dto = new UserAssignBulkDTO(List.of(userDto.students().get(0).dni()));

            mockMvc.perform(post("/api/v1/trips/{id}/users/bulk", trip.getId())
                    .header("Authorization", "Bearer " + adminTokens.accessToken())
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(objectMapper.writeValueAsString(dto)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.assignedCount").value(1));

            mockMvc.perform(get("/api/v1/trips/{id}/spreadsheet", trip.getId())
                    .header("Authorization", "Bearer " + adminTokens.accessToken()))
                .andExpect(status().isOk())
            .andExpect(jsonPath("$.rows[0].installments[0].status").value("YELLOW"))
            .andExpect(jsonPath("$.rows[0].installments[0].uiStatusCode").value("UP_TO_DATE"))
            .andExpect(jsonPath("$.rows[0].installments[0].uiStatusLabel").value("Al día"))
            .andExpect(jsonPath("$.rows[0].installments[0].uiStatusTone").value("green"))
            .andExpect(jsonPath("$.rows[0].installments[1].status").value("YELLOW"))
            .andExpect(jsonPath("$.rows[0].installments[2].status").value("YELLOW"));    }

    @Test            void getSpreadsheet_cuotaVencidaSinRetroactivo_devuelveStatusRedEnLaRespuesta() throws Exception {
            TokenDTO adminTokens = signUpAdmin(buildValidUser("admin-spreadsheet-red"));

            UserCreateDTO userDto = buildValidUser("user-spreadsheet-red");
            signUp(userDto);
            Trip trip = buildTripForBulk(
                "Trip Spreadsheet Red",
                BigDecimal.valueOf(4000),
                2,
                10,
                BigDecimal.valueOf(100),
                false,
                LocalDate.now().minusMonths(2)
            );

            UserAssignBulkDTO dto = new UserAssignBulkDTO(List.of(userDto.students().get(0).dni()));

            mockMvc.perform(post("/api/v1/trips/{id}/users/bulk", trip.getId())
                    .header("Authorization", "Bearer " + adminTokens.accessToken())
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(objectMapper.writeValueAsString(dto)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.assignedCount").value(1));

            mockMvc.perform(get("/api/v1/trips/{id}/spreadsheet", trip.getId())
                    .header("Authorization", "Bearer " + adminTokens.accessToken()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.rows[0].installments[0].status").value("RED"))
                .andExpect(jsonPath("$.rows[0].installments[0].uiStatusCode").value("OVERDUE"))
                .andExpect(jsonPath("$.rows[0].installments[0].uiStatusLabel").value("Vencida"))
                .andExpect(jsonPath("$.rows[0].installments[0].uiStatusTone").value("red"));
            }

            @Test
            void getSpreadsheet_conComprobantePendiente_devuelveUiStatusUnderReview() throws Exception {
            TokenDTO adminTokens = signUpAdmin(buildValidUser("admin-spreadsheet-under-review"));

            UserCreateDTO userDto = buildValidUser("user-spreadsheet-under-review");
            signUp(userDto);
            Trip trip = buildTripForBulk(
                "Trip Spreadsheet Under Review",
                BigDecimal.valueOf(4000),
                1,
                10,
                BigDecimal.valueOf(100),
                false,
                LocalDate.now().plusDays(2)
            );

            UserAssignBulkDTO dto = new UserAssignBulkDTO(List.of(userDto.students().get(0).dni()));

            mockMvc.perform(post("/api/v1/trips/{id}/users/bulk", trip.getId())
                    .header("Authorization", "Bearer " + adminTokens.accessToken())
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(objectMapper.writeValueAsString(dto)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.assignedCount").value(1));

            Installment installment = installmentRepository.findByTripIdWithUsers(trip.getId()).stream()
                    .findFirst()
                    .orElseThrow();

            paymentReceiptRepository.save(PaymentReceipt.builder()
                    .installment(installment)
                    .reportedAmount(BigDecimal.valueOf(4000))
                    .reportedPaymentDate(LocalDate.now())
                    .paymentMethod(PaymentMethod.BANK_TRANSFER)
                    .status(com.agencia.pagos.entities.ReceiptStatus.PENDING)
                    .fileKey("")
                    .build());

            mockMvc.perform(get("/api/v1/trips/{id}/spreadsheet", trip.getId())
                    .header("Authorization", "Bearer " + adminTokens.accessToken()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.rows[0].installments[0].uiStatusCode").value("UNDER_REVIEW"))
                .andExpect(jsonPath("$.rows[0].installments[0].uiStatusLabel").value("En revisión"))
                .andExpect(jsonPath("$.rows[0].installments[0].uiStatusTone").value("yellow"));
            }

            @Test
            void getSpreadsheet_filtroPorStatusRed_devuelveSoloFilasConEseStatus() throws Exception {
            TokenDTO adminTokens = signUpAdmin(buildValidUser("admin-spreadsheet-filter-red"));

            UserCreateDTO userDto = buildValidUser("user-spreadsheet-filter-red");
            signUp(userDto);
            Trip trip = buildTripForBulk(
                "Trip Spreadsheet Filter Red",
                BigDecimal.valueOf(6000),
                3,
                10,
                BigDecimal.valueOf(100),
                false,
                LocalDate.now().minusMonths(2)
            );

            UserAssignBulkDTO dto = new UserAssignBulkDTO(List.of(userDto.students().get(0).dni()));

            mockMvc.perform(post("/api/v1/trips/{id}/users/bulk", trip.getId())
                    .header("Authorization", "Bearer " + adminTokens.accessToken())
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(objectMapper.writeValueAsString(dto)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.assignedCount").value(1));

            mockMvc.perform(get("/api/v1/trips/{id}/spreadsheet", trip.getId())
                    .param("status", "RED")
                    .header("Authorization", "Bearer " + adminTokens.accessToken()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.rows", hasSize(greaterThan(0))))
                .andExpect(jsonPath("$.rows[0].installments[?(@.status == 'RED')]", hasSize(greaterThan(0))));
            }

    @Test
    void exportSpreadsheet_siendoAdmin_devuelve200ConContentDisposition() throws Exception {
        TokenDTO adminTokens = signUpAdmin(buildValidUser("admin-spreadsheet-export"));

        UserCreateDTO user1Dto = buildValidUser("user-export-1");
        UserCreateDTO user2Dto = buildValidUser("user-export-2");
        signUp(user1Dto);
        signUp(user2Dto);
        Trip trip = buildTripForBulk(
                "Trip Export",
                BigDecimal.valueOf(9000),
                3,
                10,
                BigDecimal.valueOf(100),
                false,
                LocalDate.now().plusMonths(1)
        );

        UserAssignBulkDTO assignDto = new UserAssignBulkDTO(List.of(
                user1Dto.students().get(0).dni(),
                user2Dto.students().get(0).dni()
        ));
        mockMvc.perform(post("/api/v1/trips/{id}/users/bulk", trip.getId())
                        .header("Authorization", "Bearer " + adminTokens.accessToken())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(assignDto)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.assignedCount").value(2));

        byte[] responseBody = mockMvc.perform(get("/api/v1/trips/{id}/spreadsheet/export", trip.getId())
                        .header("Authorization", "Bearer " + adminTokens.accessToken()))
                .andExpect(status().isOk())
                .andExpect(header().string("Content-Type", containsString("spreadsheetml")))
                .andExpect(header().string("Content-Disposition", containsString("attachment")))
                .andReturn()
                .getResponse()
                .getContentAsByteArray();

        assertNotNull(responseBody);
        assertTrue(responseBody.length > 1);
        assertEquals((byte) 0x50, responseBody[0]);
        assertEquals((byte) 0x4B, responseBody[1]);
    }

    @Test
    void exportSpreadsheet_tripNoExiste_devuelve404() throws Exception {
        TokenDTO adminTokens = signUpAdmin(buildValidUser("admin-spreadsheet-export-not-found"));

        mockMvc.perform(get("/api/v1/trips/{id}/spreadsheet/export", 99999L)
                        .header("Authorization", "Bearer " + adminTokens.accessToken()))
                .andExpect(status().isNotFound());
    }

    @Test
    void exportSpreadsheet_sinAutenticacion_devuelve401() throws Exception {
        mockMvc.perform(get("/api/v1/trips/{id}/spreadsheet/export", 1L))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void exportSpreadsheet_siendoUserNormal_devuelve403() throws Exception {
        TokenDTO userTokens = signUp(buildValidUser("user-spreadsheet-export-forbidden"));

        mockMvc.perform(get("/api/v1/trips/{id}/spreadsheet/export", 1L)
                        .header("Authorization", "Bearer " + userTokens.accessToken()))
                .andExpect(status().isForbidden());
    }
}
