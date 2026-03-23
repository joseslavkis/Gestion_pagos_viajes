package com.agencia.pagos.services;

import com.agencia.pagos.dtos.request.TripCreateDTO;
import com.agencia.pagos.dtos.request.TripUpdateDTO;
import com.agencia.pagos.dtos.request.UserAssignBulkDTO;
import com.agencia.pagos.dtos.response.BulkAssignResultDTO;
import com.agencia.pagos.dtos.response.SpreadsheetDTO;
import com.agencia.pagos.dtos.response.SpreadsheetRowDTO;
import com.agencia.pagos.dtos.response.SpreadsheetRowInstallmentDTO;
import com.agencia.pagos.dtos.response.TripDetailDTO;
import com.agencia.pagos.dtos.response.TripSummaryDTO;
import com.agencia.pagos.entities.Currency;
import com.agencia.pagos.entities.Installment;
import com.agencia.pagos.entities.InstallmentStatus;
import com.agencia.pagos.entities.PaymentReceipt;
import com.agencia.pagos.entities.Student;
import com.agencia.pagos.entities.Trip;
import com.agencia.pagos.entities.user.User;
import com.agencia.pagos.repositories.InstallmentRepository;
import com.agencia.pagos.repositories.InstallmentReminderNotificationRepository;
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
import java.util.HashSet;
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

        if (studentRepository == null) {
            throw new IllegalStateException("StudentRepository is not available");
        }

        List<String> requestedDnis = dto.studentDnis().stream()
                .map(String::trim)
                .toList();

        Map<String, Student> studentsByDni = studentRepository.findByDniIn(requestedDnis).stream()
                .collect(Collectors.toMap(Student::getDni, Function.identity()));

        for (String requestedDni : requestedDnis) {
            if (!studentsByDni.containsKey(requestedDni)) {
                throw new EntityNotFoundException("Alumno no encontrado con DNI: " + requestedDni);
            }
        }

        Set<Long> alreadyAssignedParentIds = trip.getAssignedUsers().stream()
                .map(User::getId)
                .collect(Collectors.toSet());
        Set<Long> alreadyAssignedStudentIds = new HashSet<>(installmentRepository.findAssignedStudentIdsByTripId(tripId));

        List<Student> studentsToAssign = requestedDnis.stream()
                .map(studentsByDni::get)
                .filter(student -> !alreadyAssignedStudentIds.contains(student.getId()))
                .toList();

        if (studentsToAssign.isEmpty()) {
            return new BulkAssignResultDTO("success", "All students were already assigned", 0);
        }

        List<BigDecimal> amounts = splitAmount(trip.getTotalAmount(), trip.getInstallmentsCount());

        List<Installment> installmentsToSave = new ArrayList<>();
        LocalDate now = LocalDate.now(BUSINESS_ZONE);

        for (Student student : studentsToAssign) {
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

                if (!currentDueDate.isBefore(now)) {
                    Installment installment = new Installment();
                    installment.setTrip(trip);
                    installment.setUser(user);
                    installment.setStudent(student);
                    installment.setInstallmentNumber(i);
                    installment.setDueDate(currentDueDate);
                    installment.setCapitalAmount(installmentCapital);
                    installment.setRetroactiveAmount(BigDecimal.ZERO);
                    installment.setFineAmount(BigDecimal.ZERO);
                    installment.setStatus(InstallmentStatus.YELLOW);
                    installment.recalculateTotalDue();
                    installmentsToSave.add(installment);
                } else if (trip.getRetroactiveActive()) {
                    Installment retroInstallment = new Installment();
                    retroInstallment.setTrip(trip);
                    retroInstallment.setUser(user);
                    retroInstallment.setStudent(student);
                    retroInstallment.setInstallmentNumber(i);
                    retroInstallment.setDueDate(currentDueDate);
                    retroInstallment.setCapitalAmount(installmentCapital);
                    retroInstallment.setRetroactiveAmount(BigDecimal.ZERO);
                    retroInstallment.setFineAmount(BigDecimal.ZERO);
                    retroInstallment.setStatus(InstallmentStatus.RETROACTIVE);
                    retroInstallment.recalculateTotalDue();
                    installmentsToSave.add(retroInstallment);
                } else {
                    Installment installment = new Installment();
                    installment.setTrip(trip);
                    installment.setUser(user);
                    installment.setStudent(student);
                    installment.setInstallmentNumber(i);
                    installment.setDueDate(currentDueDate);
                    installment.setCapitalAmount(installmentCapital);
                    installment.setRetroactiveAmount(BigDecimal.ZERO);
                    installment.setFineAmount(trip.getFixedFineAmount());
                    installment.setStatus(InstallmentStatus.RED);
                    installment.recalculateTotalDue();
                    installmentsToSave.add(installment);
                }
            }
        }

        installmentRepository.saveAll(installmentsToSave);
        tripRepository.save(trip);

        return new BulkAssignResultDTO("success", "Students assigned successfully", studentsToAssign.size());
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
