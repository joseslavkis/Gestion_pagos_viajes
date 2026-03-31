import { useMemo, useState, type CSSProperties } from "react";
import { Link } from "wouter";

import { CommonLayout } from "@/components/CommonLayout/CommonLayout";
import { RequestState } from "@/components/ui/RequestState/RequestState";
import { useSignup } from "@/features/auth/services/auth-service";
import { useSchools } from "@/features/schools/services/schools-service";
import {
  SignupRequestSchema,
  type SignupRequest,
  type StudentCreateDTO,
} from "@/features/auth/types/auth-dtos";

import styles from "./SignupPage.module.css";

const emptyStudent = (): StudentCreateDTO => ({
  name: "",
  dni: "",
  schoolName: "",
  courseName: "",
});

const formLayout: CSSProperties = {
  width: "100%",
  display: "grid",
  gap: "1rem",
};

const cardStyle: CSSProperties = {
  border: "1px solid #dbe5f0",
  borderRadius: 16,
  padding: "1rem",
  background: "#f8fbff",
  display: "grid",
  gap: "0.75rem",
};

const fieldStyle: CSSProperties = {
  display: "grid",
  gap: 6,
};

const inputStyle: CSSProperties = {
  width: "100%",
  minHeight: 44,
  borderRadius: 12,
  border: "1px solid #c8d7e8",
  padding: "0.75rem 0.9rem",
  boxSizing: "border-box",
};

const secondaryButtonStyle: CSSProperties = {
  minHeight: 44,
  borderRadius: 12,
  border: "1px solid #c8d7e8",
  background: "#fff",
  padding: "0.7rem 0.9rem",
  cursor: "pointer",
};

const primaryButtonStyle: CSSProperties = {
  minHeight: 48,
  borderRadius: 14,
  border: "none",
  background: "#0b5bcf",
  color: "#fff",
  fontWeight: 700,
  padding: "0.85rem 1rem",
  cursor: "pointer",
};

export function SignupPage() {
  const { mutateAsync, isPending } = useSignup();
  const { data: schools, isLoading: isSchoolsLoading, error: schoolsError } = useSchools();
  const [form, setForm] = useState<SignupRequest>({
    email: "",
    password: "",
    name: "",
    lastname: "",
    dni: "",
    phone: "",
    students: [emptyStudent()],
  });
  const [validationErrors, setValidationErrors] = useState<string[]>([]);
  const [schoolSearchByIndex, setSchoolSearchByIndex] = useState<Record<number, string>>({});
  const [openSchoolPickerIndex, setOpenSchoolPickerIndex] = useState<number | null>(null);

  const studentsCountLabel = useMemo(
    () => `${form.students.length} hijo${form.students.length === 1 ? "" : "s"} cargado${form.students.length === 1 ? "" : "s"}`,
    [form.students.length],
  );
  const schoolItems = useMemo(
    () => [...(schools ?? [])].sort((a, b) => a.name.localeCompare(b.name, "es", { sensitivity: "base" })),
    [schools],
  );

  const handleParentFieldChange = (field: keyof Omit<SignupRequest, "students">, value: string) => {
    setForm((current) => ({ ...current, [field]: value }));
  };

  const handleStudentChange = (
    index: number,
    field: keyof StudentCreateDTO,
    value: string,
  ) => {
    setForm((current) => ({
      ...current,
      students: current.students.map((student, studentIndex) =>
        studentIndex === index ? { ...student, [field]: value } : student,
      ),
    }));
  };

  const handleAddStudent = () => {
    setForm((current) => {
      if (current.students.length >= 10) {
        return current;
      }
      return { ...current, students: [...current.students, emptyStudent()] };
    });
  };

  const handleRemoveStudent = (index: number) => {
    setForm((current) => {
      if (current.students.length <= 1) {
        return current;
      }

      return {
        ...current,
        students: current.students.filter((_, studentIndex) => studentIndex !== index),
      };
    });

    setSchoolSearchByIndex((current) => {
      const next: Record<number, string> = {};

      for (const [key, value] of Object.entries(current)) {
        const numericKey = Number(key);
        if (!Number.isInteger(numericKey) || numericKey === index) {
          continue;
        }

        next[numericKey > index ? numericKey - 1 : numericKey] = value;
      }

      return next;
    });

    setOpenSchoolPickerIndex((current) => {
      if (current === null) {
        return current;
      }
      if (current === index) {
        return null;
      }
      return current > index ? current - 1 : current;
    });
  };

  const handleSubmit = async (event: React.FormEvent<HTMLFormElement>) => {
    event.preventDefault();
    setValidationErrors([]);

    const parsed = SignupRequestSchema.safeParse(form);
    if (!parsed.success) {
      setValidationErrors(parsed.error.issues.map((issue) => issue.message));
      return;
    }

    try {
      await mutateAsync(parsed.data);
    } catch {
      // The mutation error is rendered by RequestState.
    }
  };

  return (
    <CommonLayout>
      <section className={styles.wrapper}>
        <header className={styles.header}>
          <p className={styles.eyebrow}>Registro Protegido</p>
          <h1>Crear cuenta</h1>
          <p className={styles.description}>Completá tus datos y reclamá los DNIs de tus hijos que la agencia cargó previamente para sus viajes.</p>
        </header>

        <RequestState isLoading={isPending} loadingLabel="Creando cuenta...">
          <form onSubmit={handleSubmit} style={formLayout}>
            <div style={cardStyle}>
              <h2 style={{ margin: 0 }}>Datos del responsable</h2>
              <label style={fieldStyle}>
                <span>Nombre</span>
                <input
                  style={inputStyle}
                  value={form.name}
                  onChange={(event) => handleParentFieldChange("name", event.target.value)}
                  autoComplete="given-name"
                />
              </label>
              <label style={fieldStyle}>
                <span>Apellido</span>
                <input
                  style={inputStyle}
                  value={form.lastname}
                  onChange={(event) => handleParentFieldChange("lastname", event.target.value)}
                  autoComplete="family-name"
                />
              </label>
              <label style={fieldStyle}>
                <span>Email</span>
                <input
                  style={inputStyle}
                  type="email"
                  value={form.email}
                  onChange={(event) => handleParentFieldChange("email", event.target.value)}
                  autoComplete="email"
                />
              </label>
              <label style={fieldStyle}>
                <span>Contraseña</span>
                <input
                  style={inputStyle}
                  type="password"
                  value={form.password}
                  onChange={(event) => handleParentFieldChange("password", event.target.value)}
                  autoComplete="new-password"
                />
              </label>
              <label style={fieldStyle}>
                <span>DNI del padre / tutor</span>
                <input
                  style={inputStyle}
                  value={form.dni}
                  onChange={(event) => handleParentFieldChange("dni", event.target.value)}
                />
              </label>
              <label style={fieldStyle}>
                <span>Teléfono</span>
                <input
                  style={inputStyle}
                  value={form.phone}
                  onChange={(event) => handleParentFieldChange("phone", event.target.value)}
                  autoComplete="tel"
                />
              </label>
            </div>

            <div style={cardStyle}>
              <div style={{ display: "flex", justifyContent: "space-between", gap: 12, alignItems: "center", flexWrap: "wrap" }}>
                <div>
                  <h2 style={{ margin: 0 }}>Mis hijos</h2>
                  <p style={{ margin: "0.35rem 0 0", color: "#47658f" }}>
                    {studentsCountLabel}. Solo podés cargar DNIs precargados por administración. Puedes registrar hasta 10.
                  </p>
                </div>
                <button
                  type="button"
                  style={secondaryButtonStyle}
                  onClick={handleAddStudent}
                  disabled={form.students.length >= 10}
                >
                  + Agregar otro hijo
                </button>
              </div>

              {form.students.map((student, index) => (
                <div key={index} style={{ ...cardStyle, background: "#fff" }}>
                  {(() => {
                    const schoolSearchValue = schoolSearchByIndex[index] ?? (student.schoolName ?? "");
                    const normalizedSearch = schoolSearchValue.trim().toLocaleLowerCase("es");
                    const filteredSchoolItems =
                      normalizedSearch.length === 0
                        ? schoolItems
                        : schoolItems.filter((school) =>
                            school.name.toLocaleLowerCase("es").includes(normalizedSearch),
                          );
                    const isSchoolListOpen =
                      openSchoolPickerIndex === index && !isSchoolsLoading && schoolItems.length > 0;
                    const schoolListboxId = `school-results-${index}`;
                    const hasSchoolSelection = Boolean(student.schoolName?.trim());

                    return (
                      <>
                  <div style={{ display: "flex", justifyContent: "space-between", alignItems: "center", gap: 12 }}>
                    <strong>Hijo {index + 1}</strong>
                    <button
                      type="button"
                      style={secondaryButtonStyle}
                      onClick={() => handleRemoveStudent(index)}
                      disabled={form.students.length === 1}
                      aria-label={`Eliminar hijo ${index + 1}`}
                    >
                      ✕
                    </button>
                  </div>

                  <label style={fieldStyle}>
                    <span>Nombre completo</span>
                    <input
                      style={inputStyle}
                      value={student.name}
                      onChange={(event) => handleStudentChange(index, "name", event.target.value)}
                    />
                  </label>
                  <label style={fieldStyle}>
                    <span>DNI</span>
                    <input
                      style={inputStyle}
                      value={student.dni}
                      onChange={(event) => handleStudentChange(index, "dni", event.target.value)}
                    />
                  </label>
                  <label style={fieldStyle}>
                    <div className={styles.schoolFieldHeader}>
                      <span>Colegio</span>
                      <span className={styles.schoolCountBadge}>
                        {isSchoolsLoading
                          ? "Cargando..."
                          : `${filteredSchoolItems.length}/${schoolItems.length} disponibles`}
                      </span>
                    </div>
                    <div
                      className={styles.schoolPicker}
                      onBlur={(event) => {
                        const nextFocused = event.relatedTarget;
                        if (nextFocused instanceof Node && event.currentTarget.contains(nextFocused)) {
                          return;
                        }
                        setOpenSchoolPickerIndex((current) => (current === index ? null : current));
                      }}
                    >
                      <div className={styles.schoolSearchControl}>
                        <input
                          type="search"
                          className={styles.schoolFilterInput}
                          role="combobox"
                          placeholder={
                            schoolItems.length === 0 ? "No hay colegios cargados" : "Buscar y seleccionar colegio..."
                          }
                          value={schoolSearchValue}
                          onFocus={() => setOpenSchoolPickerIndex(index)}
                          onChange={(event) => {
                            const nextValue = event.target.value;
                            setSchoolSearchByIndex((current) => ({
                              ...current,
                              [index]: nextValue,
                            }));
                            if (student.schoolName !== nextValue) {
                              handleStudentChange(index, "schoolName", "");
                            }
                            setOpenSchoolPickerIndex(index);
                          }}
                          disabled={isSchoolsLoading || schoolItems.length === 0}
                          autoComplete="off"
                          aria-expanded={isSchoolListOpen}
                          aria-controls={schoolListboxId}
                          aria-label={`Buscar colegio para hijo ${index + 1}`}
                        />
                        {schoolSearchValue.trim().length > 0 ? (
                          <button
                            type="button"
                            className={styles.schoolClearButton}
                            onClick={() => {
                              setSchoolSearchByIndex((current) => ({ ...current, [index]: "" }));
                              handleStudentChange(index, "schoolName", "");
                              setOpenSchoolPickerIndex(index);
                            }}
                            aria-label="Limpiar búsqueda de colegio"
                          >
                            Limpiar
                          </button>
                        ) : null}
                      </div>
                      {isSchoolListOpen ? (
                        <ul
                          id={schoolListboxId}
                          className={styles.schoolOptions}
                          role="listbox"
                          aria-label={`Resultados de colegios para hijo ${index + 1}`}
                        >
                          {filteredSchoolItems.length > 0 ? (
                            filteredSchoolItems.map((school) => {
                              const isSelected = student.schoolName === school.name;
                              return (
                                <li key={school.id}>
                                  <button
                                    type="button"
                                    role="option"
                                    aria-selected={isSelected}
                                    className={`${styles.schoolOptionButton} ${isSelected ? styles.schoolOptionSelected : ""}`}
                                    onMouseDown={(event) => event.preventDefault()}
                                    onClick={() => {
                                      handleStudentChange(index, "schoolName", school.name);
                                      setSchoolSearchByIndex((current) => ({
                                        ...current,
                                        [index]: school.name,
                                      }));
                                      setOpenSchoolPickerIndex(null);
                                    }}
                                  >
                                    <span>{school.name}</span>
                                    {isSelected ? <span className={styles.schoolOptionTag}>Elegido</span> : null}
                                  </button>
                                </li>
                              );
                            })
                          ) : (
                            <li className={styles.schoolEmptyState}>No encontramos coincidencias para esa búsqueda.</li>
                          )}
                        </ul>
                      ) : null}
                    </div>
                    {hasSchoolSelection ? (
                      <small className={styles.schoolSelectedHint}>Seleccionado: {student.schoolName}</small>
                    ) : (
                      <small className={styles.schoolEmptyHint}>Buscá y elegí un colegio de la lista para reclamar este DNI.</small>
                    )}
                  </label>
                  <label style={fieldStyle}>
                    <span>Curso</span>
                    <input
                      style={inputStyle}
                      value={student.courseName ?? ""}
                      onChange={(event) => handleStudentChange(index, "courseName", event.target.value)}
                    />
                  </label>
                      </>
                    );
                  })()}
                </div>
              ))}
            </div>
            {isSchoolsLoading ? <p style={{ margin: 0, color: "#47658f" }}>Cargando colegios disponibles...</p> : null}
            {schoolsError ? <p style={{ margin: 0, color: "#b91c1c" }}>{schoolsError.message}</p> : null}
            {!isSchoolsLoading && !schoolsError && schoolItems.length === 0 ? (
              <p style={{ margin: 0, color: "#92400e", fontWeight: 600 }}>
                Aún no hay colegios cargados por administración.
              </p>
            ) : null}

            {validationErrors.length > 0 ? (
              <ul style={{ margin: 0, paddingLeft: "1.2rem", color: "#b42318" }}>
                {validationErrors.map((message) => (
                  <li key={message}>{message}</li>
                ))}
              </ul>
            ) : null}

            <button type="submit" style={primaryButtonStyle} disabled={isPending}>
              {isPending ? "Creando cuenta..." : "Crear cuenta"}
            </button>

            <p>
              ¿Ya tienes cuenta?{" "}
              <Link href="/login" className={styles.link}>
                Inicia sesión
              </Link>
            </p>
          </form>
        </RequestState>
      </section>
    </CommonLayout>
  );
}
