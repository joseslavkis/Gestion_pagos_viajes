package com.agencia.pagos.services;

import com.agencia.pagos.dtos.request.TripCreateDTO;
import com.agencia.pagos.dtos.request.TripUpdateDTO;
import com.agencia.pagos.dtos.request.UserAssignBulkDTO;
import com.agencia.pagos.dtos.internal.SpreadsheetReceiptRowDTO;
import com.agencia.pagos.dtos.response.BulkAssignResultDTO;
import com.agencia.pagos.dtos.response.SpreadsheetDTO;
import com.agencia.pagos.dtos.response.SpreadsheetRowDTO;
import com.agencia.pagos.dtos.response.SpreadsheetRowInstallmentDTO;
import com.agencia.pagos.dtos.response.TripDetailDTO;
import com.agencia.pagos.dtos.response.TripStudentAdminDTO;
import com.agencia.pagos.dtos.response.TripSummaryDTO;
import com.agencia.pagos.entities.Currency;
import com.agencia.pagos.entities.Installment;
import com.agencia.pagos.entities.InstallmentStatus;
import com.agencia.pagos.entities.PendingTripStudent;
import com.agencia.pagos.entities.PaymentOutcome;
import com.agencia.pagos.entities.PaymentOutcomeStatus;
import com.agencia.pagos.entities.PaymentReceipt;
import com.agencia.pagos.entities.PaymentSubmission;
import com.agencia.pagos.entities.PaymentSubmissionStatus;
import com.agencia.pagos.entities.ReceiptStatus;
import com.agencia.pagos.entities.Student;
import com.agencia.pagos.entities.Trip;
import com.agencia.pagos.entities.user.User;
import com.agencia.pagos.repositories.InstallmentRepository;
import com.agencia.pagos.repositories.InstallmentReminderNotificationRepository;
import com.agencia.pagos.repositories.PendingTripStudentRepository;
import com.agencia.pagos.repositories.PaymentAllocationRepository;
import com.agencia.pagos.repositories.PaymentOutcomeRepository;
import com.agencia.pagos.repositories.PaymentReceiptRepository;
import com.agencia.pagos.repositories.PaymentSubmissionRepository;
import com.agencia.pagos.repositories.StudentRepository;
import com.agencia.pagos.repositories.TripRepository;
import com.agencia.pagos.repositories.UserRepository;
import jakarta.persistence.EntityNotFoundException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;
import java.util.function.Function;
import java.util.stream.Collectors;

@Service
@Transactional
public class TripService {

    private record SpreadsheetParticipantKey(Long userId, Long studentId) {}

    // [A-1] Use Argentina's business timezone for all "today" comparisons
    private static final ZoneId BUSINESS_ZONE = ZoneId.of("America/Argentina/Buenos_Aires");
    private static final Logger LOGGER = LoggerFactory.getLogger(TripService.class);

    private final TripRepository tripRepository;
    private final UserRepository userRepository;
    private final StudentRepository studentRepository;
    private final InstallmentRepository installmentRepository;
    private final PaymentReceiptRepository paymentReceiptRepository;
    private final PaymentSubmissionRepository paymentSubmissionRepository;
    private final PaymentOutcomeRepository paymentOutcomeRepository;
    private final PaymentAllocationRepository paymentAllocationRepository;
    private final InstallmentReminderNotificationRepository installmentReminderNotificationRepository;
    private final PendingTripStudentRepository pendingTripStudentRepository;
    private final InstallmentStatusResolver installmentStatusResolver;
    private final InstallmentUiStatusResolver installmentUiStatusResolver;
    private final PaymentInstallmentOverlayService paymentInstallmentOverlayService;
    private final TripInstallmentAmountCalculator tripInstallmentAmountCalculator;
    private final PaymentAllocationPlanner paymentAllocationPlanner;
    private final TripExcelExporter tripExcelExporter;

    @Autowired
    public TripService(
            TripRepository tripRepository,
            UserRepository userRepository,
            StudentRepository studentRepository,
            InstallmentRepository installmentRepository,
            PaymentReceiptRepository paymentReceiptRepository,
            PaymentSubmissionRepository paymentSubmissionRepository,
            PaymentOutcomeRepository paymentOutcomeRepository,
            PaymentAllocationRepository paymentAllocationRepository,
            InstallmentReminderNotificationRepository installmentReminderNotificationRepository,
            PendingTripStudentRepository pendingTripStudentRepository,
            InstallmentStatusResolver installmentStatusResolver,
            InstallmentUiStatusResolver installmentUiStatusResolver,
            PaymentInstallmentOverlayService paymentInstallmentOverlayService,
            TripInstallmentAmountCalculator tripInstallmentAmountCalculator,
            PaymentAllocationPlanner paymentAllocationPlanner,
            TripExcelExporter tripExcelExporter
    ) {
        this.tripRepository = tripRepository;
        this.userRepository = userRepository;
        this.studentRepository = studentRepository;
        this.installmentRepository = installmentRepository;
        this.paymentReceiptRepository = paymentReceiptRepository;
        this.paymentSubmissionRepository = paymentSubmissionRepository;
        this.paymentOutcomeRepository = paymentOutcomeRepository;
        this.paymentAllocationRepository = paymentAllocationRepository;
        this.installmentReminderNotificationRepository = installmentReminderNotificationRepository;
        this.pendingTripStudentRepository = pendingTripStudentRepository;
        this.installmentStatusResolver = installmentStatusResolver;
        this.installmentUiStatusResolver = installmentUiStatusResolver;
        this.paymentInstallmentOverlayService = paymentInstallmentOverlayService;
        this.tripInstallmentAmountCalculator = tripInstallmentAmountCalculator;
        this.paymentAllocationPlanner = paymentAllocationPlanner;
        this.tripExcelExporter = tripExcelExporter;
    }

    public TripDetailDTO createTrip(TripCreateDTO dto) {
        Trip trip = new Trip();
        trip.setName(dto.name());
        BigDecimal firstInstallmentAmount = tripInstallmentAmountCalculator.normalizeFirstInstallmentAmount(
                dto.totalAmount(),
                dto.firstInstallmentAmount(),
                dto.installmentsCount()
        );

        trip.setTotalAmount(dto.totalAmount());
        trip.setFirstInstallmentAmount(firstInstallmentAmount);
        trip.setInstallmentsCount(dto.installmentsCount());
        trip.setDueDay(dto.dueDay());
        trip.setYellowWarningDays(dto.yellowWarningDays());
        trip.setFixedFineAmount(dto.fixedFineAmount());
        trip.setRetroactiveActive(dto.retroactiveActive());
        trip.setCurrency(dto.currency() == null ? Currency.ARS : dto.currency());
        trip.setFirstDueDate(dto.firstDueDate());
        tripRepository.save(trip);
        return toDetailDTO(trip);
    }

    @Transactional(readOnly = true)
    public List<TripSummaryDTO> getAllTrips() {
        return tripRepository.findAllWithUsers().stream()
                .map(this::toSummaryDTO)
                .toList();
    }

    @Transactional(readOnly = true)
    public TripDetailDTO getTripById(Long id) {
        Trip trip = tripRepository.findByIdWithUsers(id)
                .orElseThrow(() -> new EntityNotFoundException("Trip not found with id " + id));
        return toDetailDTO(trip);
    }

    public TripDetailDTO updateTrip(Long id, TripUpdateDTO dto) {
        // Pessimistic-write lock on the Trip row so concurrent PATCH calls serialize on the
        // calendar-integrity decision. The lock is acquired BEFORE the installment-existence
        // check below.
        Trip trip = tripRepository.findByIdForUpdate(id)
                .orElseThrow(() -> new EntityNotFoundException("Trip not found with id " + id));

        // Calendar fields (dueDay / firstDueDate) cannot be modified once any installment has
        // been generated — changing them would invalidate the dueDate stored on every existing
        // quota. Detect real requested changes (nullable DTO fields mean "absent"; identical
        // values are no-ops) and refuse only when an installment already exists. Assigned
        // users or pending students are NOT the materialization signal.
        if (hasCalendarChange(trip, dto) && installmentRepository.existsByTripId(trip.getId())) {
            throw new IllegalStateException(
                    "No se puede modificar el calendario de vencimientos porque el viaje ya tiene cuotas generadas.");
        }

        if (dto.name() != null) trip.setName(dto.name());
        if (dto.dueDay() != null) trip.setDueDay(dto.dueDay());
        if (dto.yellowWarningDays() != null) trip.setYellowWarningDays(dto.yellowWarningDays());
        if (dto.retroactiveActive() != null) trip.setRetroactiveActive(dto.retroactiveActive());
        if (dto.firstDueDate() != null) trip.setFirstDueDate(dto.firstDueDate());

        if (dto.fixedFineAmount() != null) {
            trip.setFixedFineAmount(dto.fixedFineAmount());
            // La lógica de recálculo de multas en cuotas RED se agrega en el Paso 5
        }

        tripRepository.save(trip);
        return toDetailDTO(trip);
    }

    /**
     * Returns {@code true} when the request carries at least one calendar field
     * ({@code dueDay} or {@code firstDueDate}) that actually differs from the value currently
     * stored on the Trip. A null DTO field is treated as "absent" and never counts as a
     * change; identical values are no-ops.
     */
    private boolean hasCalendarChange(Trip trip, TripUpdateDTO dto) {
        if (dto.dueDay() != null && !Objects.equals(dto.dueDay(), trip.getDueDay())) {
            return true;
        }
        if (dto.firstDueDate() != null && !Objects.equals(dto.firstDueDate(), trip.getFirstDueDate())) {
            return true;
        }
        return false;
    }

    public void deleteTrip(Long id) {
        Trip trip = tripRepository.findByIdForUpdate(id)
                .orElseThrow(() -> new EntityNotFoundException("Trip not found with id " + id));

        // Lock submissions before deleting outcomes so review/void either finishes first or
        // observes the submission as deleted; it cannot mutate rows while their outcomes vanish.
        paymentSubmissionRepository.findByTripIdForUpdate(trip.getId());
        paymentReceiptRepository.deleteByInstallmentTripId(trip.getId());
        paymentAllocationRepository.deleteByTripId(trip.getId());
        paymentOutcomeRepository.deleteByTripId(trip.getId());
        paymentSubmissionRepository.deleteByTripId(trip.getId());
        installmentReminderNotificationRepository.deleteByInstallmentTripId(trip.getId());
        pendingTripStudentRepository.deleteByTripId(trip.getId());
        installmentRepository.deleteByTripId(trip.getId());

        trip.getAssignedUsers().clear();

        tripRepository.delete(trip);
    }

    @Transactional(readOnly = true)
    public SpreadsheetDTO getSpreadsheet(
            Long tripId,
            int page,
            int size,
            String search,
            String sortBy,
            String order,
            InstallmentStatus status
    ) {
        return buildSpreadsheet(tripId, page, size, search, sortBy, order, status, true);
    }

    @Transactional(readOnly = true)
    public byte[] exportSpreadsheetAsExcel(Long tripId) {
        Trip trip = tripRepository.findById(tripId)
                .orElseThrow(() -> new EntityNotFoundException("Trip not found with id " + tripId));

        SpreadsheetDTO data = getSpreadsheetUnpaged(tripId);
        List<SpreadsheetReceiptRowDTO> receipts = buildReceiptRows(tripId);
        return tripExcelExporter.export(data, trip.getCurrency().name(), receipts);
    }

    private List<SpreadsheetReceiptRowDTO> buildReceiptRows(Long tripId) {
        List<SpreadsheetReceiptRowDTO> rows = new ArrayList<>();

        List<PaymentReceipt> paymentReceipts = paymentReceiptRepository.findByTripIdWithContext(tripId);
        for (PaymentReceipt receipt : paymentReceipts) {
            Student student = receipt.getInstallment() == null ? null : receipt.getInstallment().getStudent();
            rows.add(new SpreadsheetReceiptRowDTO(
                    receipt.getInstallment() == null ? null : receipt.getInstallment().getInstallmentNumber(),
                    receipt.getInstallment() == null ? null : receipt.getInstallment().getDueDate(),
                    student == null ? null : student.getLastname(),
                    student == null ? null : student.getName(),
                    student == null ? null : student.getDni(),
                    receipt.getReportedPaymentDate(),
                    receipt.getPaymentMethod() == null ? null : receipt.getPaymentMethod().name(),
                    receipt.getReportedAmount(),
                    receipt.getPaymentCurrency() == null ? null : receipt.getPaymentCurrency().name(),
                    receipt.getExchangeRate(),
                    receipt.getAmountInTripCurrency(),
                    mapReceiptStatus(receipt.getStatus()),
                    receipt.getAdminObservation()
            ));
        }

        List<PaymentSubmission> submissions = paymentSubmissionRepository.findByTripIdWithContext(tripId);
        for (PaymentSubmission submission : submissions) {
            appendSubmissionRows(rows, submission);
        }

        rows.sort(
                Comparator.comparing(SpreadsheetReceiptRowDTO::reportedPaymentDate,
                                Comparator.nullsLast(Comparator.reverseOrder()))
                        .thenComparing(SpreadsheetReceiptRowDTO::installmentNumber,
                                Comparator.nullsLast(Integer::compareTo))
                        .thenComparing(SpreadsheetReceiptRowDTO::studentLastname,
                                Comparator.nullsLast(String.CASE_INSENSITIVE_ORDER))
                        .thenComparing(SpreadsheetReceiptRowDTO::studentName,
                                Comparator.nullsLast(String.CASE_INSENSITIVE_ORDER))
        );

        return rows;
    }

    private void appendSubmissionRows(List<SpreadsheetReceiptRowDTO> rows, PaymentSubmission submission) {
        PaymentOutcome rejectedOutcome = null;
        PaymentOutcome approvedOutcome = null;

        for (PaymentOutcome outcome : submission.getOutcomes() == null ? Set.<PaymentOutcome>of() : submission.getOutcomes()) {
            if (outcome.getStatus() == PaymentOutcomeStatus.REJECTED && rejectedOutcome == null) {
                rejectedOutcome = outcome;
            }
            if (outcome.getStatus() == PaymentOutcomeStatus.APPROVED && approvedOutcome == null) {
                approvedOutcome = outcome;
            }
        }

        if (approvedOutcome != null) {
            rows.add(toSubmissionRow(
                    submission,
                    submission.getAnchorInstallment(),
                    approvedOutcome.getReportedAmount(),
                    approvedOutcome.getAmountInTripCurrency(),
                    "Aprobado",
                    approvedOutcome.getAdminObservation()
            ));
            return;
        }

        String statusLabel = resolveSubmissionStatusLabel(submission, rejectedOutcome);
        String adminObservation = rejectedOutcome == null ? null : rejectedOutcome.getAdminObservation();
        rows.add(tryProjectSubmission(submission, statusLabel, adminObservation));
    }

    private SpreadsheetReceiptRowDTO tryProjectSubmission(
            PaymentSubmission submission,
            String statusLabel,
            String adminObservation
    ) {
        if (submission.getAnchorInstallment() != null) {
            try {
                var plan = paymentAllocationPlanner.plan(
                        List.of(submission.getAnchorInstallment()),
                        submission.getReportedAmount(),
                        submission.getPaymentCurrency(),
                        submission.getExchangeRate()
                );
                if (!plan.allocations().isEmpty()) {
                    var first = plan.allocations().get(0);
                    return toSubmissionRow(
                            submission,
                            first.installment(),
                            first.reportedAmount(),
                            first.amountInTripCurrency(),
                            statusLabel,
                            adminObservation
                    );
                }
            } catch (RuntimeException ex) {
                LOGGER.warn(
                        "Could not project payment submission {} into installments, falling back to anchor installment",
                        submission.getId(),
                        ex
                );
            }
        }

        return toSubmissionRow(
                submission,
                submission.getAnchorInstallment(),
                submission.getReportedAmount(),
                submission.getAmountInTripCurrency(),
                statusLabel,
                adminObservation
        );
    }

    private SpreadsheetReceiptRowDTO toSubmissionRow(
            PaymentSubmission submission,
            Installment installment,
            BigDecimal reportedAmount,
            BigDecimal amountInTripCurrency,
            String status,
            String adminObservation
    ) {
        Student student = submission.getStudent() != null
                ? submission.getStudent()
                : installment == null ? null : installment.getStudent();

        return new SpreadsheetReceiptRowDTO(
                installment == null ? null : installment.getInstallmentNumber(),
                installment == null ? null : installment.getDueDate(),
                student == null ? null : student.getLastname(),
                student == null ? null : student.getName(),
                student == null ? null : student.getDni(),
                submission.getReportedPaymentDate(),
                submission.getPaymentMethod() == null ? null : submission.getPaymentMethod().name(),
                reportedAmount,
                submission.getPaymentCurrency() == null ? null : submission.getPaymentCurrency().name(),
                submission.getExchangeRate(),
                amountInTripCurrency,
                status,
                adminObservation
        );
    }

    private String mapReceiptStatus(ReceiptStatus status) {
        if (status == null) {
            return "";
        }
        return switch (status) {
            case PENDING -> "Pendiente";
            case APPROVED -> "Aprobado";
            case REJECTED -> "Rechazado";
        };
    }

    private String resolveSubmissionStatusLabel(PaymentSubmission submission, PaymentOutcome rejectedOutcome) {
        if (rejectedOutcome != null) {
            return "Rechazado";
        }

        if (submission.getOutcomes() != null) {
            for (PaymentOutcome outcome : submission.getOutcomes()) {
                if (outcome.getStatus() == PaymentOutcomeStatus.VOIDED) {
                    return "Anulado";
                }
            }
        }

        if (submission.getStatus() == PaymentSubmissionStatus.VOIDED) {
            return "Anulado";
        }

        return "Pendiente";
    }

    // [C-2, A-1] Uses pessimistic lock + Argentina timezone
    public BulkAssignResultDTO assignUsersInBulk(Long tripId, UserAssignBulkDTO dto) {
        List<String> requestedDnis = dto.studentDnis().stream()
                .map(StudentDniNormalizer::normalizeAndValidate)
                .toList();

        if (new LinkedHashSet<>(requestedDnis).size() != requestedDnis.size()) {
            throw new IllegalStateException("Los DNIs no deben repetirse");
        }

        lockStudentDnis(requestedDnis);

        Trip trip = tripRepository.findByIdForUpdate(tripId)
                .orElseThrow(() -> new EntityNotFoundException("Trip not found"));

        Map<String, Student> studentsByDni = studentRepository.findByDniIn(requestedDnis).stream()
                .collect(Collectors.toMap(Student::getDni, Function.identity()));

        Set<Long> alreadyAssignedParentIds = trip.getAssignedUsers().stream()
                .map(User::getId)
                .collect(Collectors.toSet());
        Set<Long> alreadyAssignedStudentIds = new HashSet<>(installmentRepository.findAssignedStudentIdsByTripId(tripId));
        Map<String, PendingTripStudent> pendingByDni = pendingTripStudentRepository
                .findByTripIdAndStudentDniIn(tripId, requestedDnis)
                .stream()
                .collect(Collectors.toMap(PendingTripStudent::getStudentDni, Function.identity()));

        List<String> rejectedDnis = requestedDnis.stream()
                .filter(requestedDni -> {
                    Student student = studentsByDni.get(requestedDni);
                    boolean alreadyAssigned = student != null && alreadyAssignedStudentIds.contains(student.getId());
                    return alreadyAssigned || pendingByDni.containsKey(requestedDni);
                })
                .distinct()
                .toList();

        if (!rejectedDnis.isEmpty()) {
            throw new IllegalStateException(buildBulkAssignRejectedMessage(rejectedDnis));
        }

        List<BigDecimal> amounts = tripInstallmentAmountCalculator.calculate(
                trip.getTotalAmount(),
                trip.getFirstInstallmentAmount(),
                trip.getInstallmentsCount()
        );
        LocalDate now = LocalDate.now(BUSINESS_ZONE);
        List<Installment> installmentsToSave = new ArrayList<>();
        List<PendingTripStudent> pendingToSave = new ArrayList<>();
        int assignedCount = 0;
        int pendingCount = 0;

        for (String requestedDni : requestedDnis) {
            Student student = studentsByDni.get(requestedDni);
            if (student != null) {
                if (assignStudentToTrip(
                        trip,
                        student,
                        amounts,
                        now,
                        alreadyAssignedParentIds,
                        alreadyAssignedStudentIds,
                        installmentsToSave
                )) {
                    assignedCount++;
                }
                continue;
            }

            PendingTripStudent pendingTripStudent = new PendingTripStudent();
            pendingTripStudent.setTrip(trip);
            pendingTripStudent.setStudentDni(requestedDni);
            pendingToSave.add(pendingTripStudent);
            pendingCount++;
        }

        if (!pendingToSave.isEmpty()) {
            pendingTripStudentRepository.saveAll(pendingToSave);
        }
        if (!installmentsToSave.isEmpty()) {
            installmentRepository.saveAll(installmentsToSave);
        }
        tripRepository.save(trip);

        return new BulkAssignResultDTO(
                "success",
                buildBulkAssignMessage(assignedCount, pendingCount),
                assignedCount,
                pendingCount
        );
    }

    @Transactional(readOnly = true)
    public List<TripStudentAdminDTO> getTripStudentsAdmin(Long tripId) {
        Trip trip = tripRepository.findById(tripId)
                .orElseThrow(() -> new EntityNotFoundException("Trip not found"));

        List<Installment> installments = installmentRepository.findByTripIdWithUsers(trip.getId());
        Map<String, TripStudentAdminDTO> studentsByDni = new HashMap<>();
        Map<String, Integer> installmentsCountByDni = new HashMap<>();

        for (Installment installment : installments) {
            Student student = installment.getStudent();
            if (student == null || student.getDni() == null) {
                continue;
            }

            String studentDni = student.getDni();
            installmentsCountByDni.merge(studentDni, 1, Integer::sum);
            studentsByDni.putIfAbsent(studentDni, toTripStudentAdminDTO(student, installment.getUser(), "ASSIGNED", 0));
        }

        List<PendingTripStudent> pendingStudents = pendingTripStudentRepository.findByTripIdOrderByStudentDniAsc(tripId);
        for (PendingTripStudent pendingStudent : pendingStudents) {
            studentsByDni.putIfAbsent(
                    pendingStudent.getStudentDni(),
                    new TripStudentAdminDTO(
                            pendingStudent.getStudentDni(),
                            null,
                            null,
                            null,
                            null,
                            null,
                            "PENDING",
                            0
                    )
            );
        }

        return studentsByDni.values().stream()
                .map(item -> {
                    int installmentsCount = "ASSIGNED".equals(item.status())
                            ? installmentsCountByDni.getOrDefault(item.studentDni(), item.installmentsCount())
                            : 0;
                    return new TripStudentAdminDTO(
                            item.studentDni(),
                            item.studentId(),
                            item.studentName(),
                            item.parentUserId(),
                            item.parentFullName(),
                            item.parentEmail(),
                            item.status(),
                            installmentsCount
                    );
                })
                .sorted(Comparator.comparing(TripStudentAdminDTO::studentDni))
                .toList();
    }

    public void unassignStudentByDni(Long tripId, String studentDni) {
        String normalizedDni = StudentDniNormalizer.normalizeAndValidate(studentDni);
        pendingTripStudentRepository.lockStudentDni(normalizedDni);

        Trip trip = tripRepository.findByIdForUpdate(tripId)
                .orElseThrow(() -> new EntityNotFoundException("Trip not found"));

        List<PendingTripStudent> pendingStudents = pendingTripStudentRepository.findByTripIdAndStudentDni(tripId, normalizedDni);
        // Pessimistic-lock the actual installment scope (trip + student DNI) so concurrent payment
        // registration/review paths cannot interleave between the activity check and the deletes.
        List<Installment> installments = installmentRepository.findByTripIdAndStudentDniForUpdate(tripId, normalizedDni);

        if (pendingStudents.isEmpty() && installments.isEmpty()) {
            throw new EntityNotFoundException("No existe una asignación para el DNI " + normalizedDni + " en este viaje.");
        }

        List<Long> installmentIds = installments.stream()
                .map(Installment::getId)
                .toList();

        // GUARD: never delete financial history. If the locked scope carries any activity, refuse.
        // CRITICAL: this guard runs BEFORE any delete — including pending rows — so a mixed
        // pending+installment rejection is guaranteed to leave the PendingTripStudent row intact
        // (explicit semantics, not merely relying on transaction rollback).
        if (!installmentIds.isEmpty() && hasFinancialActivity(installmentIds, installments)) {
            throw new IllegalStateException(
                    "No se puede desasignar al alumno porque posee actividad financiera registrada."
            );
        }

        // Pending rows are only deleted once the financial guard has cleared, even when there
        // are no installments in scope (pure pending path) or when the installment scope is clean.
        if (!pendingStudents.isEmpty()) {
            pendingTripStudentRepository.deleteAll(pendingStudents);
        }

        if (installments.isEmpty()) {
            return;
        }

        if (!installmentIds.isEmpty()) {
            installmentReminderNotificationRepository.deleteByInstallmentIdIn(installmentIds);
        }
        // No financial records (receipts, submissions, outcomes, allocations) are deleted on
        // unassignment; the no-activity branch above guarantees the scope is clean.

        User parent = installments.get(0).getUser();
        installmentRepository.deleteAll(installments);

        if (parent != null && !installmentRepository.existsByTripIdAndUserId(tripId, parent.getId())) {
            trip.getAssignedUsers().removeIf(user -> user.getId().equals(parent.getId()));
            tripRepository.save(trip);
        }
    }

    /**
     * Locks every Trip referenced by a {@code PendingTripStudent} row for the given student DNI,
     * in ascending trip-ID order, and returns the locked Trip entities keyed by trip id. The
     * lock order is total and matches {@link #unassignStudentByDni(Long, String)} (which locks
     * {@code Trip} before any pending delete) so signup and unassignment cannot deadlock on a
     * Trip ↔ PendingTripStudent cycle.
     *
     * <p>Algorithm (single, deterministic pass):
     * <ol>
     *   <li>snapshot pending rows for the DNI <em>without a lock</em> via
     *       {@code PendingTripStudentRepository.findByStudentDniWithTrip}; collect the distinct
     *       trip IDs;</li>
     *   <li>sort the IDs ascending and acquire {@code SELECT ... FOR UPDATE} on each Trip in
     *       that exact order — the total ordering matches {@code unassignStudentByDni};</li>
     *   <li>return the resulting locked Trip entities keyed by id (empty when no pending rows).</li>
     * </ol>
     * Any pending row inserted for a trip not present in the snapshot is <strong>not</strong>
     * silently processed in this materialization: it remains a legitimate pending assignment
     * that a later materialization (or sign-up) will pick up. This is the explicit contract — no
     * fixed-point iteration, no max iteration cap, no incremental acquisition that could
     * reorder the lock acquisition across multiple Trips and risk a deadlock.
     *
     * <p>Caller contract: any pending-row lock acquired AFTER this method returns MUST be scoped
     * via {@code PendingTripStudentRepository.findByStudentDniAndTripIdInWithTripForUpdate(dni,
     * lockedTripIds.keySet())}. Locking a pending row whose trip has not been locked by us yet
     * would invert the Trip → Pending ordering and reintroduce a deadlock.
     */
    public Map<Long, Trip> lockCandidateTripsForStudentDni(String studentDni) {
        return snapshotAndLockCandidateTrips(studentDni, null);
    }

    /**
     * Snapshot pending rows for the given DNI <em>without a lock</em>, lock the distinct Trips
     * deterministically ascending, and return the locked Trip entities keyed by trip id. The
     * output map is empty when there are no pending rows for the DNI. When {@code result} is
     * null, callers that don't need the Trip entities can read just the keys.
     */
    Map<Long, Trip> snapshotAndLockCandidateTrips(String studentDni, Map<Long, Trip> result) {
        if (studentDni == null || studentDni.isBlank()) {
            return result == null ? new HashMap<>() : result;
        }
        pendingTripStudentRepository.lockStudentDni(studentDni);
        Set<Long> tripIds = pendingTripStudentRepository.findByStudentDniWithTrip(studentDni).stream()
                .map(p -> p.getTrip().getId())
                .collect(Collectors.toCollection(TreeSet::new));
        Map<Long, Trip> lockedById = result == null ? new HashMap<>() : result;
        for (Long tripId : tripIds) {
            Trip trip = tripRepository.findByIdForUpdate(tripId)
                    .orElseThrow(() -> new EntityNotFoundException("Trip not found with id " + tripId));
            lockedById.put(tripId, trip);
        }
        return lockedById;
    }

    private void lockStudentDnis(Collection<String> studentDnis) {
        studentDnis.stream()
                .distinct()
                .sorted()
                .forEach(pendingTripStudentRepository::lockStudentDni);
    }

    /**
     * Single source of truth for "does the locked installment scope carry any financial activity?".
     * Returns {@code true} when ANY of the scoped installment IDs has:
     * <ul>
     *   <li>a {@code PaymentSubmission} anchored to it, regardless of PENDING/RESOLVED/VOIDED/unknown status;</li>
     *   <li>a legacy {@code PaymentReceipt} attached to it;</li>
     *   <li>{@code paidAmount > 0} on the already-locked installment entity.</li>
     * </ul>
     * Uses efficient exists queries against the indexed FK columns; no full collection loads.
     */
    private boolean hasFinancialActivity(List<Long> installmentIds, List<Installment> lockedInstallments) {
        if (installmentIds == null || installmentIds.isEmpty()) {
            return false;
        }
        if (paymentSubmissionRepository.existsByAnchorInstallmentIdIn(installmentIds)) {
            return true;
        }
        if (paymentAllocationRepository.existsByInstallmentIdIn(installmentIds)) {
            return true;
        }
        if (paymentReceiptRepository.existsByInstallmentIdIn(installmentIds)) {
            return true;
        }
        for (Installment installment : lockedInstallments) {
            BigDecimal paid = installment.getPaidAmount();
            if (paid != null && paid.compareTo(BigDecimal.ZERO) > 0) {
                return true;
            }
        }
        return false;
    }

    /**
     * Convenience entry point for direct callers: snapshot pending rows for the student DNI,
     * lock every distinct Trip in ascending order, scope-lock the matching pending rows, then
     * delegate to {@link #materializePendingAssignmentsForStudent(Student, Map, List)} with the
     * locked map and pending rows. The lock order is Trip → PendingTripStudent, matching
     * {@code unassignStudentByDni}.
     */
    public void materializePendingAssignmentsForStudent(Student student) {
        if (student == null) {
            throw new IllegalArgumentException("Student is required");
        }
        if (student.getId() == null) {
            throw new IllegalArgumentException("Student must be persisted before assigning trips");
        }
        String studentDni = student.getDni();
        Map<Long, Trip> lockedTripsById = snapshotAndLockCandidateTrips(studentDni, new HashMap<>());
        if (lockedTripsById.isEmpty()) {
            return;
        }

        List<PendingTripStudent> pendingTrips = pendingTripStudentRepository
                .findByStudentDniAndTripIdInWithTripForUpdate(studentDni, lockedTripsById.keySet());
        materializePendingAssignmentsForStudent(student, lockedTripsById, pendingTrips);
    }

    /**
     * Overload for callers that have already locked the candidate Trips (e.g. signup's
     * {@code lockCandidateTripsForStudentDni}) but have not yet scope-locked the pending rows.
     * Issues the scoped pending {@code FOR UPDATE} on the supplied Trip set, then delegates to
     * the worker {@link #materializePendingAssignmentsForStudent(Student, Map, List)}.
     */
    public void materializePendingAssignmentsForStudent(Student student, Map<Long, Trip> lockedTripsById) {
        if (student == null) {
            throw new IllegalArgumentException("Student is required");
        }
        if (student.getId() == null) {
            throw new IllegalArgumentException("Student must be persisted before assigning trips");
        }
        if (lockedTripsById == null || lockedTripsById.isEmpty()) {
            return;
        }

        String studentDni = student.getDni();
        List<PendingTripStudent> pendingTrips = pendingTripStudentRepository
                .findByStudentDniAndTripIdInWithTripForUpdate(studentDni, lockedTripsById.keySet());
        materializePendingAssignmentsForStudent(student, lockedTripsById, pendingTrips);
    }

    /**
     * Proportional worker that completes the materialization using an <em>already-locked</em>
     * Trip map and an <em>already-locked</em> pending-row list. The caller MUST have acquired
     * every {@code SELECT ... FOR UPDATE} on the supplied Trip map and pending rows. This
     * method performs <strong>only</strong> installment generation, parent-binding updates on
     * the locked Trips, and pending row cleanup. It does NOT re-snapshot, does NOT lock any
     * additional Trips, does NOT expand the Trip set, and does NOT re-read pending rows —
     * preventing the duplicate scoped pending query and the Trip A/Trip B lock-expansion
     * deadlock. {@code signup} now passes the same locked map and pending list here so the
     * lock order across the whole transaction remains the total ascending order established by
     * {@code lockCandidateTripsForStudentDni}.
     *
     * <p>An empty {@code lockedTripsById} is a no-op: there is nothing scoped to materialize
     * and no writes are emitted. Disappearing pending rows (deleted between the caller's
     * snapshot/lock and this call) surface as an empty {@code pendingTrips} list — the worker
     * bails with no writes.
     */
    public void materializePendingAssignmentsForStudent(
            Student student,
            Map<Long, Trip> lockedTripsById,
            List<PendingTripStudent> pendingTrips
    ) {
        if (student == null) {
            throw new IllegalArgumentException("Student is required");
        }
        if (student.getId() == null) {
            throw new IllegalArgumentException("Student must be persisted before assigning trips");
        }
        if (lockedTripsById == null || lockedTripsById.isEmpty()) {
            return;
        }
        if (pendingTrips == null || pendingTrips.isEmpty()) {
            // Rows disappeared between the caller's snapshot/lock and this call. No writes.
            return;
        }

        LocalDate now = LocalDate.now(BUSINESS_ZONE);
        List<Installment> installmentsToSave = new ArrayList<>();

        // Reuse the Trip entities already locked by the caller — no second lock acquisition
        // needed. Reusing the managed entity guarantees we save() the updated assignedUsers
        // collection. The Trip set is intentionally NOT expanded: every pending row in this
        // pass MUST have its Trip id present in lockedTripsById.
        for (PendingTripStudent pendingTripStudent : pendingTrips) {
            Long tripId = pendingTripStudent.getTrip().getId();
            Trip trip = lockedTripsById.get(tripId);
            if (trip == null) {
                throw new EntityNotFoundException("Trip not found");
            }
            List<BigDecimal> amounts = tripInstallmentAmountCalculator.calculate(
                    trip.getTotalAmount(),
                    trip.getFirstInstallmentAmount(),
                    trip.getInstallmentsCount()
            );
            Set<Long> alreadyAssignedParentIds = trip.getAssignedUsers().stream()
                    .map(User::getId)
                    .collect(Collectors.toSet());
            Set<Long> alreadyAssignedStudentIds = new HashSet<>(installmentRepository.findAssignedStudentIdsByTripId(trip.getId()));
            assignStudentToTrip(
                    trip,
                    student,
                    amounts,
                    now,
                    alreadyAssignedParentIds,
                    alreadyAssignedStudentIds,
                    installmentsToSave
            );
        }

        if (!installmentsToSave.isEmpty()) {
            installmentRepository.saveAll(installmentsToSave);
        }
        pendingTripStudentRepository.deleteAll(pendingTrips);
        if (!lockedTripsById.isEmpty()) {
            tripRepository.saveAll(lockedTripsById.values());
        }
    }

    // ── Private helpers ───────────────────────────────────────────────────────

    private boolean assignStudentToTrip(
            Trip trip,
            Student student,
            List<BigDecimal> amounts,
            LocalDate now,
            Set<Long> alreadyAssignedParentIds,
            Set<Long> alreadyAssignedStudentIds,
            List<Installment> installmentsToSave
    ) {
        if (alreadyAssignedStudentIds.contains(student.getId())) {
            return false;
        }

        User user = student.getParent();
        if (!alreadyAssignedParentIds.contains(user.getId())) {
            trip.getAssignedUsers().add(user);
            alreadyAssignedParentIds.add(user.getId());
        }

        for (int i = 1; i <= trip.getInstallmentsCount(); i++) {
            LocalDate currentDueDate = resolveInstallmentDueDate(trip, i);
            BigDecimal installmentCapital = amounts.get(i - 1);

            Installment installment = new Installment();
            installment.setTrip(trip);
            installment.setUser(user);
            installment.setStudent(student);
            installment.setInstallmentNumber(i);
            installment.setDueDate(currentDueDate);
            installment.setCapitalAmount(installmentCapital);
            installment.setRetroactiveAmount(BigDecimal.ZERO);

            if (!currentDueDate.isBefore(now)) {
                installment.setFineAmount(BigDecimal.ZERO);
                installment.setStatus(InstallmentStatus.YELLOW);
            } else if (trip.getRetroactiveActive()) {
                installment.setFineAmount(BigDecimal.ZERO);
                installment.setStatus(InstallmentStatus.RETROACTIVE);
            } else {
                installment.setFineAmount(trip.getFixedFineAmount());
                installment.setStatus(InstallmentStatus.RED);
            }

            installment.recalculateTotalDue();
            installmentsToSave.add(installment);
        }

        alreadyAssignedStudentIds.add(student.getId());
        return true;
    }

    private LocalDate resolveInstallmentDueDate(Trip trip, int installmentNumber) {
        if (installmentNumber == 1) {
            return trip.getFirstDueDate();
        }

        LocalDate baseDate = trip.getFirstDueDate().plusMonths(installmentNumber - 1L);
        int validDay = Math.min(trip.getDueDay(), baseDate.lengthOfMonth());
        return baseDate.withDayOfMonth(validDay);
    }

    private String buildBulkAssignMessage(int assignedCount, int pendingCount) {
        if (assignedCount == 0 && pendingCount == 0) {
            return "Todos los DNIs indicados ya estaban asignados o pendientes.";
        }
        if (assignedCount > 0 && pendingCount > 0) {
            return "Se asignaron " + assignedCount + " alumnos y " + pendingCount
                    + " DNI quedaron pendientes hasta que el padre se registre.";
        }
        if (assignedCount > 0) {
            return "Se asignaron " + assignedCount + " alumnos correctamente.";
        }
        return "Se cargaron " + pendingCount + " DNI pendientes hasta que el padre se registre.";
    }

    private String buildBulkAssignRejectedMessage(List<String> rejectedDnis) {
        String listedDnis = new LinkedHashSet<>(rejectedDnis).stream()
                .collect(Collectors.joining(", "));
        if (rejectedDnis.size() == 1) {
            return "El DNI " + listedDnis + " ya está cargado en este viaje y fue rechazado.";
        }
        return "Los siguientes DNIs ya están cargados en este viaje y fueron rechazados: " + listedDnis + ".";
    }

    private TripStudentAdminDTO toTripStudentAdminDTO(Student student, User parent, String status, int installmentsCount) {
        String parentFullName = parent == null
                ? null
                : formatResponsibleFullName(parent);
        return new TripStudentAdminDTO(
                student.getDni(),
                student.getId(),
                StudentNameFormatter.displayName(student),
                parent != null ? parent.getId() : null,
                parentFullName == null || parentFullName.isBlank() ? null : parentFullName,
                parent != null ? parent.getEmail() : null,
                status,
                installmentsCount
        );
    }

    // ── Mappers ─────────────────────────────────────────────────────────────

    private TripSummaryDTO toSummaryDTO(Trip trip) {
        int assignedParticipantsCount = getAssignedParticipantsCount(trip.getId());
        return new TripSummaryDTO(
                trip.getId(),
                trip.getName(),
                trip.getTotalAmount(),
                trip.getFirstInstallmentAmount(),
                trip.getCurrency(),
                trip.getInstallmentsCount(),
                trip.getAssignedUsers().size(),
                assignedParticipantsCount
        );
    }

    private TripDetailDTO toDetailDTO(Trip trip) {
        int assignedParticipantsCount = getAssignedParticipantsCount(trip.getId());
        return new TripDetailDTO(
                trip.getId(),
                trip.getName(),
                trip.getTotalAmount(),
                trip.getFirstInstallmentAmount(),
                trip.getInstallmentsCount(),
                trip.getDueDay(),
                trip.getYellowWarningDays(),
                trip.getFixedFineAmount(),
                trip.getRetroactiveActive(),
                trip.getCurrency(),
                trip.getFirstDueDate(),
                trip.getAssignedUsers().size(),
                assignedParticipantsCount
        );
    }

    private SpreadsheetRowInstallmentDTO toSpreadsheetInstallmentDTO(
            Installment installment,
            PaymentReceipt latestReceipt,
            PaymentInstallmentOverlayService.InstallmentOverlay overlay
    ) {
        Trip trip = installment.getTrip();
        int yellowWarningDays = trip.getYellowWarningDays() == null ? 0 : trip.getYellowWarningDays();
        InstallmentStatus effectiveStatus = computeEffectiveStatus(
                installment.getStatus(),
                installment.getDueDate(),
                yellowWarningDays,
                installment.getPaidAmount(),
                installment.getTotalDue()
        );
        InstallmentUiStatus uiStatus = installmentUiStatusResolver.resolve(
                effectiveStatus,
                overlay != null ? overlay.status() : latestReceipt != null ? latestReceipt.getStatus() : null,
                installment.getDueDate(),
                yellowWarningDays,
                installment.getPaidAmount(),
                installment.getTotalDue()
        );

        return new SpreadsheetRowInstallmentDTO(
                installment.getId(),
                installment.getInstallmentNumber(),
                installment.getDueDate(),
                installment.getCapitalAmount(),
                installment.getRetroactiveAmount(),
                installment.getFineAmount(),
                installment.getTotalDue(),
                installment.getPaidAmount(),
                effectiveStatus,
                uiStatus.code(),
                uiStatus.label(),
                uiStatus.tone()
        );
    }

    private InstallmentStatus computeEffectiveStatus(
            InstallmentStatus storedStatus,
            LocalDate dueDate,
            int yellowWarningDays,
            BigDecimal paidAmount,
            BigDecimal totalDue
    ) {
        return installmentStatusResolver.computeEffective(storedStatus, dueDate, yellowWarningDays, paidAmount, totalDue);
    }

    private static String normalizeSortBy(String sortBy) {
        if ("date".equalsIgnoreCase(sortBy)) {
            return "date";
        }
        if ("parent".equalsIgnoreCase(sortBy)) {
            return "parent";
        }
        if ("student".equalsIgnoreCase(sortBy)) {
            return "student";
        }
        if ("email".equalsIgnoreCase(sortBy)) {
            return "email";
        }
        return "student";
    }

    private static String normalizeOrder(String order) {
        return "desc".equalsIgnoreCase(order) ? "desc" : "asc";
    }

    private int getAssignedParticipantsCount(Long tripId) {
        return tripId == null ? 0 : Math.toIntExact(installmentRepository.countDistinctStudentsByTripId(tripId));
    }

    private boolean hasNoRemainingBalance(Installment installment) {
        BigDecimal totalDue = installment.getTotalDue() == null ? BigDecimal.ZERO : installment.getTotalDue();
        BigDecimal paidAmount = installment.getPaidAmount() == null ? BigDecimal.ZERO : installment.getPaidAmount();
        return paidAmount.compareTo(totalDue) >= 0;
    }

    private static boolean rowMatchesSearch(SpreadsheetRowDTO row, String search) {
        if (search == null || search.isBlank()) {
            return true;
        }

        String haystack = String.join(
                " ",
                row.name() == null ? "" : row.name(),
                row.lastname() == null ? "" : row.lastname(),
                row.email() == null ? "" : row.email(),
                row.phone() == null ? "" : row.phone(),
                row.studentLastname() == null ? "" : row.studentLastname(),
                row.studentName() == null ? "" : row.studentName(),
                StudentNameFormatter.displayName(row.studentName(), row.studentLastname()) == null
                        ? ""
                        : StudentNameFormatter.displayName(row.studentName(), row.studentLastname()),
                row.studentDni() == null ? "" : row.studentDni()
        ).toLowerCase();

        return haystack.contains(search.trim().toLowerCase());
    }

    private static Comparator<SpreadsheetRowDTO> buildSpreadsheetComparator(String sortBy, String order) {
        Comparator<String> textComparator = Comparator.nullsLast(String.CASE_INSENSITIVE_ORDER);
        Comparator<SpreadsheetRowDTO> comparator;

        if ("parent".equals(sortBy)) {
            comparator = Comparator.comparing(SpreadsheetRowDTO::lastname, textComparator)
                    .thenComparing(SpreadsheetRowDTO::name, textComparator);
        } else if ("email".equals(sortBy)) {
            comparator = Comparator.comparing(SpreadsheetRowDTO::email, textComparator)
                    .thenComparing(SpreadsheetRowDTO::lastname, textComparator);
        } else if ("date".equals(sortBy)) {
            Comparator<LocalDate> dateComparator = Comparator.nullsLast(LocalDate::compareTo);
            comparator = Comparator.comparing(
                    row -> row.installments().stream()
                            .map(SpreadsheetRowInstallmentDTO::dueDate)
                            .filter(Objects::nonNull)
                            .min(LocalDate::compareTo)
                            .orElse(null),
                    dateComparator
            );
        } else {
            comparator = Comparator.comparing(TripService::studentSortLastname, textComparator)
                    .thenComparing(TripService::studentSortName, textComparator);
        }

        comparator = comparator
                .thenComparing(SpreadsheetRowDTO::lastname, textComparator)
                .thenComparing(SpreadsheetRowDTO::name, textComparator)
                .thenComparing(SpreadsheetRowDTO::studentLastname, textComparator)
                .thenComparing(SpreadsheetRowDTO::studentName, textComparator)
                .thenComparing(SpreadsheetRowDTO::studentDni, textComparator)
                .thenComparing(SpreadsheetRowDTO::userId, Comparator.nullsLast(Comparator.naturalOrder()))
                .thenComparing(SpreadsheetRowDTO::studentId, Comparator.nullsLast(Comparator.naturalOrder()));

        return "desc".equals(order) ? comparator.reversed() : comparator;
    }

    private SpreadsheetDTO getSpreadsheetUnpaged(Long tripId) {
        return buildSpreadsheet(tripId, 0, Integer.MAX_VALUE, null, "student", "asc", null, false);
    }

    private SpreadsheetDTO buildSpreadsheet(
            Long tripId,
            int page,
            int size,
            String search,
            String sortBy,
            String order,
            InstallmentStatus status,
            boolean enforceMaxPageSize
    ) {
        if (enforceMaxPageSize && size > 100) {
            throw new IllegalArgumentException("Page size cannot exceed 100");
        }

        Trip trip = tripRepository.findById(tripId)
                .orElseThrow(() -> new EntityNotFoundException("Trip not found with id " + tripId));

        String normalizedSortBy = normalizeSortBy(sortBy);
        String normalizedOrder = normalizeOrder(order);

        List<Installment> tripInstallments = installmentRepository.findByTripIdWithUsers(tripId);
        Map<SpreadsheetParticipantKey, List<Installment>> installmentsByParticipant = tripInstallments.isEmpty()
                ? Map.of()
                : tripInstallments.stream().collect(Collectors.groupingBy(
                        installment -> new SpreadsheetParticipantKey(
                                installment.getUser().getId(),
                                installment.getStudent() != null ? installment.getStudent().getId() : null
                        )
                ));

        List<Long> installmentIds = tripInstallments.stream()
                .map(Installment::getId)
                .toList();

        Map<Long, PaymentReceipt> latestReceiptByInstallmentId = installmentIds.isEmpty()
                ? Map.of()
                : paymentReceiptRepository.findByInstallmentIdIn(installmentIds).stream()
                .collect(Collectors.toMap(
                        receipt -> receipt.getInstallment().getId(),
                        Function.identity(),
                        (existing, ignored) -> existing
                ));
        Map<Long, PaymentInstallmentOverlayService.InstallmentOverlay> overlays =
                paymentInstallmentOverlayService.resolveForInstallments(tripInstallments);

        List<SpreadsheetRowDTO> rows = installmentsByParticipant.values().stream()
                .map(participantInstallments -> {
                    Installment firstInstallment = participantInstallments.get(0);
                    User user = firstInstallment.getUser();
                    Student student = firstInstallment.getStudent();

                    boolean userCompleted = participantInstallments.stream()
                            .allMatch(this::hasNoRemainingBalance);

                    List<SpreadsheetRowInstallmentDTO> rowInstallments = participantInstallments.stream()
                            .sorted(Comparator.comparing(Installment::getInstallmentNumber))
                            .map(installment -> toSpreadsheetInstallmentDTO(
                                    installment,
                                    latestReceiptByInstallmentId.get(installment.getId()),
                                    overlays.get(installment.getId())
                            ))
                            .toList();

                    return new SpreadsheetRowDTO(
                            user.getId(),
                            student != null ? student.getId() : null,
                            normalizeResponsibleName(user.getName()),
                            normalizeResponsibleName(user.getLastname()),
                            user.getPhone(),
                            user.getEmail(),
                            student != null ? student.getLastname() : null,
                            student != null ? student.getName() : null,
                            student != null ? student.getDni() : null,
                            userCompleted,
                            rowInstallments
                    );
                })
                .filter(row -> rowMatchesSearch(row, search))
                .filter(row -> status == null || row.installments().stream().anyMatch(i -> i.status() == status))
                .sorted(buildSpreadsheetComparator(normalizedSortBy, normalizedOrder))
                .toList();

        long totalElements = rows.size();
        int safePage = Math.max(0, page);
        int safeSize;
        int offset;
        if (enforceMaxPageSize) {
            safeSize = Math.max(1, size);
            offset = safePage * safeSize;
        } else {
            safeSize = totalElements > Integer.MAX_VALUE ? Integer.MAX_VALUE : Math.max(1, (int) totalElements);
            offset = 0;
        }

        List<SpreadsheetRowDTO> pagedRows = rows.stream()
                .skip(offset)
                .limit(safeSize)
                .toList();

        return new SpreadsheetDTO(
                trip.getName(),
                trip.getInstallmentsCount(),
                safePage,
                totalElements,
                pagedRows
        );
    }

    private static String studentSortLastname(SpreadsheetRowDTO row) {
        if (row == null) {
            return null;
        }
        if (row.studentLastname() == null || row.studentLastname().isBlank()
                || row.studentName() == null || row.studentName().isBlank()) {
            return row.lastname();
        }
        return row.studentLastname();
    }

    private static String studentSortName(SpreadsheetRowDTO row) {
        if (row == null) {
            return null;
        }
        if (row.studentLastname() == null || row.studentLastname().isBlank()
                || row.studentName() == null || row.studentName().isBlank()) {
            return row.name();
        }
        return row.studentName();
    }

    private static String formatResponsibleFullName(User user) {
        String fullname = List.of(
                        normalizeResponsibleName(user.getName()),
                        normalizeResponsibleName(user.getLastname())
                ).stream()
                .filter(part -> part != null && !part.isBlank())
                .collect(Collectors.joining(" "))
                .trim();
        return fullname.isBlank() ? null : fullname;
    }

    private static String normalizeResponsibleName(String value) {
        if (value == null) {
            return null;
        }

        String trimmed = value.trim();
        if (trimmed.isEmpty()) {
            return null;
        }

        return trimmed.toUpperCase(Locale.ROOT);
    }
}
