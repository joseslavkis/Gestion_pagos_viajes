package com.agencia.pagos.controllers;

import com.agencia.pagos.TestcontainersConfiguration;
import com.agencia.pagos.dtos.request.AdminCreateDTO;
import com.agencia.pagos.dtos.request.StudentCreateDTO;
import com.agencia.pagos.dtos.request.UserCreateDTO;
import com.agencia.pagos.dtos.request.UserUpdateDTO;
import com.agencia.pagos.dtos.response.TokenDTO;
import com.agencia.pagos.entities.BankAccount;
import com.agencia.pagos.entities.Currency;
import com.agencia.pagos.entities.Installment;
import com.agencia.pagos.entities.InstallmentStatus;
import com.agencia.pagos.entities.PaymentMethod;
import com.agencia.pagos.entities.PaymentReceipt;
import com.agencia.pagos.entities.ReceiptStatus;
import com.agencia.pagos.entities.Trip;
import com.agencia.pagos.entities.user.User;
import com.agencia.pagos.repositories.BankAccountRepository;
import com.agencia.pagos.repositories.InstallmentRepository;
import com.agencia.pagos.repositories.PaymentReceiptRepository;
import com.agencia.pagos.repositories.StudentRepository;
import com.agencia.pagos.repositories.TripRepository;
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

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.hasSize;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
class UserRestControllerTest extends ControllerIntegrationTestSupport {
    @Autowired
    private TripRepository tripRepository;

    @Autowired
    private InstallmentRepository installmentRepository;

    @Autowired
    private PaymentReceiptRepository paymentReceiptRepository;

    @Autowired
    private BankAccountRepository bankAccountRepository;

    @Autowired
    private StudentRepository studentRepository;

    @AfterEach
    void cleanDomainData() {
        paymentReceiptRepository.deleteAll();
        installmentRepository.deleteAll();
        tripRepository.deleteAll();
        bankAccountRepository.deleteAll();
    }

    // ── GET /profile/{id} ───────────────────────────────────────────────────

    @Test
    void verPerfil_propioPerfil_devuelve200() throws Exception {
        UserCreateDTO dto = buildValidUser("ver-perfil-propio");
        TokenDTO tokens = signUp(dto);
        Long myId = extractId(tokens.accessToken());

        mockMvc.perform(get("/api/v1/users/profile/{id}", myId)
                .header("Authorization", "Bearer " + tokens.accessToken()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.email").value(dto.email()))
                .andExpect(jsonPath("$.id").value(myId));
    }

    @Test
    void verPerfil_perfilAjeno_devuelve403() throws Exception {
        UserCreateDTO dto1 = buildValidUser("idor-user1");
        UserCreateDTO dto2 = buildValidUser("idor-user2");

        TokenDTO tokens1 = signUp(dto1);
        TokenDTO tokens2 = signUp(dto2);
        Long idUser2 = extractId(tokens2.accessToken());

        // user1 intenta ver el perfil de user2 → 403
        mockMvc.perform(get("/api/v1/users/profile/{id}", idUser2)
                .header("Authorization", "Bearer " + tokens1.accessToken()))
                .andExpect(status().isForbidden());
    }

    @Test
    void verPerfil_sinAutenticacion_devuelve401() throws Exception {
        mockMvc.perform(get("/api/v1/users/profile/1"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void verPerfil_adminPuedeVerCualquierPerfil_devuelve200() throws Exception {
        // Crear un user normal
        UserCreateDTO userDto = buildValidUser("admin-ve-user");
        TokenDTO userTokens = signUp(userDto);
        Long userId = extractId(userTokens.accessToken());

        // Crear un admin
        UserCreateDTO adminDto = buildValidUser("admin-ver");
        TokenDTO adminTokens = signUpAdmin(adminDto);

        // Admin ve el perfil del user → 200
        mockMvc.perform(get("/api/v1/users/profile/{id}", userId)
                .header("Authorization", "Bearer " + adminTokens.accessToken()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(userId));
    }

    // ── PATCH /update/me ────────────────────────────────────────────────────

    @Test
    void updateMe_conDatosValidos_devuelve200() throws Exception {
        TokenDTO tokens = signUp(buildValidUser("update-me"));

        UserUpdateDTO updateDto = new UserUpdateDTO("NuevoNombre", "NuevoApellido", null);

        mockMvc.perform(patch("/api/v1/users/update/me")
                .header("Authorization", "Bearer " + tokens.accessToken())
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(updateDto)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("success"));
    }

    @Test
    void updateMe_conPasswordCorta_devuelve400() throws Exception {
        TokenDTO tokens = signUp(buildValidUser("update-me-pass-corta"));

        UserUpdateDTO updateDto = new UserUpdateDTO(null, null, "corta");

        mockMvc.perform(patch("/api/v1/users/update/me")
                .header("Authorization", "Bearer " + tokens.accessToken())
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(updateDto)))
                .andExpect(status().isBadRequest());
    }

    @Test
    void updateMe_sinAutenticacion_devuelve401() throws Exception {
        mockMvc.perform(patch("/api/v1/users/update/me")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(new UserUpdateDTO("Nombre", null, null))))
                .andExpect(status().isUnauthorized());
    }

    // ── DELETE /delete/me ───────────────────────────────────────────────────

    @Test
    void deleteMe_devuelve200YDesactivaUsuario() throws Exception {
        TokenDTO tokens = signUp(buildValidUser("delete-me"));

        mockMvc.perform(delete("/api/v1/users/delete/me")
                .header("Authorization", "Bearer " + tokens.accessToken()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("success"));
    }

    @Test
    void deleteMe_sinAutenticacion_devuelve401() throws Exception {
        mockMvc.perform(delete("/api/v1/users/delete/me"))
                .andExpect(status().isUnauthorized());
    }

    // ── POST /students ──────────────────────────────────────────────────────

    @Test
    void addStudent_conDniPendiente_creaAlumnoYCuotasDeTodosLosViajesPendientes() throws Exception {
        UserCreateDTO parentDto = buildValidUser("add-student-parent");
        TokenDTO parentTokens = signUp(parentDto);

        StudentCreateDTO newStudentDto = new StudentCreateDTO("Luca Perez", uniqueDni());
        var firstTrip = seedPendingTrip("add-student-pending-1", List.of(newStudentDto.dni()));
        var secondTrip = seedPendingTrip("add-student-pending-2", List.of(newStudentDto.dni()));

        mockMvc.perform(post("/api/v1/users/students")
                .header("Authorization", "Bearer " + parentTokens.accessToken())
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(newStudentDto)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.name").value("Luca Perez"))
                .andExpect(jsonPath("$.dni").value(newStudentDto.dni()));

        User parent = userRepository.findByEmail(parentDto.email()).orElseThrow();
        List<Installment> newStudentInstallments = installmentRepository.findByUserIdWithTrip(parent.getId()).stream()
                .filter(installment -> installment.getStudent() != null && newStudentDto.dni().equals(installment.getStudent().getDni()))
                .toList();

        assertEquals(6, newStudentInstallments.size());
        assertTrue(newStudentInstallments.stream().anyMatch(installment -> installment.getTrip().getId().equals(firstTrip.getId())));
        assertTrue(newStudentInstallments.stream().anyMatch(installment -> installment.getTrip().getId().equals(secondTrip.getId())));
        assertTrue(pendingTripStudentRepository.findByStudentDniWithTrip(newStudentDto.dni()).isEmpty());
    }

        @Test
        void addStudent_conDniConPuntosGuionesYEspacios_loNormalizaAntesDeReclamar() throws Exception {
                UserCreateDTO parentDto = buildValidUser("add-student-normalized-parent");
                TokenDTO parentTokens = signUp(parentDto);

                String canonicalDni = uniqueDni();
                seedPendingTrip("add-student-normalized-pending", List.of(canonicalDni));

                String formattedDni = canonicalDni.substring(0, 2) + "." + canonicalDni.substring(2, 5) + "-" + canonicalDni.substring(5);
                StudentCreateDTO newStudentDto = new StudentCreateDTO("Luca Perez", formattedDni);

                mockMvc.perform(post("/api/v1/users/students")
                                .header("Authorization", "Bearer " + parentTokens.accessToken())
                                .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(newStudentDto)))
                                .andExpect(status().isCreated())
                                .andExpect(jsonPath("$.name").value("Luca Perez"))
                                .andExpect(jsonPath("$.dni").value(canonicalDni));

                User parent = userRepository.findByEmail(parentDto.email()).orElseThrow();
                List<Installment> newStudentInstallments = installmentRepository.findByUserIdWithTrip(parent.getId()).stream()
                                .filter(installment -> installment.getStudent() != null && canonicalDni.equals(installment.getStudent().getDni()))
                                .toList();

                assertEquals(3, newStudentInstallments.size());
                assertTrue(pendingTripStudentRepository.findByStudentDniWithTrip(canonicalDni).isEmpty());
        }

    @Test
    void addStudent_conDniNoPrecargado_devuelve409ConMensajeDelBackend() throws Exception {
        TokenDTO parentTokens = signUp(buildValidUser("add-student-no-pending"));
        StudentCreateDTO newStudentDto = new StudentCreateDTO("Luca Perez", uniqueDni());

        mockMvc.perform(post("/api/v1/users/students")
                .header("Authorization", "Bearer " + parentTokens.accessToken())
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(newStudentDto)))
                .andExpect(status().isConflict())
                .andExpect(content().string(containsString(newStudentDto.dni())))
                .andExpect(content().string(containsString("no está habilitado todavía")));
    }

    @Test
    void addStudent_conDniYaReclamadoPorOtroUsuario_devuelve409() throws Exception {
        UserCreateDTO firstParentDto = buildValidUser("add-student-already-claimed-a");
        signUp(firstParentDto);
        TokenDTO secondParentTokens = signUp(buildValidUser("add-student-already-claimed-b"));

        String claimedDni = firstParentDto.students().get(0).dni();
        StudentCreateDTO duplicateStudentDto = new StudentCreateDTO("Alumno Duplicado", claimedDni);

        mockMvc.perform(post("/api/v1/users/students")
                .header("Authorization", "Bearer " + secondParentTokens.accessToken())
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(duplicateStudentDto)))
                .andExpect(status().isConflict())
                .andExpect(content().string("El DNI de alumno " + claimedDni + " ya fue reclamado por otro usuario."));
    }

    // ── POST /admin/create ──────────────────────────────────────────────────

    @Test
    void adminCreate_siendoAdmin_devuelve201() throws Exception {
        TokenDTO adminTokens = signUpAdmin(buildValidUser("admin-creator"));
        AdminCreateDTO nuevoAdmin = buildValidAdmin("nuevo-admin");

        mockMvc.perform(post("/api/v1/users/admin/create")
                .header("Authorization", "Bearer " + adminTokens.accessToken())
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(nuevoAdmin)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.accessToken").exists());
    }

    @Test
    void adminCreate_siendoUserNormal_devuelve403() throws Exception {
        TokenDTO userTokens = signUp(buildValidUser("user-intenta-crear-admin"));
        AdminCreateDTO nuevoAdmin = buildValidAdmin("admin-bloqueado");

        mockMvc.perform(post("/api/v1/users/admin/create")
                .header("Authorization", "Bearer " + userTokens.accessToken())
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(nuevoAdmin)))
                .andExpect(status().isForbidden());
    }

    @Test
    void adminCreate_sinAutenticacion_devuelve401() throws Exception {
        mockMvc.perform(post("/api/v1/users/admin/create")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(buildValidAdmin("sin-auth"))))
                .andExpect(status().isUnauthorized());
    }

    // ── PATCH /admin/update/{id} ────────────────────────────────────────────

    @Test
    void adminUpdate_siendoAdmin_devuelve200() throws Exception {
        TokenDTO adminTokens = signUpAdmin(buildValidUser("admin-updater"));
        Long adminId = extractId(adminTokens.accessToken());

        UserUpdateDTO updateDto = new UserUpdateDTO("AdminActualizado", null, null);

        mockMvc.perform(patch("/api/v1/users/admin/update/{id}", adminId)
                .header("Authorization", "Bearer " + adminTokens.accessToken())
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(updateDto)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("success"));
    }

    @Test
    void adminUpdate_siendoUserNormal_devuelve403() throws Exception {
        TokenDTO userTokens = signUp(buildValidUser("user-intenta-update-admin"));
        Long userId = extractId(userTokens.accessToken());

        mockMvc.perform(patch("/api/v1/users/admin/update/{id}", userId)
                .header("Authorization", "Bearer " + userTokens.accessToken())
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(new UserUpdateDTO("Nombre", null, null))))
                .andExpect(status().isForbidden());
    }

    // ── DELETE /admin/delete/{id} ───────────────────────────────────────────

    @Test
    void adminDelete_siendoAdmin_devuelve200() throws Exception {
        TokenDTO adminTokens = signUpAdmin(buildValidUser("admin-deleter"));
        UserCreateDTO targetDto = buildValidUser("user-a-eliminar");
        TokenDTO targetTokens = signUp(targetDto);
        Long targetId = extractId(targetTokens.accessToken());

        mockMvc.perform(delete("/api/v1/users/admin/delete/{id}", targetId)
                .header("Authorization", "Bearer " + adminTokens.accessToken()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("success"));
    }

    @Test
    void adminDelete_siendoUserNormal_devuelve403() throws Exception {
        TokenDTO userTokens = signUp(buildValidUser("user-intenta-delete"));
        Long userId = extractId(userTokens.accessToken());

        mockMvc.perform(delete("/api/v1/users/admin/delete/{id}", userId)
                .header("Authorization", "Bearer " + userTokens.accessToken()))
                .andExpect(status().isForbidden());
    }

    @Test
    void adminDelete_sinAutenticacion_devuelve401() throws Exception {
        mockMvc.perform(delete("/api/v1/users/admin/delete/1"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void adminSearch_porNombreDevuelveResultados() throws Exception {
        TokenDTO adminTokens = signUpAdmin(buildValidUser("admin-busca-nombre"));
        signUp(buildUser("mariana-search", "Mariana", "Lopez"));
        signUp(buildUser("pedro-search", "Pedro", "Suarez"));

        mockMvc.perform(get("/api/v1/users/admin/search")
                .param("q", "maria")
                .header("Authorization", "Bearer " + adminTokens.accessToken()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].name").value("Mariana"))
                .andExpect(jsonPath("$[0].lastname").value("Lopez"))
                .andExpect(jsonPath("$[0].studentsCount").value(1));
    }

    @Test
    void adminSearch_porEmailYdniDevuelveResultados() throws Exception {
        TokenDTO adminTokens = signUpAdmin(buildValidUser("admin-busca-identidad"));
        UserCreateDTO userDto = buildUser("lucia-search", "Lucia", "Mendez");
        TokenDTO userTokens = signUp(userDto);
        Long userId = extractId(userTokens.accessToken());
        String userDni = userRepository.findById(userId).orElseThrow().getDni();

        mockMvc.perform(get("/api/v1/users/admin/search")
                .param("q", "lucia-search")
                .header("Authorization", "Bearer " + adminTokens.accessToken()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].email").value(userDto.email()));

        mockMvc.perform(get("/api/v1/users/admin/search")
                .param("q", userDni.substring(0, 5))
                .header("Authorization", "Bearer " + adminTokens.accessToken()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].dni").value(userDni));
    }

    @Test
    void adminSearch_siendoUsuarioNormalDevuelve403() throws Exception {
        TokenDTO userTokens = signUp(buildValidUser("user-no-busca"));

        mockMvc.perform(get("/api/v1/users/admin/search")
                .param("q", "test")
                .header("Authorization", "Bearer " + userTokens.accessToken()))
                .andExpect(status().isForbidden());
    }

    @Test
    void adminSearch_conMenosDeDosCaracteresDevuelveListaVacia() throws Exception {
        TokenDTO adminTokens = signUpAdmin(buildValidUser("admin-busca-corto"));
        signUp(buildUser("usuario-corto", "Ana", "Lopez"));

        mockMvc.perform(get("/api/v1/users/admin/search")
                .param("q", "a")
                .header("Authorization", "Bearer " + adminTokens.accessToken()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(0)));
    }

    @Test
    void adminSearch_porApellidoEsCaseInsensitiveYExcluyeAdmins() throws Exception {
        TokenDTO adminTokens = signUpAdmin(buildValidUser("admin-busca-apellido"));
        signUp(buildUser("usuario-apellido", "Camila", "Fernandez"));

        UserCreateDTO adminDto = buildValidUser("otro-admin-buscable");
        signUpAdmin(adminDto);

        mockMvc.perform(get("/api/v1/users/admin/search")
                .param("q", "FERNAN")
                .header("Authorization", "Bearer " + adminTokens.accessToken()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(1)))
                .andExpect(jsonPath("$[0].lastname").value("Fernandez"))
                .andExpect(jsonPath("$[0].role").value("USER"));
    }

    @Test
    void adminSearch_ordenaResultadosPorApellidoNombreYEmail() throws Exception {
        TokenDTO adminTokens = signUpAdmin(buildValidUser("admin-busca-orden"));
        UserCreateDTO zeta = buildUser("zeta-sort", "Zoe", "Perez");
        UserCreateDTO ana = buildUser("ana-sort", "Ana", "Perez");
        UserCreateDTO bruno = buildUser("bruno-sort", "Bruno", "Acosta");
        signUp(zeta);
        signUp(ana);
        signUp(bruno);

        mockMvc.perform(get("/api/v1/users/admin/search")
                .param("q", "@agencia.com")
                .header("Authorization", "Bearer " + adminTokens.accessToken()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].lastname").value("Acosta"))
                .andExpect(jsonPath("$[0].name").value("Bruno"))
                .andExpect(jsonPath("$[1].lastname").value("Perez"))
                .andExpect(jsonPath("$[1].name").value("Ana"))
                .andExpect(jsonPath("$[2].lastname").value("Perez"))
                .andExpect(jsonPath("$[2].name").value("Zoe"));
    }

    @Test
    void adminDetail_devuelveDetalleCompleto() throws Exception {
        TokenDTO adminTokens = signUpAdmin(buildValidUser("admin-detalle"));
        UserCreateDTO userDto = buildUser("detalle-user", "Clara", "Benitez");
        TokenDTO userTokens = signUp(userDto);
        Long userId = extractId(userTokens.accessToken());
        User user = userRepository.findById(userId).orElseThrow();
        var student = studentRepository.findByParentId(userId).get(0);

        Trip trip = new Trip();
        trip.setName("Viaje a Mendoza");
        trip.setTotalAmount(BigDecimal.valueOf(120000));
        trip.setInstallmentsCount(3);
        trip.setDueDay(10);
        trip.setYellowWarningDays(5);
        trip.setFixedFineAmount(BigDecimal.valueOf(1000));
        trip.setRetroactiveActive(true);
        trip.setFirstDueDate(LocalDate.now().plusDays(10));
        trip.setCurrency(Currency.ARS);
        trip = tripRepository.save(trip);

        Installment installment = new Installment();
        installment.setTrip(trip);
        installment.setUser(user);
        installment.setStudent(student);
        installment.setInstallmentNumber(1);
        installment.setDueDate(LocalDate.now().plusDays(12));
        installment.setCapitalAmount(BigDecimal.valueOf(40000));
        installment.setFineAmount(BigDecimal.ZERO);
        installment.setRetroactiveAmount(BigDecimal.ZERO);
        installment.setPaidAmount(BigDecimal.valueOf(15000));
        installment.setStatus(InstallmentStatus.YELLOW);
        installment.recalculateTotalDue();
        installment = installmentRepository.save(installment);

        BankAccount bankAccount = BankAccount.builder()
                .bankName("Banco Test")
                .accountLabel("Cuenta corriente")
                .accountHolder("Agencia")
                .accountNumber("12345")
                .taxId("20333444556")
                .cbu("0000000000000000000001")
                .alias("agencia.test")
                .currency(Currency.ARS)
                .active(true)
                .displayOrder(1)
                .build();
        bankAccount = bankAccountRepository.save(bankAccount);

        PaymentReceipt receipt = PaymentReceipt.builder()
                .installment(installment)
                .bankAccount(bankAccount)
                .reportedAmount(BigDecimal.valueOf(15000))
                .paymentCurrency(Currency.ARS)
                .amountInTripCurrency(BigDecimal.valueOf(15000))
                .reportedPaymentDate(LocalDate.now())
                .paymentMethod(PaymentMethod.BANK_TRANSFER)
                .status(ReceiptStatus.APPROVED)
                .fileKey("https://example.com/comprobante.pdf")
                .adminObservation("Pago verificado")
                .build();
        paymentReceiptRepository.save(receipt);

        mockMvc.perform(get("/api/v1/users/admin/{id}/detail", userId)
                .header("Authorization", "Bearer " + adminTokens.accessToken()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(userId))
                .andExpect(jsonPath("$.email").value(userDto.email()))
                .andExpect(jsonPath("$.students[0].name").value("Alumno Clara"))
                .andExpect(jsonPath("$.installments[0].tripName").value("Viaje a Mendoza"))
                .andExpect(jsonPath("$.payments[0].adminObservation").value("Pago verificado"));
    }

    @Test
    void adminDetail_usuarioInexistenteDevuelve404() throws Exception {
        TokenDTO adminTokens = signUpAdmin(buildValidUser("admin-detalle-404"));

        mockMvc.perform(get("/api/v1/users/admin/999999/detail")
                .header("Authorization", "Bearer " + adminTokens.accessToken()))
                .andExpect(status().isNotFound());
    }

    @Test
    void adminDetail_siendoUsuarioNormalDevuelve403() throws Exception {
        TokenDTO userTokens = signUp(buildValidUser("user-no-detalle"));

        mockMvc.perform(get("/api/v1/users/admin/1/detail")
                .header("Authorization", "Bearer " + userTokens.accessToken()))
                .andExpect(status().isForbidden());
    }

    @Test
    void adminDetail_paraResponsableSinMovimientosDevuelveListasVacias() throws Exception {
        TokenDTO adminTokens = signUpAdmin(buildValidUser("admin-detalle-vacio"));
        UserCreateDTO userDto = buildUser("detalle-vacio", "Julieta", "Mora");
        TokenDTO userTokens = signUp(userDto);
        Long userId = extractId(userTokens.accessToken());

        mockMvc.perform(get("/api/v1/users/admin/{id}/detail", userId)
                .header("Authorization", "Bearer " + adminTokens.accessToken()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(userId))
                .andExpect(jsonPath("$.students", hasSize(1)))
                .andExpect(jsonPath("$.installments", hasSize(0)))
                .andExpect(jsonPath("$.payments", hasSize(0)));
    }

    @Test
    void adminDetail_paraOtroAdminDevuelve404() throws Exception {
        TokenDTO superAdminTokens = signUpAdmin(buildValidUser("admin-consulta-admin"));
        UserCreateDTO targetAdminDto = buildValidUser("admin-destino");
        TokenDTO targetAdminTokens = signUpAdmin(targetAdminDto);
        Long targetAdminId = extractId(targetAdminTokens.accessToken());

        mockMvc.perform(get("/api/v1/users/admin/{id}/detail", targetAdminId)
                .header("Authorization", "Bearer " + superAdminTokens.accessToken()))
                .andExpect(status().isNotFound());
    }

    private UserCreateDTO buildUser(String prefix, String name, String lastname) {
        return new UserCreateDTO(
                uniqueEmail(prefix),
                "Password123!",
                name,
                lastname,
                uniqueDni(),
                "1133344455",
                List.of(new StudentCreateDTO(
                        "Alumno " + name,
                        uniqueDni()
                ))
        );
    }

}
