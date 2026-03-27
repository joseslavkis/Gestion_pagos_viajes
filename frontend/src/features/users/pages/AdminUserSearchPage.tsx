import { useEffect, useState } from "react";
import { Link } from "wouter";

import { CommonLayout } from "@/components/CommonLayout/CommonLayout";
import { RequestState } from "@/components/ui/RequestState/RequestState";
import { useAdminUserSearch } from "@/features/users/services/users-service";

import styles from "./AdminUserSearchPage.module.css";

export function AdminUserSearchPage() {
  const [rawQuery, setRawQuery] = useState("");
  const [debouncedQuery, setDebouncedQuery] = useState("");

  useEffect(() => {
    const timeoutId = window.setTimeout(() => {
      setDebouncedQuery(rawQuery.trim());
    }, 250);

    return () => window.clearTimeout(timeoutId);
  }, [rawQuery]);

  const searchQuery = debouncedQuery.trim();
  const isSearchReady = searchQuery.length >= 2;
  const { data, isLoading, error } = useAdminUserSearch(searchQuery);
  const results = data ?? [];

  return (
    <CommonLayout>
      <section className={styles.page}>
        <div className={styles.container}>
          <header className={styles.header}>
            <p className={styles.eyebrow}>Admin</p>
            <h1 className={styles.title}>Buscar usuarios</h1>
            <p className={styles.subtitle}>
              Encontrá responsables por nombre, apellido, email o DNI y entrá a su detalle administrativo.
            </p>
          </header>

          <section className={styles.searchCard}>
            <label className={styles.searchLabel}>
              <span>Buscador</span>
              <div className={styles.searchField}>
                <input
                  type="search"
                  className={styles.searchInput}
                  placeholder="Buscar por nombre, mail o DNI..."
                  value={rawQuery}
                  onChange={(event) => setRawQuery(event.target.value)}
                />
                {rawQuery.trim().length > 0 ? (
                  <button
                    type="button"
                    className={styles.clearButton}
                    onClick={() => {
                      setRawQuery("");
                      setDebouncedQuery("");
                    }}
                    aria-label="Limpiar búsqueda"
                  >
                    ×
                  </button>
                ) : null}
              </div>
            </label>
            <p className={styles.searchHelp}>
              La búsqueda comienza a partir de 2 caracteres y solo incluye usuarios responsables.
            </p>
          </section>

          {!isSearchReady ? (
            <section className={styles.emptyPanel}>
              <h2 className={styles.emptyTitle}>Escribí al menos 2 caracteres</h2>
              <p className={styles.emptyText}>
                Podés buscar por nombre, apellido, email o DNI del responsable.
              </p>
            </section>
          ) : (
            <section className={styles.resultsCard}>
              <div className={styles.resultsHeader}>
                <h2 className={styles.sectionTitle}>Resultados</h2>
                <span className={styles.resultCount}>
                  {isLoading ? "Buscando..." : `${results.length} encontrado${results.length === 1 ? "" : "s"}`}
                </span>
              </div>

              <RequestState isLoading={isLoading} error={error ?? null} loadingLabel="Buscando usuarios...">
                {results.length === 0 && !isLoading ? (
                  <div className={styles.emptyPanel}>
                    <h3 className={styles.emptyTitle}>No encontramos usuarios</h3>
                    <p className={styles.emptyText}>Probá con otro nombre, email o DNI.</p>
                  </div>
                ) : null}

                <div className={styles.resultsList}>
                  {results.map((user) => (
                    <Link key={user.id} href={`/users/${user.id}`} className={styles.resultCard}>
                      <div className={styles.resultMain}>
                        <h3 className={styles.resultTitle}>
                          {user.lastname}, {user.name}
                        </h3>
                        <p className={styles.resultMeta}>{user.email}</p>
                        <p className={styles.resultMeta}>
                          DNI: {user.dni ?? "Sin DNI"}{user.phone ? ` · Tel: ${user.phone}` : ""}
                        </p>
                      </div>
                      <span className={styles.studentBadge}>
                        {user.studentsCount} hijo{user.studentsCount === 1 ? "" : "s"}
                      </span>
                    </Link>
                  ))}
                </div>
              </RequestState>
            </section>
          )}
        </div>
      </section>
    </CommonLayout>
  );
}
