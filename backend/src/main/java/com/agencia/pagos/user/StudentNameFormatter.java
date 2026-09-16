package com.agencia.pagos.user;

import com.agencia.pagos.user.Student;

public final class StudentNameFormatter {

    private StudentNameFormatter() {
    }

    public static String displayName(Student student) {
        if (student == null) {
            return null;
        }
        return displayName(student.getName(), student.getLastname());
    }

    public static String displayName(String name, String lastname) {
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
