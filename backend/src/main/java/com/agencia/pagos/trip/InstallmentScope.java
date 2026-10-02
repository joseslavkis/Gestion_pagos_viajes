package com.agencia.pagos.trip;

/**
 * Clave de inscripción (trip + user + student) resuelta con una query escalar.
 *
 * <p>Existe para descubrir el alcance a bloquear SIN cargar entidades al
 * persistence context antes de tomar los locks: una entidad leída pre-lock
 * quedaría managed con estado viejo y los queries locked posteriores
 * devolverían esa misma instancia stale en lugar del estado recién
 * bloqueado. Los tres ids son FKs inmutables, seguros de leer sin lock.
 */
public record InstallmentScope(Long tripId, Long userId, Long studentId) {
}
