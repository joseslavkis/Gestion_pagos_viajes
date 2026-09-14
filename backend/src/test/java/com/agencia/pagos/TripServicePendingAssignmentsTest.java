package com.agencia.pagos;

import com.agencia.pagos.dtos.request.UserAssignBulkDTO;
import com.agencia.pagos.dtos.response.BulkAssignResultDTO;
import com.agencia.pagos.dtos.response.TripStudentAdminDTO;
import com.agencia.pagos.entities.Currency;
import com.agencia.pagos.entities.Installment;
import com.agencia.pagos.entities.InstallmentStatus;
import com.agencia.pagos.entities.PendingTripStudent;
import com.agencia.pagos.entities.PaymentSubmissionStatus;
import com.agencia.pagos.entities.Role;
import com.agencia.pagos.entities.Student;
import com.agencia.pagos.entities.Trip;
import com.agencia.pagos.entities.user.User;
import com.agencia.pagos.repositories.InstallmentReminderNotificationRepository;
import com.agencia.pagos.repositories.InstallmentRepository;
import com.agencia.pagos.repositories.PaymentAllocationRepository;
import com.agencia.pagos.repositories.PaymentOutcomeRepository;
import com.agencia.pagos.repositories.PaymentReceiptRepository;
import com.agencia.pagos.repositories.PaymentSubmissionRepository;
import com.agencia.pagos.repositories.PendingTripStudentRepository;
import com.agencia.pagos.repositories.StudentRepository;
import com.agencia.pagos.repositories.TripRepository;
import com.agencia.pagos.repositories.UserRepository;
import com.agencia.pagos.services.InstallmentStatusResolver;
import com.agencia.pagos.services.InstallmentUiStatusResolver;
import com.agencia.pagos.services.PaymentAllocationPlanner;
import com.agencia.pagos.services.PaymentInstallmentOverlayService;
import com.agencia.pagos.services.TripExcelExporter;
import com.agencia.pagos.services.TripInstallmentAmountCalculator;
import com.agencia.pagos.services.TripService;
import jakarta.persistence.EntityNotFoundException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
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
    private PaymentSubmissionRepository paymentSubmissionRepository;

    @Mock
    private PaymentOutcomeRepository paymentOutcomeRepository;

    @Mock
    private PaymentAllocationRepository paymentAllocationRepository;

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
                paymentSubmissionRepository,
                paymentOutcomeRepository,
                paymentAllocationRepository,
                installmentReminderNotificationRepository,
                pendingTripStudentRepository,
                installmentStatusResolver,
                installmentUiStatusResolver,
                new PaymentInstallmentOverlayService(
                        paymentSubmissionRepository,
                        new PaymentAllocationPlanner()
                ),
                new TripInstallmentAmountCalculator(),
                new PaymentAllocationPlanner(),
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

        // [DEADLOCK FIX] Simple consistent-order lock protocol: snapshot trip IDs (no lock) →
        // lock the unique trips ascending → scoped pending FOR UPDATE. No iteration, no
        // re-snapshot, no Trip-set expansion.
        when(pendingTripStudentRepository.findByStudentDniWithTrip(student.getDni())).thenReturn(List.of(pending));
        when(tripRepository.findByIdForUpdate(5L)).thenReturn(Optional.of(trip));
        when(pendingTripStudentRepository.findByStudentDniAndTripIdInWithTripForUpdate(
                student.getDni(), Set.of(5L))).thenReturn(List.of(pending));
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

        when(pendingTripStudentRepository.findByStudentDniWithTrip(student.getDni())).thenReturn(List.of(pending));
        when(tripRepository.findByIdForUpdate(6L)).thenReturn(Optional.of(trip));
        when(pendingTripStudentRepository.findByStudentDniAndTripIdInWithTripForUpdate(
                student.getDni(), Set.of(6L))).thenReturn(List.of(pending));
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
    void materializePendingAssignmentsForStudent_noCandidates_returnsEarlyWithoutLocking() {
        Trip trip = buildTrip(15L, LocalDate.now().plusMonths(1), false);
        User parent = new User("Ana", "secret", "ana-no-pending@test.com", "Perez", Role.USER);
        setUserId(parent, 16L);

        Student student = Student.builder()
                .parent(parent)
                .name("Empty")
                .dni("15151515")
                .build();
        student.setId(17L);

        // Empty snapshot → no Trip locks and no Pending locks should be acquired.
        when(pendingTripStudentRepository.findByStudentDniWithTrip(student.getDni())).thenReturn(List.of());

        tripService.materializePendingAssignmentsForStudent(student);

        verify(tripRepository, never()).findByIdForUpdate(any());
        verify(pendingTripStudentRepository, never()).findByStudentDniAndTripIdInWithTripForUpdate(any(), any());
        verify(installmentRepository, never()).saveAll(any());
        verify(pendingTripStudentRepository, never()).deleteAll(any());
    }

    @Test
    void materializePendingAssignmentsForStudent_candidateDisappearsAfterTripLock_skipsAndDeletesNothing() {
        // Concise Trip-before-pending + disappearing-row coverage: snapshot had a row for
        // trip 25, the trip lock is acquired (proving Trip lock is taken first), but the
        // scoped pending read returns nothing because the row was deleted between snapshot
        // and lock. The worker must NOT save installments and must NOT call deleteAll on
        // an empty list.
        Trip trip = buildTrip(25L, LocalDate.now().plusMonths(1), false);

        Student student = Student.builder()
                .name("Disappear")
                .dni("25252525")
                .build();
        student.setId(26L);

        PendingTripStudent candidate = new PendingTripStudent();
        candidate.setTrip(trip);
        candidate.setStudentDni(student.getDni());

        when(pendingTripStudentRepository.findByStudentDniWithTrip(student.getDni())).thenReturn(List.of(candidate));
        when(tripRepository.findByIdForUpdate(25L)).thenReturn(Optional.of(trip));
        // Scoped pending read returns nothing — the row was deleted between snapshot and lock.
        when(pendingTripStudentRepository.findByStudentDniAndTripIdInWithTripForUpdate(
                student.getDni(), Set.of(25L))).thenReturn(List.of());

        tripService.materializePendingAssignmentsForStudent(student);

        // Trip lock was acquired (proving the lock-first ordering) ...
        verify(tripRepository, times(1)).findByIdForUpdate(25L);
        // ... then the scoped pending read returned empty → no writes.
        verify(pendingTripStudentRepository).findByStudentDniAndTripIdInWithTripForUpdate(
                student.getDni(), Set.of(25L));
        verify(installmentRepository, never()).saveAll(any());
        verify(pendingTripStudentRepository, never()).deleteAll(any());
    }

    @Test
    void materializePendingAssignmentsForStudent_workerOverload_usesProvidedPendingListWithoutRereading() {
        // Worker overload (called directly by UserService.claimPendingStudentForUser) MUST NOT
        // re-snapshot or re-read pending rows: it trusts the caller's already-locked Trip map
        // and pending list. This proves the duplicate scoped-pending query is gone.
        Trip trip = buildTrip(45L, LocalDate.now().plusMonths(1), false);

        User parent = new User("Ana", "secret", "ana-worker@test.com", "Perez", Role.USER);
        setUserId(parent, 46L);

        Student student = Student.builder()
                .parent(parent)
                .name("Worker")
                .dni("45454545")
                .build();
        student.setId(47L);

        PendingTripStudent pending = new PendingTripStudent();
        pending.setTrip(trip);
        pending.setStudentDni(student.getDni());

        when(installmentRepository.findAssignedStudentIdsByTripId(45L)).thenReturn(List.of());

        tripService.materializePendingAssignmentsForStudent(
                student, Map.of(45L, trip), List.of(pending)
        );

        // No snapshot, no scoped pending read, no extra Trip locks — the caller pre-locked everything.
        verify(pendingTripStudentRepository, never()).findByStudentDniWithTrip(any());
        verify(pendingTripStudentRepository, never()).findByStudentDniAndTripIdInWithTripForUpdate(any(), any());
        verify(tripRepository, never()).findByIdForUpdate(any());
        verify(installmentRepository).saveAll(any());
        verify(pendingTripStudentRepository).deleteAll(List.of(pending));
        verify(tripRepository).saveAll(org.mockito.ArgumentMatchers.argThat(
                (Iterable<Trip> trips) -> {
                    var iter = trips.iterator();
                    return iter.hasNext() && iter.next() == trip && !iter.hasNext();
                }
        ));
    }

    @Test
    void materializePendingAssignmentsForStudent_workerOverload_emptyPendingList_bailsWithoutWrites() {
        // Disappearing rows surface as an empty list to the worker; no writes, no deletes on
        // an empty list — same contract as the no-arg overload.
        Trip trip = buildTrip(48L, LocalDate.now().plusMonths(1), false);

        Student student = Student.builder()
                .name("Vanished")
                .dni("48484848")
                .build();
        student.setId(49L);

        tripService.materializePendingAssignmentsForStudent(
                student, Map.of(48L, trip), List.of()
        );

        verify(installmentRepository, never()).saveAll(any());
        verify(pendingTripStudentRepository, never()).deleteAll(any());
        verify(tripRepository, never()).saveAll(any());
    }

    @Test
    void materializePendingAssignmentsForStudent_multipleTrips_locksTripsInAscendingOrderBeforePending() {
        // Concise Trip-before-pending ordering coverage: two pending rows on different trips
        // must both be locked before any pending read, and they MUST be locked in ascending
        // trip-ID order to match unassignStudentByDni.
        Trip tripA = buildTrip(31L, LocalDate.now().plusMonths(1), false);
        Trip tripB = buildTrip(7L, LocalDate.now().plusMonths(1), false);

        User parent = new User("Ana", "secret", "ana-multi@test.com", "Perez", Role.USER);
        setUserId(parent, 32L);

        Student student = Student.builder()
                .parent(parent)
                .name("Multi")
                .dni("31313131")
                .build();
        student.setId(33L);

        PendingTripStudent pendingA = new PendingTripStudent();
        pendingA.setTrip(tripA);
        pendingA.setStudentDni(student.getDni());

        PendingTripStudent pendingB = new PendingTripStudent();
        pendingB.setTrip(tripB);
        pendingB.setStudentDni(student.getDni());

        // Snapshot ordering is intentionally not the lock ordering — service must sort IDs
        // ascending inside the helper.
        when(pendingTripStudentRepository.findByStudentDniWithTrip(student.getDni()))
                .thenReturn(List.of(pendingA, pendingB));
        when(tripRepository.findByIdForUpdate(7L)).thenReturn(Optional.of(tripB));
        when(tripRepository.findByIdForUpdate(31L)).thenReturn(Optional.of(tripA));
        when(pendingTripStudentRepository.findByStudentDniAndTripIdInWithTripForUpdate(
                student.getDni(), Set.of(7L, 31L))).thenReturn(List.of(pendingA, pendingB));
        when(installmentRepository.findAssignedStudentIdsByTripId(7L)).thenReturn(List.of());
        when(installmentRepository.findAssignedStudentIdsByTripId(31L)).thenReturn(List.of());

        tripService.materializePendingAssignmentsForStudent(student);

        // Trip locks must be acquired in ascending trip-ID order (7 before 31) so the direction
        // matches unassignStudentByDni and lock-order deadlocks are impossible.
        org.mockito.InOrder tripLockOrder = org.mockito.Mockito.inOrder(tripRepository);
        tripLockOrder.verify(tripRepository).findByIdForUpdate(7L);
        tripLockOrder.verify(tripRepository).findByIdForUpdate(31L);
        // The scoped pending read runs only AFTER both Trip locks were acquired.
        verify(pendingTripStudentRepository).findByStudentDniAndTripIdInWithTripForUpdate(
                student.getDni(), Set.of(7L, 31L));

        verify(installmentRepository, org.mockito.Mockito.atLeastOnce()).saveAll(any());
    }

    @Test
    void lockCandidateTripsForStudentDni_emptySnapshot_returnsEmptyAndDoesNotLock() {
        when(pendingTripStudentRepository.findByStudentDniWithTrip("90909090")).thenReturn(List.of());

        Map<Long, Trip> locked = tripService.lockCandidateTripsForStudentDni("90909090");

        assertTrue(locked.isEmpty());
        verify(tripRepository, never()).findByIdForUpdate(any());
    }

    @Test
    void lockCandidateTripsForStudentDni_singleTrip_locksThatTripInAscendingOrder() {
        Trip trip = buildTrip(105L, LocalDate.now().plusMonths(1), false);
        PendingTripStudent pending = new PendingTripStudent();
        pending.setTrip(trip);
        pending.setStudentDni("10101010");

        when(pendingTripStudentRepository.findByStudentDniWithTrip("10101010")).thenReturn(List.of(pending));
        when(tripRepository.findByIdForUpdate(105L)).thenReturn(Optional.of(trip));

        Map<Long, Trip> locked = tripService.lockCandidateTripsForStudentDni("10101010");

        assertEquals(Set.of(105L), locked.keySet());
        assertSame(trip, locked.get(105L));
        verify(tripRepository, times(1)).findByIdForUpdate(105L);
    }

    @Test
    void lockCandidateTripsForStudentDni_multipleTrips_locksThemInAscendingOrderBeforeAnyPendingRead() {
        // Snapshot returns trips in arbitrary order; the service must sort them ascending so
        // concurrent signup ↔ unassign cannot deadlock. A single snapshot read is sufficient
        // (no re-snapshot, no fixed-point iteration).
        Trip tripA = buildTrip(200L, LocalDate.now().plusMonths(1), false);
        Trip tripB = buildTrip(150L, LocalDate.now().plusMonths(1), false);
        Trip tripC = buildTrip(175L, LocalDate.now().plusMonths(1), false);

        PendingTripStudent pA = new PendingTripStudent();
        pA.setTrip(tripA);
        pA.setStudentDni("20202020");
        PendingTripStudent pB = new PendingTripStudent();
        pB.setTrip(tripB);
        pB.setStudentDni("20202020");
        PendingTripStudent pC = new PendingTripStudent();
        pC.setTrip(tripC);
        pC.setStudentDni("20202020");

        when(pendingTripStudentRepository.findByStudentDniWithTrip("20202020"))
                .thenReturn(List.of(pA, pC, pB));
        when(tripRepository.findByIdForUpdate(150L)).thenReturn(Optional.of(tripB));
        when(tripRepository.findByIdForUpdate(175L)).thenReturn(Optional.of(tripC));
        when(tripRepository.findByIdForUpdate(200L)).thenReturn(Optional.of(tripA));

        Map<Long, Trip> locked = tripService.lockCandidateTripsForStudentDni("20202020");

        assertEquals(Set.of(150L, 175L, 200L), locked.keySet());
        org.mockito.InOrder order = org.mockito.Mockito.inOrder(tripRepository);
        order.verify(tripRepository).findByIdForUpdate(150L);
        order.verify(tripRepository).findByIdForUpdate(175L);
        order.verify(tripRepository).findByIdForUpdate(200L);
        // Snapshot is read exactly once — no fixed-point re-snapshot.
        verify(pendingTripStudentRepository, times(1)).findByStudentDniWithTrip("20202020");
    }

    @Test
    void lockCandidateTripsForStudentDni_blankDni_returnsEmptyWithoutAnyAccess() {
        Map<Long, Trip> blank = tripService.lockCandidateTripsForStudentDni("");
        Map<Long, Trip> nullDni = tripService.lockCandidateTripsForStudentDni(null);
        Map<Long, Trip> whitespace = tripService.lockCandidateTripsForStudentDni("   ");

        assertTrue(blank.isEmpty());
        assertTrue(nullDni.isEmpty());
        assertTrue(whitespace.isEmpty());
        verifyNoInteractions(tripRepository);
        verifyNoInteractions(pendingTripStudentRepository);
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
    void unassignStudentByDni_pendingOnly_removesOnlyPendingRow() {
        Trip trip = buildTrip(40L, LocalDate.now().plusMonths(1), false);
        PendingTripStudent pending = new PendingTripStudent();
        pending.setTrip(trip);
        pending.setStudentDni("44555666");

        when(tripRepository.findByIdForUpdate(40L)).thenReturn(Optional.of(trip));
        when(pendingTripStudentRepository.findByTripIdAndStudentDni(40L, "44555666")).thenReturn(List.of(pending));
        when(installmentRepository.findByTripIdAndStudentDniForUpdate(40L, "44555666")).thenReturn(List.of());

        tripService.unassignStudentByDni(40L, "44555666");

        verify(pendingTripStudentRepository).deleteAll(List.of(pending));
        verify(installmentRepository, never()).deleteAll(any());
        verify(installmentReminderNotificationRepository, never()).deleteByInstallmentIdIn(any());
        verify(paymentSubmissionRepository, never()).existsByAnchorInstallmentIdIn(any());
        verify(paymentReceiptRepository, never()).existsByInstallmentIdIn(any());
    }

    @Test
    void unassignStudentByDni_noActivity_deletesRemindersAndInstallmentsAndRemovesParent() {
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

        Installment installment = buildInstallment(91L, trip, parent, student, 1, BigDecimal.ZERO);

        PendingTripStudent pending = new PendingTripStudent();
        pending.setTrip(trip);
        pending.setStudentDni(student.getDni());

        when(tripRepository.findByIdForUpdate(4L)).thenReturn(Optional.of(trip));
        when(pendingTripStudentRepository.findByTripIdAndStudentDni(4L, student.getDni())).thenReturn(List.of(pending));
        when(installmentRepository.findByTripIdAndStudentDniForUpdate(4L, student.getDni())).thenReturn(List.of(installment));
        // No activity anywhere — all existence checks return false.
        when(paymentSubmissionRepository.existsByAnchorInstallmentIdIn(List.of(91L))).thenReturn(false);
        when(paymentReceiptRepository.existsByInstallmentIdIn(List.of(91L))).thenReturn(false);
        when(installmentRepository.existsByTripIdAndUserId(4L, parent.getId())).thenReturn(false);

        tripService.unassignStudentByDni(4L, student.getDni());

        verify(pendingTripStudentRepository).deleteAll(List.of(pending));
        verify(installmentReminderNotificationRepository).deleteByInstallmentIdIn(List.of(91L));
        // Financial records are NEVER deleted on unassignment — this is the contract.
        verify(paymentSubmissionRepository, never()).deleteByTripId(any());
        verify(paymentOutcomeRepository, never()).deleteByTripId(any());
        verify(paymentAllocationRepository, never()).deleteByTripId(any());
        verify(installmentRepository).deleteAll(List.of(installment));
        assertTrue(trip.getAssignedUsers().isEmpty());
    }

    @ParameterizedTest
    @EnumSource(value = PaymentSubmissionStatus.class, names = {"PENDING", "RESOLVED", "VOIDED"})
    void unassignStudentByDni_paymentSubmissionAnchored_throws409AndDeletesNothing(PaymentSubmissionStatus status) {
        Trip trip = buildTrip(50L, LocalDate.now().plusMonths(1), false);
        User parent = new User("Ana", "secret", "ana-sub-" + status + "@test.com", "Perez", Role.USER);
        setUserId(parent, 22L);
        trip.setAssignedUsers(new ArrayList<>(List.of(parent)));

        Student student = Student.builder()
                .parent(parent)
                .name("Sub " + status)
                .dni("5000000" + status.ordinal())
                .build();
        student.setId(99L);

        Installment installment = buildInstallment(141L, trip, parent, student, 1, BigDecimal.ZERO);

        when(tripRepository.findByIdForUpdate(50L)).thenReturn(Optional.of(trip));
        when(pendingTripStudentRepository.findByTripIdAndStudentDni(50L, student.getDni())).thenReturn(List.of());
        when(installmentRepository.findByTripIdAndStudentDniForUpdate(50L, student.getDni())).thenReturn(List.of(installment));
        when(paymentSubmissionRepository.existsByAnchorInstallmentIdIn(List.of(141L))).thenReturn(true);

        IllegalStateException ex = assertThrows(
                IllegalStateException.class,
                () -> tripService.unassignStudentByDni(50L, student.getDni())
        );

        assertTrue(
                ex.getMessage().contains("actividad financiera"),
                "Expected financial-activity message for status " + status + " but was: " + ex.getMessage()
        );
        // No deletes of any kind on rejection — financial and structural data stays intact.
        verify(installmentRepository, never()).deleteAll(any());
        verify(installmentReminderNotificationRepository, never()).deleteByInstallmentIdIn(any());
        verify(paymentSubmissionRepository, never()).deleteByTripId(any());
        verify(paymentOutcomeRepository, never()).deleteByTripId(any());
        verify(paymentAllocationRepository, never()).deleteByTripId(any());
        verify(pendingTripStudentRepository, never()).deleteAll(any());
        // Parent must remain in assigned users — the trip scope is unchanged.
        assertTrue(trip.getAssignedUsers().contains(parent));
    }

    @Test
    void unassignStudentByDni_legacyPaymentReceipt_throws409AndDeletesNothing() {
        Trip trip = buildTrip(60L, LocalDate.now().plusMonths(1), false);
        User parent = new User("Ana", "secret", "ana-legacy@test.com", "Perez", Role.USER);
        setUserId(parent, 33L);
        trip.setAssignedUsers(new ArrayList<>(List.of(parent)));

        Student student = Student.builder()
                .parent(parent)
                .name("Legacy")
                .dni("60000000")
                .build();
        student.setId(101L);

        Installment installment = buildInstallment(151L, trip, parent, student, 1, BigDecimal.ZERO);

        when(tripRepository.findByIdForUpdate(60L)).thenReturn(Optional.of(trip));
        when(pendingTripStudentRepository.findByTripIdAndStudentDni(60L, student.getDni())).thenReturn(List.of());
        when(installmentRepository.findByTripIdAndStudentDniForUpdate(60L, student.getDni())).thenReturn(List.of(installment));
        when(paymentSubmissionRepository.existsByAnchorInstallmentIdIn(List.of(151L))).thenReturn(false);
        when(paymentReceiptRepository.existsByInstallmentIdIn(List.of(151L))).thenReturn(true);

        IllegalStateException ex = assertThrows(
                IllegalStateException.class,
                () -> tripService.unassignStudentByDni(60L, student.getDni())
        );

        assertTrue(ex.getMessage().contains("actividad financiera"));
        verify(installmentRepository, never()).deleteAll(any());
        verify(installmentReminderNotificationRepository, never()).deleteByInstallmentIdIn(any());
        verify(paymentSubmissionRepository, never()).deleteByTripId(any());
        verify(paymentOutcomeRepository, never()).deleteByTripId(any());
        verify(paymentAllocationRepository, never()).deleteByTripId(any());
        assertTrue(trip.getAssignedUsers().contains(parent));
    }

    @Test
    void unassignStudentByDni_paidAmountGreaterThanZero_throws409AndDeletesNothing() {
        Trip trip = buildTrip(70L, LocalDate.now().plusMonths(1), false);
        User parent = new User("Ana", "secret", "ana-paid@test.com", "Perez", Role.USER);
        setUserId(parent, 44L);
        trip.setAssignedUsers(new ArrayList<>(List.of(parent)));

        Student student = Student.builder()
                .parent(parent)
                .name("Paid")
                .dni("70000000")
                .build();
        student.setId(110L);

        Installment installment = buildInstallment(161L, trip, parent, student, 1, new BigDecimal("1500.00"));

        when(tripRepository.findByIdForUpdate(70L)).thenReturn(Optional.of(trip));
        when(pendingTripStudentRepository.findByTripIdAndStudentDni(70L, student.getDni())).thenReturn(List.of());
        when(installmentRepository.findByTripIdAndStudentDniForUpdate(70L, student.getDni())).thenReturn(List.of(installment));
        when(paymentSubmissionRepository.existsByAnchorInstallmentIdIn(List.of(161L))).thenReturn(false);
        when(paymentReceiptRepository.existsByInstallmentIdIn(List.of(161L))).thenReturn(false);

        IllegalStateException ex = assertThrows(
                IllegalStateException.class,
                () -> tripService.unassignStudentByDni(70L, student.getDni())
        );

        assertTrue(ex.getMessage().contains("actividad financiera"));
        verify(installmentRepository, never()).deleteAll(any());
        verify(installmentReminderNotificationRepository, never()).deleteByInstallmentIdIn(any());
        verify(paymentSubmissionRepository, never()).deleteByTripId(any());
        verify(paymentOutcomeRepository, never()).deleteByTripId(any());
        verify(paymentAllocationRepository, never()).deleteByTripId(any());
        assertTrue(trip.getAssignedUsers().contains(parent));
    }

    @Test
    void unassignStudentByDni_directAllocationOnDifferentInstallment_throws409WithZeroPaidAmount() {
        Trip trip = buildTrip(75L, LocalDate.now().plusMonths(1), false);
        User parent = new User("Ana", "secret", "ana-allocation@test.com", "Perez", Role.USER);
        setUserId(parent, 45L);
        trip.setAssignedUsers(new ArrayList<>(List.of(parent)));

        Student student = Student.builder()
                .parent(parent)
                .name("Allocated")
                .dni("75000000")
                .build();
        student.setId(115L);

        Installment anchor = buildInstallment(165L, trip, parent, student, 1, BigDecimal.ZERO);
        Installment allocated = buildInstallment(166L, trip, parent, student, 2, BigDecimal.ZERO);

        when(tripRepository.findByIdForUpdate(75L)).thenReturn(Optional.of(trip));
        when(pendingTripStudentRepository.findByTripIdAndStudentDni(75L, student.getDni())).thenReturn(List.of());
        when(installmentRepository.findByTripIdAndStudentDniForUpdate(75L, student.getDni()))
                .thenReturn(List.of(anchor, allocated));
        when(paymentSubmissionRepository.existsByAnchorInstallmentIdIn(List.of(165L, 166L))).thenReturn(false);
        when(paymentAllocationRepository.existsByInstallmentIdIn(List.of(165L, 166L))).thenReturn(true);

        IllegalStateException ex = assertThrows(
                IllegalStateException.class,
                () -> tripService.unassignStudentByDni(75L, student.getDni())
        );

        assertTrue(ex.getMessage().contains("actividad financiera"));
        verify(paymentAllocationRepository).existsByInstallmentIdIn(List.of(165L, 166L));
        verify(installmentRepository, never()).deleteAll(any());
        verify(installmentReminderNotificationRepository, never()).deleteByInstallmentIdIn(any());
        verify(pendingTripStudentRepository, never()).deleteAll(any());
        assertEquals(BigDecimal.ZERO, anchor.getPaidAmount());
        assertEquals(BigDecimal.ZERO, allocated.getPaidAmount());
        assertTrue(trip.getAssignedUsers().contains(parent));
    }

    @Test
    void unassignStudentByDni_parentHasAnotherStudentOnTrip_keepsParentAssigned() {
        Trip trip = buildTrip(80L, LocalDate.now().plusMonths(1), false);
        User parent = new User("Ana", "secret", "ana-keep@test.com", "Perez", Role.USER);
        setUserId(parent, 55L);
        trip.setAssignedUsers(new ArrayList<>(List.of(parent)));

        Student leavingStudent = Student.builder()
                .parent(parent)
                .name("Leaving")
                .dni("80000001")
                .build();
        leavingStudent.setId(120L);

        Installment leavingInstallment = buildInstallment(171L, trip, parent, leavingStudent, 1, BigDecimal.ZERO);

        when(tripRepository.findByIdForUpdate(80L)).thenReturn(Optional.of(trip));
        when(pendingTripStudentRepository.findByTripIdAndStudentDni(80L, leavingStudent.getDni())).thenReturn(List.of());
        when(installmentRepository.findByTripIdAndStudentDniForUpdate(80L, leavingStudent.getDni())).thenReturn(List.of(leavingInstallment));
        when(paymentSubmissionRepository.existsByAnchorInstallmentIdIn(List.of(171L))).thenReturn(false);
        when(paymentReceiptRepository.existsByInstallmentIdIn(List.of(171L))).thenReturn(false);
        // Other student on the trip means the parent must stay assigned.
        when(installmentRepository.existsByTripIdAndUserId(80L, parent.getId())).thenReturn(true);

        tripService.unassignStudentByDni(80L, leavingStudent.getDni());

        verify(installmentRepository).deleteAll(List.of(leavingInstallment));
        verify(installmentReminderNotificationRepository).deleteByInstallmentIdIn(List.of(171L));
        // Parent retention: tripRepository.save must NOT be invoked to drop the user from assignedUsers.
        verify(tripRepository, never()).save(any(Trip.class));
        assertTrue(trip.getAssignedUsers().contains(parent));
    }

    @Test
    void unassignStudentByDni_mixedPendingAndFinancialInstallment_neverDeletesPendingRow() {
        // [GUARD FIX] Mixed pending + financial installment: the pending delete must NOT run
        // even though the financial guard rejects — explicit semantics, not just rollback.
        Trip trip = buildTrip(85L, LocalDate.now().plusMonths(1), false);
        User parent = new User("Ana", "secret", "ana-mixed@test.com", "Perez", Role.USER);
        setUserId(parent, 56L);
        trip.setAssignedUsers(new ArrayList<>(List.of(parent)));

        Student student = Student.builder()
                .parent(parent)
                .name("Mixed")
                .dni("85000000")
                .build();
        student.setId(130L);

        Installment installment = buildInstallment(181L, trip, parent, student, 1, BigDecimal.ZERO);
        PendingTripStudent pending = new PendingTripStudent();
        pending.setTrip(trip);
        pending.setStudentDni(student.getDni());

        when(tripRepository.findByIdForUpdate(85L)).thenReturn(Optional.of(trip));
        when(pendingTripStudentRepository.findByTripIdAndStudentDni(85L, student.getDni())).thenReturn(List.of(pending));
        when(installmentRepository.findByTripIdAndStudentDniForUpdate(85L, student.getDni())).thenReturn(List.of(installment));
        // Submissions anchored → guard rejects.
        when(paymentSubmissionRepository.existsByAnchorInstallmentIdIn(List.of(181L))).thenReturn(true);

        IllegalStateException ex = assertThrows(
                IllegalStateException.class,
                () -> tripService.unassignStudentByDni(85L, student.getDni())
        );
        assertTrue(ex.getMessage().contains("actividad financiera"));

        // Explicit contract: pending row delete is NEVER called when the financial guard rejects,
        // regardless of how many pending rows are in scope or how the mocks are wired.
        verify(pendingTripStudentRepository, never()).deleteAll(any());
        verify(pendingTripStudentRepository, never()).deleteByTripId(any());
        verify(pendingTripStudentRepository, never()).deleteByTripIdAndStudentDni(any(), any());

        verify(installmentRepository, never()).deleteAll(any());
        verify(installmentReminderNotificationRepository, never()).deleteByInstallmentIdIn(any());
        verify(paymentReceiptRepository, never()).existsByInstallmentIdIn(any());
        // The existence check IS still called — the guard must run before any delete to be safe.
        verify(paymentSubmissionRepository).existsByAnchorInstallmentIdIn(List.of(181L));
        assertTrue(trip.getAssignedUsers().contains(parent));
    }

    @Test
    void unassignStudentByDni_pendingOnlyWithNoInstallments_deletesPendingRow() {
        // Sanity counterpart: when there is no installment scope at all, the pending row
        // is safe to delete because the financial guard does not apply (no installments to check).
        Trip trip = buildTrip(86L, LocalDate.now().plusMonths(1), false);

        PendingTripStudent pending = new PendingTripStudent();
        pending.setTrip(trip);
        pending.setStudentDni("86000000");

        when(tripRepository.findByIdForUpdate(86L)).thenReturn(Optional.of(trip));
        when(pendingTripStudentRepository.findByTripIdAndStudentDni(86L, "86000000")).thenReturn(List.of(pending));
        when(installmentRepository.findByTripIdAndStudentDniForUpdate(86L, "86000000")).thenReturn(List.of());

        tripService.unassignStudentByDni(86L, "86000000");

        verify(pendingTripStudentRepository).deleteAll(List.of(pending));
        verify(installmentRepository, never()).deleteAll(any());
        verify(paymentSubmissionRepository, never()).existsByAnchorInstallmentIdIn(any());
    }

    @Test
    void unassignStudentByDni_tripNotFound_throwsEntityNotFound() {
        when(tripRepository.findByIdForUpdate(999L)).thenReturn(Optional.empty());

        assertThrows(
                EntityNotFoundException.class,
                () -> tripService.unassignStudentByDni(999L, "11111111")
        );
    }

    @Test
    void unassignStudentByDni_noAssignmentForDni_throwsEntityNotFound() {
        Trip trip = buildTrip(90L, LocalDate.now().plusMonths(1), false);
        when(tripRepository.findByIdForUpdate(90L)).thenReturn(Optional.of(trip));
        when(pendingTripStudentRepository.findByTripIdAndStudentDni(90L, "99999999")).thenReturn(List.of());
        when(installmentRepository.findByTripIdAndStudentDniForUpdate(90L, "99999999")).thenReturn(List.of());

        assertThrows(
                EntityNotFoundException.class,
                () -> tripService.unassignStudentByDni(90L, "99999999")
        );
    }

    @Test
    void deleteTrip_locksTripBeforeDeletingFinancialChildren() {
        Trip trip = buildTrip(300L, LocalDate.now().plusMonths(1), false);

        when(tripRepository.findByIdForUpdate(300L)).thenReturn(Optional.of(trip));

        tripService.deleteTrip(300L);

        org.mockito.InOrder order = org.mockito.Mockito.inOrder(
                tripRepository,
                paymentReceiptRepository,
                paymentAllocationRepository,
                paymentOutcomeRepository,
                paymentSubmissionRepository,
                installmentReminderNotificationRepository,
                pendingTripStudentRepository,
                installmentRepository
        );
        order.verify(tripRepository).findByIdForUpdate(300L);
        order.verify(paymentSubmissionRepository).findByTripIdForUpdate(300L);
        order.verify(paymentReceiptRepository).deleteByInstallmentTripId(300L);
        order.verify(paymentAllocationRepository).deleteByTripId(300L);
        order.verify(paymentOutcomeRepository).deleteByTripId(300L);
        order.verify(paymentSubmissionRepository).deleteByTripId(300L);
        order.verify(installmentReminderNotificationRepository).deleteByInstallmentTripId(300L);
        order.verify(pendingTripStudentRepository).deleteByTripId(300L);
        order.verify(installmentRepository).deleteByTripId(300L);
        order.verify(tripRepository).delete(trip);
        verify(tripRepository, never()).findByIdWithUsers(300L);
    }

    private Installment buildInstallment(Long id, Trip trip, User parent, Student student, int number, BigDecimal paidAmount) {
        Installment installment = new Installment();
        installment.setId(id);
        installment.setTrip(trip);
        installment.setUser(parent);
        installment.setStudent(student);
        installment.setInstallmentNumber(number);
        installment.setDueDate(LocalDate.now().plusMonths(number));
        installment.setCapitalAmount(BigDecimal.valueOf(1000));
        installment.setRetroactiveAmount(BigDecimal.ZERO);
        installment.setFineAmount(BigDecimal.ZERO);
        installment.setPaidAmount(paidAmount == null ? BigDecimal.ZERO : paidAmount);
        installment.setStatus(InstallmentStatus.YELLOW);
        installment.recalculateTotalDue();
        return installment;
    }

    private Trip buildTrip(Long id, LocalDate firstDueDate, boolean retroactiveActive) {
        Trip trip = new Trip();
        setTripId(trip, id);
        trip.setName("Bariloche");
        trip.setCurrency(Currency.ARS);
        trip.setTotalAmount(new BigDecimal("300000.00"));
        trip.setFirstInstallmentAmount(new BigDecimal("100000.00"));
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
