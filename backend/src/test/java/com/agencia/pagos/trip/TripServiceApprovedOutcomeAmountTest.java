package com.agencia.pagos.trip;

import com.agencia.pagos.shared.money.Currency;
import com.agencia.pagos.trip.Installment;
import com.agencia.pagos.trip.InstallmentStatus;
import com.agencia.pagos.payment.PaymentMethod;
import com.agencia.pagos.payment.PaymentOutcome;
import com.agencia.pagos.payment.PaymentOutcomeStatus;
import com.agencia.pagos.payment.PaymentSubmission;
import com.agencia.pagos.payment.PaymentSubmissionStatus;
import com.agencia.pagos.user.Role;
import com.agencia.pagos.user.Student;
import com.agencia.pagos.trip.Trip;
import com.agencia.pagos.user.User;
import com.agencia.pagos.trip.InstallmentReminderNotificationRepository;
import com.agencia.pagos.trip.InstallmentRepository;
import com.agencia.pagos.payment.PaymentAllocationRepository;
import com.agencia.pagos.payment.PaymentOutcomeRepository;
import com.agencia.pagos.payment.PaymentReceiptRepository;
import com.agencia.pagos.payment.PaymentSubmissionRepository;
import com.agencia.pagos.trip.PendingTripStudentRepository;
import com.agencia.pagos.user.StudentRepository;
import com.agencia.pagos.trip.TripRepository;
import com.agencia.pagos.user.UserRepository;
import com.agencia.pagos.trip.InstallmentStatusResolver;
import com.agencia.pagos.trip.InstallmentUiStatusResolver;
import com.agencia.pagos.payment.PaymentAllocationPlanner;
import com.agencia.pagos.payment.PaymentInstallmentOverlayService;
import com.agencia.pagos.trip.TripExcelExporter;
import com.agencia.pagos.trip.TripInstallmentAmountCalculator;
import com.agencia.pagos.trip.TripService;
import org.apache.poi.ss.usermodel.DataFormatter;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
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

    @ParameterizedTest
    @ValueSource(strings = {"same-currency", "cross-currency", "voided", "new-quote"})
    void exportSpreadsheetAsExcel_approvedOutcomeUsesOutcomeAmountsNotSubmissionAmounts(String scenario) throws IOException {
        // ── Arrange ──────────────────────────────────────────────────────────
        Trip trip = new Trip();
        setField(trip, "id", 10L);
        trip.setName("Bariloche 2026");
        trip.setCurrency(Currency.ARS);
        trip.setInstallmentsCount(1);
        trip.setDueDay(10);
        trip.setYellowWarningDays(5);
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
        approvedOutcome.applySnapshot(com.agencia.pagos.payment.PaymentOutcomeSnapshot.fromSubmission(submission));
        approvedOutcome.setStatus(PaymentOutcomeStatus.APPROVED);
        approvedOutcome.setReportedAmount(new BigDecimal("1500.00"));
        approvedOutcome.setAmountInTripCurrency(new BigDecimal("1500.00"));
        approvedOutcome.setAdminObservation("Monto corregido por admin");
        approvedOutcome.setResolvedByEmail("admin@test.com");

        Set<PaymentOutcome> outcomes = new LinkedHashSet<>();
        outcomes.add(approvedOutcome);
        boolean crossCurrency = !scenario.equals("same-currency");
        if (crossCurrency) {
            trip.setCurrency(Currency.USD);
            submission.setReportedAmount(new BigDecimal("306000.00"));
            submission.setAmountInTripCurrency(new BigDecimal("200.00"));
            submission.setExchangeRate(new BigDecimal("1530.00000000"));
            submission.setExchangeRateScale(8);
            submission.setExchangeRateRequestedDate(submission.getReportedPaymentDate());
            submission.setExchangeRateEffectiveDate(submission.getReportedPaymentDate());
            submission.setExchangeRateSource("original-source");
            submission.setExchangeRateProvider("original-provider");
            approvedOutcome.setReportedAmount(new BigDecimal("150.00"));
            approvedOutcome.setAmountInTripCurrency(new BigDecimal("150.00"));
            approvedOutcome.applySnapshot(com.agencia.pagos.payment.PaymentOutcomeSnapshot.administrative(Currency.USD, null));
            PaymentOutcome rejected = new PaymentOutcome();
            rejected.setSubmission(submission);
            rejected.setStatus(PaymentOutcomeStatus.REJECTED);
            rejected.setReportedAmount(new BigDecimal("76500.00"));
            rejected.setAmountInTripCurrency(new BigDecimal("50.00"));
            rejected.applySnapshot(com.agencia.pagos.payment.PaymentOutcomeSnapshot.fromSubmission(submission));
            outcomes.add(rejected);
            if (scenario.equals("new-quote")) {
                submission.setReportedAmount(new BigDecimal("200.00"));
                submission.setPaymentCurrency(Currency.USD);
                submission.setExchangeRate(null);
                submission.setExchangeRateScale(null);
                submission.setExchangeRateRequestedDate(null);
                submission.setExchangeRateEffectiveDate(null);
                submission.setExchangeRateSource(null);
                submission.setExchangeRateProvider(null);
                approvedOutcome.setReportedAmount(new BigDecimal("153000.00"));
                approvedOutcome.setAmountInTripCurrency(new BigDecimal("100.00"));
                approvedOutcome.applySnapshot(com.agencia.pagos.payment.PaymentOutcomeSnapshot.administrative(Currency.ARS,
                        new com.agencia.pagos.payment.ExchangeRateQuote(new BigDecimal("1530.00000000"),
                                submission.getReportedPaymentDate(), submission.getReportedPaymentDate().minusDays(1),
                                "admin-source", "admin-provider", "admin-time")));
                rejected.setReportedAmount(new BigDecimal("100.00"));
                rejected.setAmountInTripCurrency(new BigDecimal("100.00"));
                rejected.applySnapshot(com.agencia.pagos.payment.PaymentOutcomeSnapshot.fromSubmission(submission));
            }
            if (scenario.equals("voided")) {
                submission.setStatus(PaymentSubmissionStatus.VOIDED);
            }
        }
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
                new PaymentInstallmentOverlayService(
                        paymentSubmissionRepository,
                        new PaymentAllocationPlanner()
                ),
                new TripInstallmentAmountCalculator(),
                new PaymentAllocationPlanner(),
                new TripExcelExporter()
        );

        // ── Act ──────────────────────────────────────────────────────────────
        byte[] excelBytes = tripService.exportSpreadsheetAsExcel(10L);

        // ── Assert: parse Excel and verify the "Comprobantes" sheet ─────────
        try (XSSFWorkbook workbook = new XSSFWorkbook(new ByteArrayInputStream(excelBytes))) {
            var receiptsSheet = workbook.getSheet("Comprobantes");
            // Header row (0) + one data row
            assertEquals(crossCurrency ? 2 : 1, receiptsSheet.getLastRowNum());

            var dataRow = receiptsSheet.getRow(1);

            // The amounts must come from the APPROVED outcome, NOT the submission
            assertEquals(approvedOutcome.getReportedAmount().doubleValue(), dataRow.getCell(7).getNumericCellValue(),
                    "Monto (col 7) must be approved outcome amount (1500), not submission amount (1000)");
            assertEquals(approvedOutcome.getAmountInTripCurrency().doubleValue(), dataRow.getCell(10).getNumericCellValue(),
                    "Monto convertido (col 10) must be approved outcome amount (1500), not submission amount (1000)");

            // Other fields should still reflect correct data
            assertEquals(scenario.equals("voided") ? "Anulado" : "Aprobado", dataFormatter.formatCellValue(dataRow.getCell(11)),
                    "Estado should be 'Aprobado'");
            assertEquals("Monto corregido por admin", dataFormatter.formatCellValue(dataRow.getCell(12)),
                    "Observación should come from the approved outcome");
            assertEquals("40111222", dataFormatter.formatCellValue(dataRow.getCell(4)),
                    "DNI alumno should still be correct");
            assertEquals("Gomez", dataFormatter.formatCellValue(dataRow.getCell(2)),
                    "Apellido alumno should still be correct");
            assertEquals(approvedOutcome.getCurrency().name(), dataFormatter.formatCellValue(dataRow.getCell(8)));
            assertEquals(submission.getReportedAmount().doubleValue(), dataRow.getCell(13).getNumericCellValue());
            assertEquals(submission.getPaymentCurrency().name(), dataFormatter.formatCellValue(dataRow.getCell(14)));
            if (scenario.equals("new-quote")) {
                assertEquals(1530.00, dataRow.getCell(9).getNumericCellValue());
                assertEquals("", dataFormatter.formatCellValue(dataRow.getCell(15)));
                assertEquals("admin-provider", dataFormatter.formatCellValue(dataRow.getCell(19)));
                assertEquals("admin-time", dataFormatter.formatCellValue(dataRow.getCell(20)));
                assertEquals("2", dataFormatter.formatCellValue(dataRow.getCell(21)));
                assertEquals("USD", dataFormatter.formatCellValue(receiptsSheet.getRow(2).getCell(8)));
                assertEquals(100.00, receiptsSheet.getRow(2).getCell(7).getNumericCellValue());
            } else if (crossCurrency) {
                assertEquals("", dataFormatter.formatCellValue(dataRow.getCell(9)), "Identity admin approval must not borrow original FX");
                var rejectedRow = receiptsSheet.getRow(2);
                assertEquals(76500.00, rejectedRow.getCell(7).getNumericCellValue());
                assertEquals("ARS", dataFormatter.formatCellValue(rejectedRow.getCell(8)));
                assertEquals(1530.00, rejectedRow.getCell(9).getNumericCellValue());
                assertEquals("Rechazado", dataFormatter.formatCellValue(rejectedRow.getCell(11)));
                assertEquals("original-provider", dataFormatter.formatCellValue(rejectedRow.getCell(19)));
            }
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
