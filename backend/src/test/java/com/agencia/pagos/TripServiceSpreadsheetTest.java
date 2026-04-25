package com.agencia.pagos;

import com.agencia.pagos.dtos.response.SpreadsheetDTO;
import com.agencia.pagos.entities.Currency;
import com.agencia.pagos.entities.Installment;
import com.agencia.pagos.entities.InstallmentStatus;
import com.agencia.pagos.entities.Student;
import com.agencia.pagos.entities.Trip;
import com.agencia.pagos.entities.user.User;
import com.agencia.pagos.repositories.InstallmentRepository;
import com.agencia.pagos.repositories.TripRepository;
import com.agencia.pagos.repositories.UserRepository;
import com.agencia.pagos.services.TripService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.LocalDate;
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
    private InstallmentRepository installmentRepository;

    private TripService tripService;

    @BeforeEach
    void setUp() {
        tripService = new TripService(tripRepository, userRepository, installmentRepository);
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
        Installment installment = new Installment();
        installment.setId(id);
        installment.setTrip(trip);
        installment.setUser(user);
        installment.setStudent(student);
        installment.setInstallmentNumber(installmentNumber);
        installment.setDueDate(LocalDate.of(2026, 5, 10));
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
