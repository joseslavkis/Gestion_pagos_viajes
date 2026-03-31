const STUDENT_DNI_CANONICAL_PATTERN = /^\d{7,8}$/;

export function normalizeStudentDniInput(value: string): string {
  return value.replace(/[.\-\s]/g, "").trim();
}

export function isCanonicalStudentDni(value: string): boolean {
  return STUDENT_DNI_CANONICAL_PATTERN.test(value);
}
