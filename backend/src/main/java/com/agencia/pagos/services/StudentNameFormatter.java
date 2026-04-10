package com.agencia.pagos.services;

import com.agencia.pagos.entities.Student;

final class StudentNameFormatter {

    private StudentNameFormatter() {
    }

    static String displayName(Student student) {
        if (student == null) {
            return null;
        }
        return displayName(student.getName(), student.getLastname());
    }

    static String displayName(String name, String lastname) {
        String safeName = normalize(name);
        String safeLastname = normalize(lastname);

        if (safeName == null && safeLastname == null) {
            return null;
        }
        if (safeName == null) {
            return safeLastname;
        }
        if (safeLastname == null) {
            return safeName;
        }
        return safeName + " " + safeLastname;
    }

    private static String normalize(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isBlank() ? null : trimmed;
    }
}
