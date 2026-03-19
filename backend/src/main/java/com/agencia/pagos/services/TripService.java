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
import com.agencia.pagos.entities.Installment;
import com.agencia.pagos.entities.InstallmentStatus;
import com.agencia.pagos.entities.Trip;
import com.agencia.pagos.entities.user.User;
import com.agencia.pagos.repositories.InstallmentRepository;
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
import java.util.List;
import java.util.Map;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Collectors;

@Service
@Transactional
public class TripService {

    // [A-1] Use Argentina's business timezone for all "today" comparisons
    private static final ZoneId BUSINESS_ZONE = ZoneId.of("America/Argentina/Buenos_Aires");

    private final TripRepository tripRepository;
    private final UserRepository userRepository;
    private final InstallmentRepository installmentRepository;

    @Autowired
    public TripService(TripRepository tripRepository, UserRepository userRepository, InstallmentRepository installmentRepository) {
        this.tripRepository = tripRepository;
        this.userRepository = userRepository;
        this.installmentRepository = installmentRepository;
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

        if (!trip.getAssignedUsers().isEmpty()) {
            throw new IllegalStateException("Cannot delete a trip with assigned users");
        }

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
        Trip trip = tripRepository.findByIdWithUsers(tripId)
                .orElseThrow(() -> new EntityNotFoundException("Trip not found with id " + tripId));

        List<Installment> installments = installmentRepository.findByTripIdWithUsers(tripId);

        Map<Long, List<Installment>> installmentsByUserId = installments.stream()
                .collect(Collectors.groupingBy(i -> i.getUser().getId()));

        List<SpreadsheetRowDTO> rows = trip.getAssignedUsers().stream()
                .map(user -> {
                    List<SpreadsheetRowInstallmentDTO> rowInstallments = installmentsByUserId
                            .getOrDefault(user.getId(), List.of())
                            .stream()
                            .sorted(Comparator.comparing(Installment::getInstallmentNumber))
                            .map(this::toSpreadsheetInstallmentDTO)
                            .toList();

                    return new SpreadsheetRowDTO(
                            user.getId(),
                            user.getName(),
                            user.getLastname(),
                            user.getPhone(),
                            user.getEmail(),
                            user.getStudentName(),
                            user.getSchoolName(),
                            user.getCourseName(),
                            rowInstallments
                    );
                })
                .toList();

        String normalizedSearch = search == null ? "" : search.trim().toLowerCase(Locale.ROOT);
        if (!normalizedSearch.isEmpty()) {
            rows = rows.stream()
                    .filter(row -> containsIgnoreCase(row.name(), normalizedSearch)
                            || containsIgnoreCase(row.lastname(), normalizedSearch)
                            || containsIgnoreCase(row.email(), normalizedSearch))
                    .toList();
        }

        if (status != null) {
            InstallmentStatus filterStatus = status;
            rows = rows.stream()
                    .filter(row -> row.installments().stream().anyMatch(i -> i.status() == filterStatus))
                    .toList();
        }

        Comparator<SpreadsheetRowDTO> comparator;
        if ("name".equalsIgnoreCase(sortBy)) {
            comparator = Comparator.comparing(row -> safeLower(row.name()));
        } else if ("email".equalsIgnoreCase(sortBy)) {
            comparator = Comparator.comparing(row -> safeLower(row.email()));
        } else {
            comparator = Comparator.comparing(row -> safeLower(row.lastname()));
        }

        if ("desc".equalsIgnoreCase(order)) {
            comparator = comparator.reversed();
        }
        rows = rows.stream().sorted(comparator).toList();

        int safeSize = Math.max(1, size);
        int safePage = Math.max(0, page);
        int fromIndex = Math.min(safePage * safeSize, rows.size());
        int toIndex = Math.min(fromIndex + safeSize, rows.size());
        List<SpreadsheetRowDTO> paginatedRows = rows.subList(fromIndex, toIndex);

        return new SpreadsheetDTO(
                trip.getName(),
                trip.getInstallmentsCount(),
                safePage,
                (long) rows.size(),
                paginatedRows
        );
    }

    // [C-2, A-1] Uses pessimistic lock + Argentina timezone
    public BulkAssignResultDTO assignUsersInBulk(Long tripId, UserAssignBulkDTO dto) {
        // [C-2] Pessimistic lock to avoid race conditions on concurrent bulk assignments
        Trip trip = tripRepository.findByIdForUpdate(tripId)
                .orElseThrow(() -> new EntityNotFoundException("Trip not found"));

        // [A-3] Force initialization of lazy collection natively within the transaction
        trip.getAssignedUsers().size();

        // [Eje-3] Batch load: recover all users in a single WHERE id IN (...) query
        Set<Long> alreadyAssignedIds = trip.getAssignedUsers().stream()
                .map(User::getId)
                .collect(Collectors.toSet());

        List<Long> candidateIds = dto.userIds().stream()
                .filter(id -> !alreadyAssignedIds.contains(id))
                .collect(Collectors.toList());

        if (candidateIds.isEmpty()) {
            return new BulkAssignResultDTO("success", "All users were already assigned", 0);
        }

        Map<Long, User> foundUsersById = userRepository.findAllByIdIn(candidateIds).stream()
                .collect(Collectors.toMap(User::getId, u -> u));

        // Validate that every requested user exists
        for (Long requestedId : candidateIds) {
            if (!foundUsersById.containsKey(requestedId)) {
                throw new EntityNotFoundException("User not found: " + requestedId);
            }
        }

        List<User> newUsers = new ArrayList<>(foundUsersById.values());

        // [C-1] Distribute total amount to avoid losing cents (last installment absorbs remainder)
        List<BigDecimal> amounts = splitAmount(trip.getTotalAmount(), trip.getInstallmentsCount());

        List<Installment> installmentsToSave = new ArrayList<>();
        // [A-1] Use Argentina timezone for "today"
        LocalDate now = LocalDate.now(BUSINESS_ZONE);

        for (User user : newUsers) {
            trip.getAssignedUsers().add(user);
            BigDecimal acumuladoRetroactivo = BigDecimal.ZERO;

            for (int i = 1; i <= trip.getInstallmentsCount(); i++) {
                LocalDate rawDueDate = trip.getFirstDueDate().plusMonths(i - 1);
                int validDay = Math.min(trip.getDueDay(), rawDueDate.lengthOfMonth());
                LocalDate currentDueDate = rawDueDate.withDayOfMonth(validDay);

                // [C-1] Per-installment amount from the pre-split list
                BigDecimal installmentCapital = amounts.get(i - 1);

                if (!currentDueDate.isBefore(now)) {
                    // ── Cuota presente o futura ────────────────────────────────────────────
                    Installment installment = new Installment();
                    installment.setTrip(trip);
                    installment.setUser(user);
                    installment.setInstallmentNumber(i);
                    installment.setDueDate(currentDueDate);
                    installment.setCapitalAmount(installmentCapital);
                    installment.setRetroactiveAmount(acumuladoRetroactivo);
                    installment.setFineAmount(BigDecimal.ZERO);
                    installment.setStatus(InstallmentStatus.YELLOW);
                    // [A-2] Explicit call so totalDue is correct before batch save
                    installment.recalculateTotalDue();
                    installmentsToSave.add(installment);
                    // Reset accumulator — only the first future quota receives the retroactive amount
                    acumuladoRetroactivo = BigDecimal.ZERO;
                } else if (trip.getRetroactiveActive()) {
                    // ── [Eje-1] Retroactividad activa: persistir la cuota pasada con status RETROACTIVE
                    // para trazabilidad completa del historial, y acumular su capital en la primera futura.
                    Installment retroInstallment = new Installment();
                    retroInstallment.setTrip(trip);
                    retroInstallment.setUser(user);
                    retroInstallment.setInstallmentNumber(i);
                    retroInstallment.setDueDate(currentDueDate);
                    retroInstallment.setCapitalAmount(installmentCapital);
                    retroInstallment.setRetroactiveAmount(BigDecimal.ZERO);
                    retroInstallment.setFineAmount(BigDecimal.ZERO);
                    retroInstallment.setStatus(InstallmentStatus.RETROACTIVE);
                    retroInstallment.recalculateTotalDue();
                    installmentsToSave.add(retroInstallment);
                    // Accumulate for the first upcoming installment
                    acumuladoRetroactivo = acumuladoRetroactivo.add(installmentCapital);
                } else {
                    // ── Retroactividad inactiva: persistir la cuota pasada en RED con multa ──
                    Installment installment = new Installment();
                    installment.setTrip(trip);
                    installment.setUser(user);
                    installment.setInstallmentNumber(i);
                    installment.setDueDate(currentDueDate);
                    installment.setCapitalAmount(installmentCapital);
                    installment.setRetroactiveAmount(BigDecimal.ZERO);
                    installment.setFineAmount(trip.getFixedFineAmount());
                    installment.setStatus(InstallmentStatus.RED);
                    // [A-2] Explicit call so totalDue is correct before batch save
                    installment.recalculateTotalDue();
                    installmentsToSave.add(installment);
                }
            }
        }

        installmentRepository.saveAll(installmentsToSave);
        tripRepository.save(trip);

        // [M-2] Return strongly-typed DTO instead of raw Map
        return new BulkAssignResultDTO("success", "Users assigned successfully", newUsers.size());
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
        return new TripSummaryDTO(
                trip.getId(),
                trip.getName(),
                trip.getTotalAmount(),
                trip.getInstallmentsCount(),
                trip.getAssignedUsers().size()
        );
    }

    private TripDetailDTO toDetailDTO(Trip trip) {
        return new TripDetailDTO(
                trip.getId(),
                trip.getName(),
                trip.getTotalAmount(),
                trip.getInstallmentsCount(),
                trip.getDueDay(),
                trip.getYellowWarningDays(),
                trip.getFixedFineAmount(),
                trip.getRetroactiveActive(),
                trip.getFirstDueDate(),
                trip.getAssignedUsers().size()
        );
    }

    private SpreadsheetRowInstallmentDTO toSpreadsheetInstallmentDTO(Installment installment) {
        return new SpreadsheetRowInstallmentDTO(
                installment.getId(),
                installment.getInstallmentNumber(),
                installment.getDueDate(),
                installment.getCapitalAmount(),
                installment.getRetroactiveAmount(),
                installment.getFineAmount(),
                installment.getTotalDue(),
                installment.getStatus()
        );
    }

    private static boolean containsIgnoreCase(String value, String search) {
        return value != null && value.toLowerCase(Locale.ROOT).contains(search);
    }

    private static String safeLower(String value) {
        return value == null ? "" : value.toLowerCase(Locale.ROOT);
    }
}
