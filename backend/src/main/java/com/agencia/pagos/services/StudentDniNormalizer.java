package com.agencia.pagos.services;

import java.util.regex.Pattern;

public final class StudentDniNormalizer {

    private static final Pattern CANONICAL_DNI_PATTERN = Pattern.compile("^\\d{7,8}$");
    private static final Pattern SEPARATORS_PATTERN = Pattern.compile("[.\\-\\s]");
    private static final String INVALID_DNI_MESSAGE =
            "El DNI de alumno debe tener 7 u 8 números (se permiten puntos, guiones y espacios).";

    private StudentDniNormalizer() {
    }

    public static String normalize(String rawDni) {
        if (rawDni == null) {
            return "";
        }
        return SEPARATORS_PATTERN.matcher(rawDni.trim()).replaceAll("");
    }

    public static String normalizeAndValidate(String rawDni) {
        String normalized = normalize(rawDni);
        if (!isCanonical(normalized)) {
            throw new IllegalArgumentException(INVALID_DNI_MESSAGE);
        }
        return normalized;
    }

    public static boolean isCanonical(String dni) {
        return dni != null && CANONICAL_DNI_PATTERN.matcher(dni).matches();
    }
}
