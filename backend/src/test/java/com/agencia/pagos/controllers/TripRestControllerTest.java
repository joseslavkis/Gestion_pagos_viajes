package com.agencia.pagos.controllers;

import com.agencia.pagos.TestcontainersConfiguration;
import com.agencia.pagos.dtos.request.TripCreateDTO;
import com.agencia.pagos.dtos.request.TripUpdateDTO;
import com.agencia.pagos.dtos.request.UserAssignBulkDTO;
import com.agencia.pagos.dtos.request.UserCreateDTO;
import com.agencia.pagos.dtos.response.TokenDTO;
import com.agencia.pagos.entities.Installment;
import com.agencia.pagos.entities.InstallmentStatus;
import com.agencia.pagos.entities.Trip;
import com.agencia.pagos.entities.user.User;
import com.agencia.pagos.repositories.InstallmentRepository;
import com.agencia.pagos.repositories.TripRepository;
import com.agencia.pagos.repositories.UserRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import static org.hamcrest.Matchers.hasSize;
import static org.junit.jupiter.api.Assertions.assertEquals;

@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
class TripRestControllerTest extends ControllerIntegrationTestSupport {

    @Autowired
    private TripRepository tripRepository;

    @Autowired
    private InstallmentRepository installmentRepository;

    @Autowired
    private UserRepository userRepository;

    @AfterEach
    void cleanUpTrips() {
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
        User u = userRepository.findByEmail(userDto.email()).orElseThrow();

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

        UserAssignBulkDTO dto = new UserAssignBulkDTO(List.of(u.getId()));

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
        assertEquals(expectedFuture, installments.size());
        
        if (expectedFuture > 0) {
            final int targetInstallmentNum = expectedPast + 1;
            Installment firstSaved = installments.stream().filter(i -> i.getInstallmentNumber() == targetInstallmentNum).findFirst().orElseThrow();
            assertEquals(BigDecimal.valueOf(10000).setScale(2), firstSaved.getCapitalAmount());
            assertEquals(BigDecimal.valueOf(10000 * expectedPast).setScale(2), firstSaved.getRetroactiveAmount());
            assertEquals(InstallmentStatus.YELLOW, firstSaved.getStatus());
        }
    }

    @Test
    void assignUsersInBulk_retroactiveInactive_calculatesCorrectly() throws Exception {
        TokenDTO adminTokens = signUpAdmin(buildValidUser("admin-bulk-inactive"));
        UserCreateDTO userDto = buildValidUser("user-bulk-inactive");
        signUp(userDto);
        User u = userRepository.findByEmail(userDto.email()).orElseThrow();

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

        UserAssignBulkDTO dto = new UserAssignBulkDTO(List.of(u.getId()));

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
}
