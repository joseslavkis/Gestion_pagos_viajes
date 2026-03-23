import { useMemo, useState, type CSSProperties } from "react";
import { Link } from "wouter";

import { CommonLayout } from "@/components/CommonLayout/CommonLayout";
import { SchoolAutocomplete } from "@/components/form-components/SchoolAutocomplete/SchoolAutocomplete";
import { RequestState } from "@/components/ui/RequestState/RequestState";
import { useSignup } from "@/features/auth/services/auth-service";
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
  const { mutateAsync, error, isPending } = useSignup();
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

  const studentsCountLabel = useMemo(
    () => `${form.students.length} hijo${form.students.length === 1 ? "" : "s"} cargado${form.students.length === 1 ? "" : "s"}`,
    [form.students.length],
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
  };

  const handleSubmit = async (event: React.FormEvent<HTMLFormElement>) => {
    event.preventDefault();
    setValidationErrors([]);

    const parsed = SignupRequestSchema.safeParse(form);
    if (!parsed.success) {
      setValidationErrors(parsed.error.issues.map((issue) => issue.message));
      return;
    }

    await mutateAsync(parsed.data);
  };

  return (
    <CommonLayout>
      <section className={styles.wrapper}>
        <header className={styles.header}>
          <p className={styles.eyebrow}>Registro Protegido</p>
          <h1>Crear cuenta</h1>
          <p className={styles.description}>Completa tus datos y registra a tus hijos para que luego puedan ser asignados a sus viajes.</p>
        </header>

        <RequestState isLoading={isPending} error={error ?? null} loadingLabel="Creando cuenta...">
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
                  <p style={{ margin: "0.35rem 0 0", color: "#47658f" }}>{studentsCountLabel}. Puedes registrar hasta 10.</p>
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
                  <SchoolAutocomplete
                    label="Colegio"
                    value={student.schoolName ?? ""}
                    onChange={(value) => handleStudentChange(index, "schoolName", value)}
                  />
                  <label style={fieldStyle}>
                    <span>Curso</span>
                    <input
                      style={inputStyle}
                      value={student.courseName ?? ""}
                      onChange={(event) => handleStudentChange(index, "courseName", event.target.value)}
                    />
                  </label>
                </div>
              ))}
            </div>

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
