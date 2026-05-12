package com.agencia.pagos;

import com.agencia.pagos.entities.Currency;
import com.agencia.pagos.entities.Installment;
import com.agencia.pagos.entities.InstallmentStatus;
import com.agencia.pagos.entities.PaymentMethod;
import com.agencia.pagos.entities.PaymentOutcome;
import com.agencia.pagos.entities.PaymentOutcomeStatus;
import com.agencia.pagos.entities.PaymentSubmission;
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
import com.agencia.pagos.services.TripExcelExporter;
import com.agencia.pagos.services.TripInstallmentAmountCalculator;
import com.agencia.pagos.services.TripService;
import org.apache.poi.ss.usermodel.DataFormatter;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.when;

/**
 * Verifies that when a PaymentOutcome is APPROVED with corrected amounts
 * (different from the original PaymentSubmission), the generated Excel
 * reflects the approved outcome amounts, not the original submitted amounts.
 */
@ExtendWith(MockitoExtension.class)
class TripServiceApprovedOutcomeAmountTest {

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

    private final DataFormatter dataFormatter = new DataFormatter();

    @Test
    void exportSpreadsheetAsExcel_approvedOutcomeUsesOutcomeAmountsNotSubmissionAmounts() throws IOException {
        // ── Arrange ──────────────────────────────────────────────────────────
        Trip trip = new Trip();
        setField(trip, "id", 10L);
        trip.setName("Bariloche 2026");
        trip.setCurrency(Currency.ARS);
        trip.setInstallmentsCount(1);
        trip.setDueDay(10);
        trip.setYellowWarningDays(5);
        trip.setFixedFineAmount(BigDecimal.ZERO);
        trip.setRetroactiveActive(false);
        trip.setFirstDueDate(LocalDate.of(2026, 5, 10));

        User parent = new User("Carlos", "hashed", "carlos@test.com", "Gomez", Role.USER);
        setField(parent, "id", 1L);

        Student student = new Student();
        setField(student, "id", 100L);
        student.setName("Martina");
        setField(student, "lastname", "Gomez");
        student.setDni("40111222");

        Installment installment = new Installment();
        installment.setId(500L);
        installment.setInstallmentNumber(2);
        installment.setDueDate(LocalDate.of(2026, 6, 10));
        installment.setStudent(student);
        installment.setUser(parent);
        installment.setTrip(trip);

        // Original submission: user reported $1000 ARS
        PaymentSubmission submission = new PaymentSubmission();
        setField(submission, "id", 200L);
        submission.setTrip(trip);
        submission.setUser(parent);
        submission.setStudent(student);
        submission.setAnchorInstallment(installment);
        submission.setReportedAmount(new BigDecimal("1000.00"));
        submission.setAmountInTripCurrency(new BigDecimal("1000.00"));
        submission.setPaymentCurrency(Currency.ARS);
        submission.setExchangeRate(BigDecimal.ONE);
        submission.setReportedPaymentDate(LocalDate.of(2026, 5, 15));
        submission.setPaymentMethod(PaymentMethod.BANK_TRANSFER);
        submission.setStatus(PaymentSubmissionStatus.RESOLVED);
        submission.setFileKey("receipt.pdf");

        // Admin corrected the approved amount to $1500 ARS
        PaymentOutcome approvedOutcome = new PaymentOutcome();
        setField(approvedOutcome, "id", 300L);
        approvedOutcome.setSubmission(submission);
        approvedOutcome.setStatus(PaymentOutcomeStatus.APPROVED);
        approvedOutcome.setReportedAmount(new BigDecimal("1500.00"));
        approvedOutcome.setAmountInTripCurrency(new BigDecimal("1500.00"));
        approvedOutcome.setAdminObservation("Monto corregido por admin");
        approvedOutcome.setResolvedByEmail("admin@test.com");

        Set<PaymentOutcome> outcomes = new LinkedHashSet<>();
        outcomes.add(approvedOutcome);
        submission.setOutcomes(outcomes);

        // ── Mock wiring ──────────────────────────────────────────────────────
        when(tripRepository.findById(10L)).thenReturn(Optional.of(trip));
        when(installmentRepository.findByTripIdWithUsers(10L)).thenReturn(List.of());
        when(paymentSubmissionRepository.findByTripIdWithContext(10L))
                .thenReturn(List.of(submission));

        // ── Instantiate real service ─────────────────────────────────────────
        TripService tripService = new TripService(
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
                new InstallmentStatusResolver(),
                new InstallmentUiStatusResolver(),
                null,   // PaymentInstallmentOverlayService
                new TripInstallmentAmountCalculator(),
                null,   // PaymentAllocationPlanner
                new TripExcelExporter()
        );

        // ── Act ──────────────────────────────────────────────────────────────
        byte[] excelBytes = tripService.exportSpreadsheetAsExcel(10L);

        // ── Assert: parse Excel and verify the "Comprobantes" sheet ─────────
        try (XSSFWorkbook workbook = new XSSFWorkbook(new ByteArrayInputStream(excelBytes))) {
            var receiptsSheet = workbook.getSheet("Comprobantes");
            // Header row (0) + one data row
            assertEquals(1, receiptsSheet.getLastRowNum());

            var dataRow = receiptsSheet.getRow(1);

            // The amounts must come from the APPROVED outcome, NOT the submission
            assertEquals(1500.00, dataRow.getCell(7).getNumericCellValue(),
                    "Monto (col 7) must be approved outcome amount (1500), not submission amount (1000)");
            assertEquals(1500.00, dataRow.getCell(10).getNumericCellValue(),
                    "Monto convertido (col 10) must be approved outcome amount (1500), not submission amount (1000)");

            // Other fields should still reflect correct data
            assertEquals("Aprobado", dataFormatter.formatCellValue(dataRow.getCell(11)),
                    "Estado should be 'Aprobado'");
            assertEquals("Monto corregido por admin", dataFormatter.formatCellValue(dataRow.getCell(12)),
                    "Observación should come from the approved outcome");
            assertEquals("40111222", dataFormatter.formatCellValue(dataRow.getCell(4)),
                    "DNI alumno should still be correct");
            assertEquals("Gomez", dataFormatter.formatCellValue(dataRow.getCell(2)),
                    "Apellido alumno should still be correct");
        }
    }

    private static void setField(Object target, String fieldName, Object value) {
        try {
            var field = target.getClass().getDeclaredField(fieldName);
            field.setAccessible(true);
            field.set(target, value);
        } catch (ReflectiveOperationException ex) {
            throw new IllegalStateException("Could not set field " + fieldName, ex);
        }
    }
}
