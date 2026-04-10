package com.agencia.pagos;

import com.agencia.pagos.dtos.request.StudentCreateDTO;
import com.agencia.pagos.dtos.response.StudentDTO;
import com.agencia.pagos.entities.Student;
import org.junit.jupiter.api.Test;

import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.assertTrue;

class StudentContractsTest {

    @Test
    void studentEntity_exposesLastnameField() {
        boolean hasLastnameField = Arrays.stream(Student.class.getDeclaredFields())
                .anyMatch(field -> "lastname".equals(field.getName()));

        assertTrue(hasLastnameField, "Student debe persistir lastname para exportar por alumno");
    }

    @Test
    void studentCreateDto_requiresLastnameComponent() {
        boolean hasLastnameComponent = Arrays.stream(StudentCreateDTO.class.getRecordComponents())
                .anyMatch(component -> "lastname".equals(component.getName()));

        assertTrue(hasLastnameComponent, "StudentCreateDTO debe requerir lastname");
    }

    @Test
    void studentDto_returnsLastnameComponent() {
        boolean hasLastnameComponent = Arrays.stream(StudentDTO.class.getRecordComponents())
                .anyMatch(component -> "lastname".equals(component.getName()));

        assertTrue(hasLastnameComponent, "StudentDTO debe devolver lastname");
    }
}
