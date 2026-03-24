import { useMemo, useState } from "react";

import { CommonLayout } from "@/components/CommonLayout/CommonLayout";
import { RequestState } from "@/components/ui/RequestState/RequestState";
import { useAdminSchools, useCreateSchool } from "@/features/schools/services/schools-service";

import styles from "./SchoolsPage.module.css";

export function SchoolsPage() {
  const { data, isLoading, error } = useAdminSchools();
  const createSchool = useCreateSchool();
  const [name, setName] = useState("");
  const [formError, setFormError] = useState<string | null>(null);
  const [successMessage, setSuccessMessage] = useState<string | null>(null);

  const schools = useMemo(() => data ?? [], [data]);

  const handleSubmit = async (event: React.FormEvent<HTMLFormElement>) => {
    event.preventDefault();
    setFormError(null);
    setSuccessMessage(null);

    const trimmedName = name.trim().replace(/\s+/g, " ");
    if (trimmedName.length < 2) {
      setFormError("Ingresá un nombre de colegio válido.");
      return;
    }

    try {
      await createSchool.mutateAsync({ name: trimmedName });
      setName("");
      setSuccessMessage("Colegio agregado correctamente.");
    } catch (submissionError) {
      setFormError(
        submissionError instanceof Error
          ? submissionError.message
          : "No se pudo guardar el colegio.",
      );
    }
  };

  return (
    <CommonLayout>
      <section className={styles.page}>
        <div className={styles.container}>
          <header className={styles.header}>
            <div>
              <h1 className={styles.title}>Colegios</h1>
              <p className={styles.subtitle}>Carga manualmente los colegios que podrá elegir el usuario al agregar hijos.</p>
            </div>
          </header>

          <section className={styles.card}>
            <h2 className={styles.sectionTitle}>Nuevo colegio</h2>
            <form className={styles.form} onSubmit={handleSubmit}>
              <label className={styles.field}>
                <span>Nombre</span>
                <input
                  className={styles.input}
                  value={name}
                  onChange={(event) => setName(event.target.value)}
                />
              </label>
              <button type="submit" className={styles.button} disabled={createSchool.isPending}>
                {createSchool.isPending ? "Guardando..." : "Agregar colegio"}
              </button>
              {formError ? <p className={styles.errorText}>{formError}</p> : null}
              {successMessage ? <p className={styles.successText}>{successMessage}</p> : null}
            </form>
          </section>

          <section className={styles.card}>
            <h2 className={styles.sectionTitle}>Listado</h2>
            <RequestState isLoading={isLoading} error={error ?? null} loadingLabel="Cargando colegios...">
              {schools.length === 0 ? <p className={styles.emptyText}>Todavía no hay colegios cargados.</p> : null}
              <div className={styles.list}>
                {schools.map((school) => (
                  <article key={school.id} className={styles.schoolItem}>
                    {school.name}
                  </article>
                ))}
              </div>
            </RequestState>
          </section>
        </div>
      </section>
    </CommonLayout>
  );
}
