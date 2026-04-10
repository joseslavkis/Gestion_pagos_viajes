package com.agencia.pagos;

import com.agencia.pagos.dtos.request.UserAssignBulkDTO;
import com.agencia.pagos.dtos.response.BulkAssignResultDTO;
import com.agencia.pagos.dtos.response.TripStudentAdminDTO;
import com.agencia.pagos.entities.Currency;
import com.agencia.pagos.entities.Installment;
import com.agencia.pagos.entities.InstallmentStatus;
import com.agencia.pagos.entities.PendingTripStudent;
import com.agencia.pagos.entities.Role;
import com.agencia.pagos.entities.Student;
import com.agencia.pagos.entities.Trip;
import com.agencia.pagos.entities.user.User;
import com.agencia.pagos.repositories.InstallmentReminderNotificationRepository;
import com.agencia.pagos.repositories.InstallmentRepository;
import com.agencia.pagos.repositories.PendingTripStudentRepository;
import com.agencia.pagos.repositories.PaymentReceiptRepository;
import com.agencia.pagos.repositories.StudentRepository;
import com.agencia.pagos.repositories.TripRepository;
import com.agencia.pagos.repositories.UserRepository;
import com.agencia.pagos.services.InstallmentStatusResolver;
import com.agencia.pagos.services.InstallmentUiStatusResolver;
import com.agencia.pagos.services.TripExcelExporter;
import com.agencia.pagos.services.TripService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class TripServicePendingAssignmentsTest {

    @Mock
    private TripRepository tripRepository;

    @Mock
    private UserRepository userRepository;

    @Mock
    private StudentRepository studentRepository;

    @Mock
    private InstallmentRepository installmentRepository;

    @Mock
    private PaymentReceiptRepository paymentReceiptRepository;

    @Mock
    private InstallmentReminderNotificationRepository installmentReminderNotificationRepository;

    @Mock
    private PendingTripStudentRepository pendingTripStudentRepository;

    private final InstallmentStatusResolver installmentStatusResolver = new InstallmentStatusResolver();
    private final InstallmentUiStatusResolver installmentUiStatusResolver = new InstallmentUiStatusResolver();
    private final TripExcelExporter tripExcelExporter = new TripExcelExporter();

    private TripService tripService;

    @BeforeEach
    void setUp() {
        tripService = new TripService(
                tripRepository,
                userRepository,
                studentRepository,
                installmentRepository,
                paymentReceiptRepository,
                installmentReminderNotificationRepository,
                pendingTripStudentRepository,
                installmentStatusResolver,
                installmentUiStatusResolver,
                tripExcelExporter
        );
    }

    @Test
    void assignUsersInBulk_createsPendingRowsWhenStudentsDoNotExistYet() {
        Trip trip = buildTrip(1L, LocalDate.now().plusMonths(1), false);
        List<String> dnis = List.of("12345678", "87654321");

        when(tripRepository.findByIdForUpdate(1L)).thenReturn(Optional.of(trip));
        when(studentRepository.findByDniIn(dnis)).thenReturn(List.of());
        when(installmentRepository.findAssignedStudentIdsByTripId(1L)).thenReturn(List.of());
        when(pendingTripStudentRepository.findByTripIdAndStudentDniIn(1L, dnis)).thenReturn(List.of());

        BulkAssignResultDTO result = tripService.assignUsersInBulk(1L, new UserAssignBulkDTO(dnis));

        @SuppressWarnings("unchecked")
        ArgumentCaptor<Iterable<PendingTripStudent>> pendingCaptor = ArgumentCaptor.forClass((Class) Iterable.class);

        verify(pendingTripStudentRepository).saveAll(pendingCaptor.capture());
        verify(installmentRepository, never()).saveAll(any());

        List<PendingTripStudent> savedPending = toList(pendingCaptor.getValue());
        assertEquals(2, savedPending.size());
        assertEquals(List.of("12345678", "87654321"), savedPending.stream().map(PendingTripStudent::getStudentDni).toList());
        assertEquals(0, result.assignedCount());
        assertEquals(2, result.pendingCount());
        assertTrue(result.message().contains("pendientes"));
    }

    @Test
    void assignUsersInBulk_normalizesFormattedDnisBeforeLookupAndSave() {
        Trip trip = buildTrip(11L, LocalDate.now().plusMonths(1), false);
        List<String> requestedDnis = List.of("12.345.678", "87-654-321", "33 444 555");
        List<String> normalizedDnis = List.of("12345678", "87654321", "33444555");

        when(tripRepository.findByIdForUpdate(11L)).thenReturn(Optional.of(trip));
        when(studentRepository.findByDniIn(normalizedDnis)).thenReturn(List.of());
        when(installmentRepository.findAssignedStudentIdsByTripId(11L)).thenReturn(List.of());
        when(pendingTripStudentRepository.findByTripIdAndStudentDniIn(11L, normalizedDnis)).thenReturn(List.of());

        BulkAssignResultDTO result = tripService.assignUsersInBulk(11L, new UserAssignBulkDTO(requestedDnis));

        @SuppressWarnings("unchecked")
        ArgumentCaptor<Iterable<PendingTripStudent>> pendingCaptor = ArgumentCaptor.forClass((Class) Iterable.class);

        verify(pendingTripStudentRepository).saveAll(pendingCaptor.capture());

        List<PendingTripStudent> savedPending = toList(pendingCaptor.getValue());
        assertEquals(normalizedDnis, savedPending.stream().map(PendingTripStudent::getStudentDni).toList());
        assertEquals(0, result.assignedCount());
        assertEquals(3, result.pendingCount());
    }

    @Test
    void materializePendingAssignmentsForStudent_generatesInstallmentsAndClearsPendingRows() {
        LocalDate firstDueDate = LocalDate.now(ZoneId.of("America/Argentina/Buenos_Aires")).minusMonths(3).withDayOfMonth(1);
        Trip trip = buildTrip(5L, firstDueDate, false);

        User parent = new User("Ana", "secret", "ana@test.com", "Perez", Role.USER);
        parent.setPhone("381123123");
        setUserId(parent, 9L);

        Student student = Student.builder()
                .parent(parent)
                .name("Tomi Perez")
                .dni("33444555")
                .build();
        student.setId(10L);

        PendingTripStudent pending = new PendingTripStudent();
        pending.setTrip(trip);
        pending.setStudentDni(student.getDni());

        when(pendingTripStudentRepository.findByStudentDniWithTripForUpdate(student.getDni())).thenReturn(List.of(pending));
        when(tripRepository.findByIdForUpdate(5L)).thenReturn(Optional.of(trip));
        when(installmentRepository.findAssignedStudentIdsByTripId(5L)).thenReturn(List.of());

        tripService.materializePendingAssignmentsForStudent(student);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<Iterable<Installment>> installmentCaptor = ArgumentCaptor.forClass((Class) Iterable.class);

        verify(installmentRepository).saveAll(installmentCaptor.capture());
        verify(pendingTripStudentRepository).deleteAll(List.of(pending));

        List<Installment> savedInstallments = toList(installmentCaptor.getValue());
        assertEquals(3, savedInstallments.size());
        assertTrue(savedInstallments.stream().allMatch(installment -> installment.getStudent() == student));
        assertTrue(savedInstallments.stream().allMatch(installment -> installment.getUser() == parent));
        assertTrue(savedInstallments.stream().allMatch(installment -> installment.getStatus() == InstallmentStatus.RED));
        assertTrue(savedInstallments.stream().allMatch(installment -> installment.getFineAmount().compareTo(new BigDecimal("1500.00")) == 0));
        assertEquals(1, trip.getAssignedUsers().size());
    }

    @Test
    void materializePendingAssignmentsForStudent_preservesExactFirstDueDate() {
        LocalDate firstDueDate = LocalDate.of(2026, 5, 8);
        Trip trip = buildTrip(6L, firstDueDate, false);
        trip.setDueDay(10);

        User parent = new User("Ana", "secret", "ana-due-date@test.com", "Perez", Role.USER);
        parent.setPhone("381123123");
        setUserId(parent, 13L);

        Student student = Student.builder()
                .parent(parent)
                .name("Mora Perez")
                .dni("44555666")
                .build();
        student.setId(14L);

        PendingTripStudent pending = new PendingTripStudent();
        pending.setTrip(trip);
        pending.setStudentDni(student.getDni());

        when(pendingTripStudentRepository.findByStudentDniWithTripForUpdate(student.getDni())).thenReturn(List.of(pending));
        when(tripRepository.findByIdForUpdate(6L)).thenReturn(Optional.of(trip));
        when(installmentRepository.findAssignedStudentIdsByTripId(6L)).thenReturn(List.of());

        tripService.materializePendingAssignmentsForStudent(student);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<Iterable<Installment>> installmentCaptor = ArgumentCaptor.forClass((Class) Iterable.class);

        verify(installmentRepository).saveAll(installmentCaptor.capture());

        List<Installment> savedInstallments = toList(installmentCaptor.getValue());
        assertEquals(3, savedInstallments.size());
        assertEquals(LocalDate.of(2026, 5, 8), savedInstallments.get(0).getDueDate());
        assertEquals(LocalDate.of(2026, 6, 10), savedInstallments.get(1).getDueDate());
        assertEquals(LocalDate.of(2026, 7, 10), savedInstallments.get(2).getDueDate());
    }

    @Test
    void assignUsersInBulk_rejectsDniAlreadyLoadedAsPendingOrAssigned() {
        Trip trip = buildTrip(2L, LocalDate.now().plusMonths(1), false);
        User parent = new User("Ana", "secret", "ana@test.com", "Perez", Role.USER);
        setUserId(parent, 7L);

        Student student = Student.builder()
                .parent(parent)
                .name("Tomi Perez")
                .dni("46113387")
                .build();
        student.setId(22L);

        PendingTripStudent pending = new PendingTripStudent();
        pending.setTrip(trip);
        pending.setStudentDni("99888777");

        when(tripRepository.findByIdForUpdate(2L)).thenReturn(Optional.of(trip));
        when(studentRepository.findByDniIn(List.of("46113387", "99888777"))).thenReturn(List.of(student));
        when(installmentRepository.findAssignedStudentIdsByTripId(2L)).thenReturn(List.of(22L));
        when(pendingTripStudentRepository.findByTripIdAndStudentDniIn(2L, List.of("46113387", "99888777")))
                .thenReturn(List.of(pending));

        IllegalStateException ex = assertThrows(
                IllegalStateException.class,
                () -> tripService.assignUsersInBulk(2L, new UserAssignBulkDTO(List.of("46113387", "99888777")))
        );

        assertTrue(ex.getMessage().contains("46113387"));
        assertTrue(ex.getMessage().contains("99888777"));
        verify(pendingTripStudentRepository, never()).saveAll(any());
        verify(installmentRepository, never()).saveAll(any());
    }

    @Test
    void getTripStudentsAdmin_returnsAssignedAndPendingStudentsTogether() {
        Trip trip = buildTrip(3L, LocalDate.now().plusMonths(1), false);
        User parent = new User("Ana", "secret", "ana@test.com", "Perez", Role.USER);
        setUserId(parent, 8L);

        Student student = Student.builder()
                .parent(parent)
                .name("Tomi Perez")
                .dni("33444555")
                .build();
        student.setId(11L);

        Installment installment = new Installment();
        installment.setId(77L);
        installment.setTrip(trip);
        installment.setUser(parent);
        installment.setStudent(student);
        installment.setInstallmentNumber(1);

        PendingTripStudent pending = new PendingTripStudent();
        pending.setTrip(trip);
        pending.setStudentDni("99888777");

        when(tripRepository.findById(3L)).thenReturn(Optional.of(trip));
        when(installmentRepository.findByTripIdWithUsers(3L)).thenReturn(List.of(installment));
        when(pendingTripStudentRepository.findByTripIdOrderByStudentDniAsc(3L)).thenReturn(List.of(pending));

        List<TripStudentAdminDTO> result = tripService.getTripStudentsAdmin(3L);

        assertEquals(2, result.size());
        assertEquals(List.of("33444555", "99888777"), result.stream().map(TripStudentAdminDTO::studentDni).toList());
        assertEquals("ASSIGNED", result.get(0).status());
        assertEquals("PENDING", result.get(1).status());
    }

    @Test
    void unassignStudentByDni_deletesPendingAndAssignedDataAndRemovesParentFromTrip() {
        Trip trip = buildTrip(4L, LocalDate.now().plusMonths(1), false);
        User parent = new User("Ana", "secret", "ana@test.com", "Perez", Role.USER);
        setUserId(parent, 12L);
        trip.setAssignedUsers(new ArrayList<>(List.of(parent)));

        Student student = Student.builder()
                .parent(parent)
                .name("Tomi Perez")
                .dni("33444555")
                .build();
        student.setId(19L);

        Installment installment = new Installment();
        installment.setId(91L);
        installment.setTrip(trip);
        installment.setUser(parent);
        installment.setStudent(student);
        installment.setInstallmentNumber(1);

        PendingTripStudent pending = new PendingTripStudent();
        pending.setTrip(trip);
        pending.setStudentDni(student.getDni());

        when(tripRepository.findByIdForUpdate(4L)).thenReturn(Optional.of(trip));
        when(pendingTripStudentRepository.findByTripIdAndStudentDni(4L, student.getDni())).thenReturn(List.of(pending));
        when(installmentRepository.findByTripIdAndStudentDni(4L, student.getDni())).thenReturn(List.of(installment));
        when(installmentRepository.existsByTripIdAndUserId(4L, parent.getId())).thenReturn(false);

        tripService.unassignStudentByDni(4L, student.getDni());

        verify(pendingTripStudentRepository).deleteAll(List.of(pending));
        verify(installmentReminderNotificationRepository).deleteByInstallmentIdIn(List.of(91L));
        verify(paymentReceiptRepository).deleteByInstallmentIdIn(List.of(91L));
        verify(installmentRepository).deleteAll(List.of(installment));
        assertTrue(trip.getAssignedUsers().isEmpty());
    }

    private Trip buildTrip(Long id, LocalDate firstDueDate, boolean retroactiveActive) {
        Trip trip = new Trip();
        setTripId(trip, id);
        trip.setName("Bariloche");
        trip.setCurrency(Currency.ARS);
        trip.setTotalAmount(new BigDecimal("300000.00"));
        trip.setInstallmentsCount(3);
        trip.setDueDay(1);
        trip.setYellowWarningDays(5);
        trip.setFixedFineAmount(new BigDecimal("1500.00"));
        trip.setRetroactiveActive(retroactiveActive);
        trip.setFirstDueDate(firstDueDate);
        return trip;
    }

    private void setTripId(Trip trip, Long id) {
        try {
            var field = Trip.class.getDeclaredField("id");
            field.setAccessible(true);
            field.set(trip, id);
        } catch (ReflectiveOperationException ex) {
            throw new RuntimeException(ex);
        }
    }

    private void setUserId(User user, Long id) {
        try {
            var field = User.class.getDeclaredField("id");
            field.setAccessible(true);
            field.set(user, id);
        } catch (ReflectiveOperationException ex) {
            throw new RuntimeException(ex);
        }
    }

    private <T> List<T> toList(Iterable<T> items) {
        if (items == null) {
            return List.of();
        }
        if (items instanceof List<T> list) {
            return list;
        }

        List<T> result = new ArrayList<>();
        for (T item : items) {
            result.add(item);
        }
        return result;
    }
}
