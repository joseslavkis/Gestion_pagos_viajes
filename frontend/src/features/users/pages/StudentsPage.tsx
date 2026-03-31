import { useState, type CSSProperties } from "react";

import { CommonLayout } from "@/components/CommonLayout/CommonLayout";
import { useAddStudent, useDeleteStudent, useStudents } from "@/features/users/services/users-service";
import type { StudentCreateDTO } from "@/features/auth/types/auth-dtos";
import { StudentCreateSchema } from "@/features/auth/types/auth-dtos";

import styles from "./StudentsPage.module.css";

const studentCardStyle: CSSProperties = {
  border: "1px solid #dbe5f0",
  borderRadius: 16,
  padding: "1rem",
  background: "#fff",
  display: "grid",
  gap: "0.45rem",
};

const studentActionsStyle: CSSProperties = {
  display: "flex",
  gap: 8,
  flexWrap: "wrap",
};

const emptyStudent = (): StudentCreateDTO => ({
  name: "",
  dni: "",
});

export function StudentsPage() {
  const addStudent = useAddStudent();
  const deleteStudent = useDeleteStudent();
  const { data: students, isLoading: isStudentsLoading, error: studentsError } = useStudents();

  const [newStudent, setNewStudent] = useState<StudentCreateDTO>(emptyStudent());
  const [studentFormError, setStudentFormError] = useState<string | null>(null);
  const [studentSuccessMessage, setStudentSuccessMessage] = useState<string | null>(null);
  const [deletingStudentId, setDeletingStudentId] = useState<number | null>(null);

  const studentItems = students ?? [];

  const handleAddStudent = async (event: React.FormEvent<HTMLFormElement>) => {
    event.preventDefault();
    setStudentFormError(null);
    setStudentSuccessMessage(null);

    const parsed = StudentCreateSchema.safeParse(newStudent);
    if (!parsed.success) {
      setStudentFormError(parsed.error.issues[0]?.message ?? "Revisá los datos del alumno.");
      return;
    }

    try {
      const createdStudent = await addStudent.mutateAsync(parsed.data);
      setNewStudent(emptyStudent());
      setStudentSuccessMessage(`El alumno ${createdStudent.name} se agrego con exito.`);
    } catch (currentError) {
      setStudentFormError(
        currentError instanceof Error ? currentError.message : "No se pudo agregar el alumno.",
      );
    }
  };

  const handleDeleteStudent = async (studentId: number) => {
    setStudentFormError(null);
    setStudentSuccessMessage(null);

    try {
      await deleteStudent.mutateAsync(studentId);
      setDeletingStudentId(null);
      setStudentSuccessMessage("Alumno eliminado correctamente.");
    } catch (currentError) {
      setStudentFormError(
        currentError instanceof Error ? currentError.message : "No se pudo eliminar el alumno.",
      );
    }
  };

  return (
    <CommonLayout>
      <section className={styles.page}>
        <div className={styles.container}>
          <h1 className={styles.title}>Mis hijos</h1>

          <section className={styles.section}>
            <p className={styles.helperText}>
              Podés reclamar hijos solo si la agencia precargó su DNI en algún viaje.
            </p>

            {isStudentsLoading ? <p className={styles.helperText}>Cargando alumnos...</p> : null}
            {studentsError ? <p className={styles.errorText}>{studentsError.message}</p> : null}

            {!isStudentsLoading && !studentsError && studentItems.length === 0 ? (
              <p className={styles.helperText}>Todavía no reclamaste hijos en tu cuenta.</p>
            ) : null}

            <div style={{ display: "grid", gap: "0.85rem", marginBottom: "1rem" }}>
              {studentItems.map((student) => (
                <div key={student.id} style={studentCardStyle}>
                  <div style={{ display: "flex", justifyContent: "space-between", gap: 12, alignItems: "center", flexWrap: "wrap" }}>
                    <strong>{student.name}</strong>
                    {deletingStudentId === student.id ? (
                      <div style={studentActionsStyle}>
                        <button
                          type="button"
                          className={styles.submitButton}
                          onClick={() => handleDeleteStudent(student.id)}
                          disabled={deleteStudent.isPending}
                        >
                          Confirmar
                        </button>
                        <button
                          type="button"
                          className={styles.select}
                          onClick={() => setDeletingStudentId(null)}
                          disabled={deleteStudent.isPending}
                        >
                          Cancelar
                        </button>
                      </div>
                    ) : (
                      <button
                        type="button"
                        className={styles.select}
                        onClick={() => setDeletingStudentId(student.id)}
                        disabled={deleteStudent.isPending}
                      >
                        ✕
                      </button>
                    )}
                  </div>
                  <div>DNI: <strong>{student.dni}</strong></div>
                  {deletingStudentId === student.id ? (
                    <p className={styles.helperWarning}>
                      ¿Eliminar a {student.name}? Esta acción no se puede deshacer si no tiene cuotas.
                    </p>
                  ) : null}
                </div>
              ))}
            </div>

            <form className={styles.form} onSubmit={handleAddStudent}>
              <label className={styles.formField}>
                <span className={styles.label}>Nombre completo</span>
                <input
                  className={styles.input}
                  value={newStudent.name}
                  onChange={(event) => setNewStudent((current) => ({ ...current, name: event.target.value }))}
                />
              </label>
              <label className={styles.formField}>
                <span className={styles.label}>DNI</span>
                <input
                  className={styles.input}
                  value={newStudent.dni}
                  onChange={(event) => setNewStudent((current) => ({ ...current, dni: event.target.value }))}
                />
              </label>

              <button type="submit" className={styles.submitButton} disabled={addStudent.isPending}>
                {addStudent.isPending ? "Agregando..." : "Agregar hijo"}
              </button>
              <p className={styles.helperText}>
                Solo se aceptan DNIs que administración haya cargado previamente. La referencia del viaje se vincula automáticamente.
              </p>

              {studentFormError ? <p className={styles.errorText}>{studentFormError}</p> : null}
              {studentSuccessMessage ? <p className={styles.successText}>{studentSuccessMessage}</p> : null}
            </form>
          </section>
        </div>
      </section>
    </CommonLayout>
  );
}
