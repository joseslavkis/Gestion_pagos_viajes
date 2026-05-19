package com.agencia.pagos;

import com.agencia.pagos.dtos.response.SpreadsheetDTO;
import com.agencia.pagos.dtos.response.SpreadsheetReceiptPageDTO;
import com.agencia.pagos.entities.Currency;
import com.agencia.pagos.entities.Installment;
import com.agencia.pagos.entities.InstallmentStatus;
import com.agencia.pagos.entities.PaymentMethod;
import com.agencia.pagos.entities.PaymentReceipt;
import com.agencia.pagos.entities.ReceiptStatus;
import com.agencia.pagos.entities.Student;
import com.agencia.pagos.entities.Trip;
import com.agencia.pagos.entities.user.User;
import com.agencia.pagos.repositories.InstallmentReminderNotificationRepository;
import com.agencia.pagos.repositories.InstallmentRepository;
import com.agencia.pagos.repositories.PaymentReceiptRepository;
import com.agencia.pagos.repositories.PendingTripStudentRepository;
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
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class TripServiceSpreadsheetTest {

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
                new InstallmentStatusResolver(),
                new InstallmentUiStatusResolver(),
                new TripExcelExporter()
        );
    }

    @Test
    void getSpreadsheet_sortByStudent_ordersRowsByStudentSurname() {
        Trip trip = buildTrip(10L);
        Installment legacyInstallment = buildInstallment(
                101L,
                trip,
                buildParent(1L, "Ana", "Zarate", "ana@test.com"),
                buildStudent(11L, "Bruno", "Zeta", "40111222"),
                1
        );
        Installment earlyInstallment = buildInstallment(
                102L,
                trip,
                buildParent(2L, "joSe", "beniTez", "jose@test.com"),
                buildStudent(12L, "Luca", "Acosta", "40222333"),
                1
        );

        when(tripRepository.findById(10L)).thenReturn(Optional.of(trip));
        when(installmentRepository.findByTripIdWithUsers(10L)).thenReturn(List.of(legacyInstallment, earlyInstallment));

        SpreadsheetDTO result = tripService.getSpreadsheet(10L, 0, 20, null, "student", "asc", null);

        assertEquals(2, result.rows().size());
        assertEquals("Luca", result.rows().get(0).studentName());
        assertEquals("Acosta", result.rows().get(0).studentLastname());
        assertEquals("JOSE", result.rows().get(0).name());
        assertEquals("BENITEZ", result.rows().get(0).lastname());
        assertEquals("Bruno", result.rows().get(1).studentName());
    }

    @ParameterizedTest
    @CsvSource({"unknown", "INVALID", "''", "xyz"})
    void normalizeSortBy_fallsBackToStudentForUnknownValues(String sortBy) {
        Trip trip = buildTrip(20L);
        Installment a = buildInstallment(
                201L, trip,
                buildParent(10L, "Carlos", "Gomez", "carlos@test.com"),
                buildStudent(21L, "Emilia", "Zabala", "50111222"),
                1);
        Installment b = buildInstallment(
                202L, trip,
                buildParent(11L, "Ana", "Lopez", "ana@test.com"),
                buildStudent(22L, "Luca", "Acosta", "50222333"),
                1);

        when(tripRepository.findById(20L)).thenReturn(Optional.of(trip));
        when(installmentRepository.findByTripIdWithUsers(20L)).thenReturn(List.of(a, b));

        SpreadsheetDTO result = tripService.getSpreadsheet(20L, 0, 20, null,
                sortBy.isEmpty() ? "student" : sortBy, "asc", null);

        assertEquals(2, result.rows().size());
        // Default fallback is student sort: Acosta before Zabala
        assertEquals("Acosta", result.rows().get(0).studentLastname());
        assertEquals("Zabala", result.rows().get(1).studentLastname());
    }

    @Test
    void getSpreadsheet_sortByDateAsc_ordersByEarliestDueDateFirst() {
        Trip trip = buildTrip(30L);
        // Participant A: earliest due date July 15 (latest)
        Installment a = buildInstallmentWithDueDate(
                301L, trip,
                buildParent(20L, "Maria", "Rios", "maria@test.com"),
                buildStudent(31L, "Tomas", "Paz", "60111222"),
                1, LocalDate.of(2026, 7, 15));
        // Participant B: earliest due date May 10 (earliest)
        Installment b = buildInstallmentWithDueDate(
                302L, trip,
                buildParent(21L, "Pedro", "Luna", "pedro@test.com"),
                buildStudent(32L, "Sofia", "Diaz", "60222333"),
                1, LocalDate.of(2026, 5, 10));
        // Participant C: earliest due date June 20 (middle)
        Installment c = buildInstallmentWithDueDate(
                303L, trip,
                buildParent(22L, "Laura", "Mora", "laura@test.com"),
                buildStudent(33L, "Mateo", "Rey", "60333444"),
                1, LocalDate.of(2026, 6, 20));

        when(tripRepository.findById(30L)).thenReturn(Optional.of(trip));
        when(installmentRepository.findByTripIdWithUsers(30L)).thenReturn(List.of(a, b, c));

        SpreadsheetDTO result = tripService.getSpreadsheet(30L, 0, 20, null, "date", "asc", null);

        assertEquals(3, result.rows().size());
        // Ascending order by earliest due date: May 10 (B), June 20 (C), July 15 (A)
        assertEquals("Sofia", result.rows().get(0).studentName());
        assertEquals("Mateo", result.rows().get(1).studentName());
        assertEquals("Tomas", result.rows().get(2).studentName());
    }

    @Test
    void getSpreadsheet_sortByDateDesc_ordersByLatestDueDateFirst() {
        Trip trip = buildTrip(40L);
        // Same data setup as asc test
        Installment a = buildInstallmentWithDueDate(
                401L, trip,
                buildParent(30L, "Maria", "Rios", "maria@test.com"),
                buildStudent(41L, "Tomas", "Paz", "70111222"),
                1, LocalDate.of(2026, 7, 15));
        Installment b = buildInstallmentWithDueDate(
                402L, trip,
                buildParent(31L, "Pedro", "Luna", "pedro@test.com"),
                buildStudent(42L, "Sofia", "Diaz", "70222333"),
                1, LocalDate.of(2026, 5, 10));
        Installment c = buildInstallmentWithDueDate(
                403L, trip,
                buildParent(32L, "Laura", "Mora", "laura@test.com"),
                buildStudent(43L, "Mateo", "Rey", "70333444"),
                1, LocalDate.of(2026, 6, 20));

        when(tripRepository.findById(40L)).thenReturn(Optional.of(trip));
        when(installmentRepository.findByTripIdWithUsers(40L)).thenReturn(List.of(a, b, c));

        SpreadsheetDTO result = tripService.getSpreadsheet(40L, 0, 20, null, "date", "desc", null);

        assertEquals(3, result.rows().size());
        // Descending order: July 15 (A), June 20 (C), May 10 (B)
        assertEquals("Tomas", result.rows().get(0).studentName());
        assertEquals("Mateo", result.rows().get(1).studentName());
        assertEquals("Sofia", result.rows().get(2).studentName());
    }

    @Test
    void getComprobantes_paginatesCorrectly() {
        Trip trip = buildTrip(50L);
        List<PaymentReceipt> receipts = new ArrayList<>();
        for (int i = 0; i < 45; i++) {
            Installment installment = buildInstallment(
                    500L + i, trip,
                    buildParent(50L, "Padre", "Nro" + i, "p" + i + "@test.com"),
                    buildStudent(50L + i, "Alumno" + i, "Apellido" + i, String.format("%08d", 40000000 + i)),
                    i + 1
            );
            receipts.add(PaymentReceipt.builder()
                    .id(500L + i)
                    .installment(installment)
                    .reportedPaymentDate(LocalDate.of(2026, 1, 1).plusDays(i))
                    .paymentMethod(PaymentMethod.BANK_TRANSFER)
                    .reportedAmount(new BigDecimal("85000"))
                    .paymentCurrency(Currency.ARS)
                    .exchangeRate(BigDecimal.ONE)
                    .amountInTripCurrency(new BigDecimal("85000"))
                    .status(ReceiptStatus.APPROVED)
                    .adminObservation(null)
                    .fileKey("receipt-" + i + ".pdf")
                    .build());
        }

        when(tripRepository.findById(50L)).thenReturn(Optional.of(trip));
        when(paymentReceiptRepository.findByTripIdWithContext(50L)).thenReturn(receipts);

        SpreadsheetReceiptPageDTO result = tripService.getComprobantes(50L, "reportedPaymentDate", "desc", 0, 20);

        assertEquals(20, result.content().size());
        assertEquals(45L, result.totalElements());
        assertEquals(3, result.totalPages());
        assertEquals(0, result.page());
        assertEquals(20, result.size());
        assertEquals("Viaje", result.tripName());
    }

    @Test
    void getComprobantes_emptyTrip() {
        Trip trip = buildTrip(60L);

        when(tripRepository.findById(60L)).thenReturn(Optional.of(trip));
        when(paymentReceiptRepository.findByTripIdWithContext(60L)).thenReturn(List.of());

        SpreadsheetReceiptPageDTO result = tripService.getComprobantes(60L, "reportedPaymentDate", "desc", 0, 20);

        assertEquals(0, result.content().size());
        assertEquals(0L, result.totalElements());
        assertEquals(0, result.totalPages());
        assertEquals(0, result.page());
        assertEquals(20, result.size());
    }

    @Test
    void getComprobantes_sortAsc_returnsReversedOrder() {
        Trip trip = buildTrip(70L);
        List<PaymentReceipt> receipts = new ArrayList<>();
        // Create 5 receipts with dates 2026-01-01 through 2026-01-05
        for (int i = 0; i < 5; i++) {
            Installment installment = buildInstallment(
                    700L + i, trip,
                    buildParent(70L, "Padre", "N" + i, "p" + i + "@test.com"),
                    buildStudent(70L + i, "A" + i, "Z" + i, String.format("%08d", 50000000 + i)),
                    1
            );
            receipts.add(PaymentReceipt.builder()
                    .id(700L + i)
                    .installment(installment)
                    .reportedPaymentDate(LocalDate.of(2026, 1, 1).plusDays(4 - i)) // reversed: 5,4,3,2,1
                    .paymentMethod(PaymentMethod.BANK_TRANSFER)
                    .reportedAmount(BigDecimal.TEN)
                    .paymentCurrency(Currency.ARS)
                    .exchangeRate(BigDecimal.ONE)
                    .amountInTripCurrency(BigDecimal.TEN)
                    .status(ReceiptStatus.APPROVED)
                    .adminObservation(null)
                    .fileKey("r" + i + ".pdf")
                    .build());
        }

        when(tripRepository.findById(70L)).thenReturn(Optional.of(trip));
        when(paymentReceiptRepository.findByTripIdWithContext(70L)).thenReturn(receipts);

        SpreadsheetReceiptPageDTO result = tripService.getComprobantes(70L, "reportedPaymentDate", "asc", 0, 10);

        assertEquals(5, result.content().size());
        // In ascending order: oldest date first
        assertEquals(LocalDate.of(2026, 1, 1), result.content().get(0).reportedPaymentDate());
        assertEquals(LocalDate.of(2026, 1, 5), result.content().get(4).reportedPaymentDate());
    }

    private Trip buildTrip(Long id) {
        Trip trip = new Trip();
        setField(trip, "id", id);
        trip.setName("Viaje");
        trip.setCurrency(Currency.ARS);
        trip.setInstallmentsCount(1);
        trip.setDueDay(10);
        trip.setYellowWarningDays(5);
        trip.setFixedFineAmount(BigDecimal.ZERO);
        trip.setRetroactiveActive(false);
        trip.setFirstDueDate(LocalDate.of(2026, 5, 10));
        return trip;
    }

    private User buildParent(Long id, String name, String lastname, String email) {
        User user = new User();
        setField(user, "id", id);
        setField(user, "name", name);
        setField(user, "lastname", lastname);
        setField(user, "email", email);
        return user;
    }

    private Student buildStudent(Long id, String name, String lastname, String dni) {
        Student student = new Student();
        setField(student, "id", id);
        student.setName(name);
        student.setDni(dni);
        setField(student, "lastname", lastname);
        return student;
    }

    private Installment buildInstallment(Long id, Trip trip, User user, Student student, int installmentNumber) {
        return buildInstallmentWithDueDate(id, trip, user, student, installmentNumber, LocalDate.of(2026, 5, 10));
    }

    private Installment buildInstallmentWithDueDate(
            Long id, Trip trip, User user, Student student, int installmentNumber, LocalDate dueDate) {
        Installment installment = new Installment();
        installment.setId(id);
        installment.setTrip(trip);
        installment.setUser(user);
        installment.setStudent(student);
        installment.setInstallmentNumber(installmentNumber);
        installment.setDueDate(dueDate);
        installment.setCapitalAmount(new BigDecimal("1000.00"));
        installment.setRetroactiveAmount(BigDecimal.ZERO.setScale(2));
        installment.setFineAmount(BigDecimal.ZERO.setScale(2));
        installment.setTotalDue(new BigDecimal("1000.00"));
        installment.setPaidAmount(BigDecimal.ZERO.setScale(2));
        installment.setStatus(InstallmentStatus.YELLOW);
        return installment;
    }

    private void setField(Object target, String fieldName, Object value) {
        try {
            var field = target.getClass().getDeclaredField(fieldName);
            field.setAccessible(true);
            field.set(target, value);
        } catch (ReflectiveOperationException ex) {
            throw new IllegalStateException("Could not set field " + fieldName, ex);
        }
    }
}
