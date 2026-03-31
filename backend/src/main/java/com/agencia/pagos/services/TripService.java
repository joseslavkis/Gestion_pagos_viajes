package com.agencia.pagos.services;

import com.agencia.pagos.dtos.request.TripCreateDTO;
import com.agencia.pagos.dtos.request.TripUpdateDTO;
import com.agencia.pagos.dtos.request.UserAssignBulkDTO;
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
import com.agencia.pagos.entities.PaymentReceipt;
import com.agencia.pagos.entities.Student;
import com.agencia.pagos.entities.Trip;
import com.agencia.pagos.entities.user.User;
import com.agencia.pagos.repositories.InstallmentRepository;
import com.agencia.pagos.repositories.InstallmentReminderNotificationRepository;
import com.agencia.pagos.repositories.PendingTripStudentRepository;
import com.agencia.pagos.repositories.PaymentReceiptRepository;
import com.agencia.pagos.repositories.StudentRepository;
import com.agencia.pagos.repositories.TripRepository;
import com.agencia.pagos.repositories.UserRepository;
import jakarta.persistence.EntityNotFoundException;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

@Service
@Transactional
public class TripService {

    private record SpreadsheetParticipantKey(Long userId, Long studentId) {}

    // [A-1] Use Argentina's business timezone for all "today" comparisons
    private static final ZoneId BUSINESS_ZONE = ZoneId.of("America/Argentina/Buenos_Aires");

    private final TripRepository tripRepository;
    private final UserRepository userRepository;
    private final StudentRepository studentRepository;
    private final InstallmentRepository installmentRepository;
    private final PaymentReceiptRepository paymentReceiptRepository;
    private final InstallmentReminderNotificationRepository installmentReminderNotificationRepository;
    private final PendingTripStudentRepository pendingTripStudentRepository;
    private final InstallmentStatusResolver installmentStatusResolver;
    private final InstallmentUiStatusResolver installmentUiStatusResolver;
    private final TripExcelExporter tripExcelExporter;

    @Autowired
    public TripService(
            TripRepository tripRepository,
            UserRepository userRepository,
            StudentRepository studentRepository,
            InstallmentRepository installmentRepository,
            PaymentReceiptRepository paymentReceiptRepository,
            InstallmentReminderNotificationRepository installmentReminderNotificationRepository,
            PendingTripStudentRepository pendingTripStudentRepository,
            InstallmentStatusResolver installmentStatusResolver,
            InstallmentUiStatusResolver installmentUiStatusResolver,
            TripExcelExporter tripExcelExporter
    ) {
        this.tripRepository = tripRepository;
        this.userRepository = userRepository;
        this.studentRepository = studentRepository;
        this.installmentRepository = installmentRepository;
        this.paymentReceiptRepository = paymentReceiptRepository;
        this.installmentReminderNotificationRepository = installmentReminderNotificationRepository;
        this.pendingTripStudentRepository = pendingTripStudentRepository;
        this.installmentStatusResolver = installmentStatusResolver;
        this.installmentUiStatusResolver = installmentUiStatusResolver;
        this.tripExcelExporter = tripExcelExporter;
    }

    // Backward-compatible constructor for tests that still instantiate TripService with 3 args.
    public TripService(
            TripRepository tripRepository,
            UserRepository userRepository,
            InstallmentRepository installmentRepository
    ) {
        this(
                tripRepository,
                userRepository,
                null,
                installmentRepository,
                null,
                null,
                null,
                new InstallmentStatusResolver(),
                new InstallmentUiStatusResolver(),
                new TripExcelExporter()
        );
    }

    public TripDetailDTO createTrip(TripCreateDTO dto) {
        Trip trip = new Trip();
        trip.setName(dto.name());
        trip.setTotalAmount(dto.totalAmount());
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
        Trip trip = tripRepository.findByIdWithUsers(id)
                .orElseThrow(() -> new EntityNotFoundException("Trip not found with id " + id));

        // [A-3] Prevent firstDueDate changes once users are assigned — would invalidate all generated quotas
        if (dto.firstDueDate() != null && !trip.getAssignedUsers().isEmpty()) {
            throw new IllegalStateException(
                "Cannot modify firstDueDate on a trip that already has assigned users.");
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

    public void deleteTrip(Long id) {
        Trip trip = tripRepository.findByIdWithUsers(id)
                .orElseThrow(() -> new EntityNotFoundException("Trip not found with id " + id));

        if (paymentReceiptRepository != null) {
            paymentReceiptRepository.deleteByInstallmentTripId(trip.getId());
        }
        if (installmentReminderNotificationRepository != null) {
            installmentReminderNotificationRepository.deleteByInstallmentTripId(trip.getId());
        }
        if (pendingTripStudentRepository != null) {
            pendingTripStudentRepository.deleteByTripId(trip.getId());
        }
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
        return tripExcelExporter.export(data, trip.getCurrency().name());
    }

    // [C-2, A-1] Uses pessimistic lock + Argentina timezone
    public BulkAssignResultDTO assignUsersInBulk(Long tripId, UserAssignBulkDTO dto) {
        Trip trip = tripRepository.findByIdForUpdate(tripId)
                .orElseThrow(() -> new EntityNotFoundException("Trip not found"));

        if (studentRepository == null || pendingTripStudentRepository == null) {
            throw new IllegalStateException("Student repositories are not available");
        }

        List<String> requestedDnis = dto.studentDnis().stream()
                .map(String::trim)
                .toList();

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

        List<BigDecimal> amounts = splitAmount(trip.getTotalAmount(), trip.getInstallmentsCount());
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
                            item.schoolName(),
                            item.courseName(),
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
        Trip trip = tripRepository.findByIdForUpdate(tripId)
                .orElseThrow(() -> new EntityNotFoundException("Trip not found"));

        String normalizedDni = studentDni == null ? "" : studentDni.trim();
        List<PendingTripStudent> pendingStudents = pendingTripStudentRepository.findByTripIdAndStudentDni(tripId, normalizedDni);
        List<Installment> installments = installmentRepository.findByTripIdAndStudentDni(tripId, normalizedDni);

        if (pendingStudents.isEmpty() && installments.isEmpty()) {
            throw new EntityNotFoundException("No existe una asignación para el DNI " + normalizedDni + " en este viaje.");
        }

        if (!pendingStudents.isEmpty()) {
            pendingTripStudentRepository.deleteAll(pendingStudents);
        }

        if (installments.isEmpty()) {
            return;
        }

        List<Long> installmentIds = installments.stream()
                .map(Installment::getId)
                .toList();

        if (installmentReminderNotificationRepository != null && !installmentIds.isEmpty()) {
            installmentReminderNotificationRepository.deleteByInstallmentIdIn(installmentIds);
        }
        if (paymentReceiptRepository != null && !installmentIds.isEmpty()) {
            paymentReceiptRepository.deleteByInstallmentIdIn(installmentIds);
        }

        User parent = installments.get(0).getUser();
        installmentRepository.deleteAll(installments);

        if (parent != null && !installmentRepository.existsByTripIdAndUserId(tripId, parent.getId())) {
            trip.getAssignedUsers().removeIf(user -> user.getId().equals(parent.getId()));
            tripRepository.save(trip);
        }
    }

    public void materializePendingAssignmentsForStudent(Student student) {
        if (student == null) {
            throw new IllegalArgumentException("Student is required");
        }
        if (student.getId() == null) {
            throw new IllegalArgumentException("Student must be persisted before assigning trips");
        }
        if (pendingTripStudentRepository == null) {
            throw new IllegalStateException("PendingTripStudentRepository is not available");
        }

        List<PendingTripStudent> pendingTrips = pendingTripStudentRepository.findByStudentDniWithTripForUpdate(student.getDni());
        if (pendingTrips.isEmpty()) {
            return;
        }

        LocalDate now = LocalDate.now(BUSINESS_ZONE);
        List<Installment> installmentsToSave = new ArrayList<>();
        Map<Long, Trip> lockedTripsById = new HashMap<>();

        for (PendingTripStudent pendingTripStudent : pendingTrips) {
            Trip trip = lockedTripsById.computeIfAbsent(
                    pendingTripStudent.getTrip().getId(),
                    tripId -> tripRepository.findByIdForUpdate(tripId)
                            .orElseThrow(() -> new EntityNotFoundException("Trip not found"))
            );
            List<BigDecimal> amounts = splitAmount(trip.getTotalAmount(), trip.getInstallmentsCount());
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

    /**
     * [C-1] Splits a total amount into {@code count} installments so that no
     * cent is lost. All installments receive the truncated base value; the
     * last one absorbs the remaining cents.
     */
    /* package-private for testing */
    List<BigDecimal> splitAmount(BigDecimal total, int count) {
        BigDecimal base = total.divide(BigDecimal.valueOf(count), 2, RoundingMode.DOWN);
        BigDecimal distributed = base.multiply(BigDecimal.valueOf(count));
        BigDecimal remainder = total.subtract(distributed);
        List<BigDecimal> amounts = new ArrayList<>(Collections.nCopies(count, base));
        amounts.set(count - 1, base.add(remainder));
        return amounts;
    }

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
            LocalDate rawDueDate = trip.getFirstDueDate().plusMonths(i - 1);
            int validDay = Math.min(trip.getDueDay(), rawDueDate.lengthOfMonth());
            LocalDate currentDueDate = rawDueDate.withDayOfMonth(validDay);
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
                : (parent.getName() + " " + parent.getLastname()).trim();
        return new TripStudentAdminDTO(
                student.getDni(),
                student.getId(),
                student.getName(),
                student.getSchoolName(),
                student.getCourseName(),
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
            PaymentReceipt latestReceipt
    ) {
        Trip trip = installment.getTrip();
        int yellowWarningDays = trip.getYellowWarningDays() == null ? 0 : trip.getYellowWarningDays();
        InstallmentStatus effectiveStatus = computeEffectiveStatus(
                installment.getStatus(),
                installment.getDueDate(),
                yellowWarningDays
        );
        InstallmentUiStatus uiStatus = installmentUiStatusResolver.resolve(
                effectiveStatus,
                latestReceipt != null ? latestReceipt.getStatus() : null,
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
            int yellowWarningDays
    ) {
        return installmentStatusResolver.computeEffective(storedStatus, dueDate, yellowWarningDays);
    }

    private static String normalizeSortBy(String sortBy) {
        if ("name".equalsIgnoreCase(sortBy)) {
            return "name";
        }
        if ("email".equalsIgnoreCase(sortBy)) {
            return "email";
        }
        return "lastname";
    }

    private static String normalizeOrder(String order) {
        return "desc".equalsIgnoreCase(order) ? "desc" : "asc";
    }

    private int getAssignedParticipantsCount(Long tripId) {
        return tripId == null ? 0 : Math.toIntExact(installmentRepository.countDistinctStudentsByTripId(tripId));
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
                row.studentName() == null ? "" : row.studentName(),
                row.studentDni() == null ? "" : row.studentDni(),
                row.schoolName() == null ? "" : row.schoolName(),
                row.courseName() == null ? "" : row.courseName()
        ).toLowerCase();

        return haystack.contains(search.trim().toLowerCase());
    }

    private static Comparator<SpreadsheetRowDTO> buildSpreadsheetComparator(String sortBy, String order) {
        Comparator<String> textComparator = Comparator.nullsLast(String.CASE_INSENSITIVE_ORDER);
        Comparator<SpreadsheetRowDTO> comparator;

        if ("name".equals(sortBy)) {
            comparator = Comparator.comparing(SpreadsheetRowDTO::name, textComparator)
                    .thenComparing(SpreadsheetRowDTO::lastname, textComparator);
        } else if ("email".equals(sortBy)) {
            comparator = Comparator.comparing(SpreadsheetRowDTO::email, textComparator)
                    .thenComparing(SpreadsheetRowDTO::lastname, textComparator);
        } else {
            comparator = Comparator.comparing(SpreadsheetRowDTO::lastname, textComparator)
                    .thenComparing(SpreadsheetRowDTO::name, textComparator);
        }

        comparator = comparator
                .thenComparing(SpreadsheetRowDTO::studentName, textComparator)
                .thenComparing(SpreadsheetRowDTO::studentDni, textComparator)
                .thenComparing(SpreadsheetRowDTO::userId, Comparator.nullsLast(Comparator.naturalOrder()))
                .thenComparing(SpreadsheetRowDTO::studentId, Comparator.nullsLast(Comparator.naturalOrder()));

        return "desc".equals(order) ? comparator.reversed() : comparator;
    }

    private SpreadsheetDTO getSpreadsheetUnpaged(Long tripId) {
        return buildSpreadsheet(tripId, 0, Integer.MAX_VALUE, null, "lastname", "asc", null, false);
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

        Map<Long, PaymentReceipt> latestReceiptByInstallmentId = installmentIds.isEmpty() || paymentReceiptRepository == null
                ? Map.of()
                : paymentReceiptRepository.findByInstallmentIdIn(installmentIds).stream()
                .collect(Collectors.toMap(
                        receipt -> receipt.getInstallment().getId(),
                        Function.identity(),
                        (existing, ignored) -> existing
                ));

        List<SpreadsheetRowDTO> rows = installmentsByParticipant.values().stream()
                .map(participantInstallments -> {
                    Installment firstInstallment = participantInstallments.get(0);
                    User user = firstInstallment.getUser();
                    Student student = firstInstallment.getStudent();

                    boolean userCompleted = participantInstallments.stream()
                            .allMatch(i -> i.getStatus() == InstallmentStatus.GREEN);

                    List<SpreadsheetRowInstallmentDTO> rowInstallments = participantInstallments.stream()
                            .sorted(Comparator.comparing(Installment::getInstallmentNumber))
                            .map(installment -> toSpreadsheetInstallmentDTO(
                                    installment,
                                    latestReceiptByInstallmentId.get(installment.getId())
                            ))
                            .toList();

                    return new SpreadsheetRowDTO(
                            user.getId(),
                            student != null ? student.getId() : null,
                            user.getName(),
                            user.getLastname(),
                            user.getPhone(),
                            user.getEmail(),
                            student != null ? student.getName() : null,
                            student != null ? student.getDni() : null,
                            student != null ? student.getSchoolName() : null,
                            student != null ? student.getCourseName() : null,
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
}
