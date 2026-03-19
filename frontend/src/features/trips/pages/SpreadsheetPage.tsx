import { useEffect, useMemo, useRef, useState } from "react";
import { useLocation } from "wouter";

import { CommonLayout } from "@/components/CommonLayout/CommonLayout";
import { RequestState } from "@/components/ui/RequestState/RequestState";
import { useSpreadsheet } from "@/features/trips/services/trips-service";
import type {
  SpreadsheetParams,
  SpreadsheetRowDTO,
  SpreadsheetRowInstallmentDTO,
} from "@/features/trips/types/trips-dtos";

import styles from "./SpreadsheetPage.module.css";

const currencyFormatter = new Intl.NumberFormat("es-AR", {
  style: "currency",
  currency: "ARS",
});

const dateFormatter = new Intl.DateTimeFormat("es-AR", {
  day: "2-digit",
  month: "2-digit",
  year: "numeric",
  timeZone: "America/Argentina/Buenos_Aires",
});

type SpreadsheetPageProps = {
  tripId: number;
};

type SelectedInstallment = {
  row: SpreadsheetRowDTO;
  installment: SpreadsheetRowInstallmentDTO;
};

export function SpreadsheetPage({ tripId }: SpreadsheetPageProps) {
  const [, setLocation] = useLocation();

  const [params, setParams] = useState<SpreadsheetParams>({
    page: 0,
    size: 20,
    search: undefined,
    sortBy: "lastname",
    order: "asc",
    status: "",
  });
  const [rawSearch, setRawSearch] = useState("");
  const [hasScrolledHorizontally, setHasScrolledHorizontally] = useState(false);
  const [selected, setSelected] = useState<SelectedInstallment | null>(null);
  const tableContainerRef = useRef<HTMLDivElement | null>(null);

  const { data, isLoading, error } = useSpreadsheet(tripId, params);

  useEffect(() => {
    const handle = window.setTimeout(() => {
      const searchValue = rawSearch.trim();
      setParams((current) => ({
        ...current,
        page: 0,
        search: searchValue.length > 0 ? searchValue : undefined,
      }));
    }, 300);

    return () => window.clearTimeout(handle);
  }, [rawSearch]);

  useEffect(() => {
    const element = tableContainerRef.current;
    if (!element) {
      return;
    }

    const handleScroll = () => {
      if (element.scrollLeft > 0) {
        setHasScrolledHorizontally(true);
      }
    };

    element.addEventListener("scroll", handleScroll, { passive: true });
    return () => element.removeEventListener("scroll", handleScroll);
  }, []);

  useEffect(() => {
    const tableTopElement = document.getElementById("spreadsheet-table-top");
    if (tableTopElement) {
      tableTopElement.scrollIntoView({ behavior: "smooth", block: "start" });
    }
  }, [params.page]);

  const totalPages = useMemo(() => {
    if (!data) return 1;
    if (data.totalElements === 0) return 1;
    return Math.max(1, Math.ceil(data.totalElements / params.size));
  }, [data, params.size]);

  const isFiltered =
    (params.search && params.search.length > 0) ||
    (typeof params.status === "string" && params.status !== "");

  const handleStatusChange = (value: string) => {
    setParams((current) => ({
      ...current,
      page: 0,
      status: value === "" ? "" : (value as SpreadsheetParams["status"]),
    }));
  };

  const handleOrderChange = (value: string) => {
    const orderMap: Record<string, Pick<SpreadsheetParams, "sortBy" | "order">> = {
      "lastname-asc": { sortBy: "lastname", order: "asc" },
      "lastname-desc": { sortBy: "lastname", order: "desc" },
      "name-asc": { sortBy: "name", order: "asc" },
    };

    const resolved = orderMap[value];
    if (resolved) {
      setParams((current) => ({ ...current, page: 0, ...resolved }));
    }
  };

  const handlePrevPage = () => {
    setParams((current) => ({
      ...current,
      page: Math.max(0, current.page - 1),
    }));
  };

  const handleNextPage = () => {
    setParams((current) => ({
      ...current,
      page: Math.min(totalPages - 1, current.page + 1),
    }));
  };

  const rows = data?.rows ?? [];
  const installmentsCount = data?.installmentsCount ?? 0;

  return (
    <CommonLayout>
      <section className={styles.page}>
        <div className={styles.shell}>
          <header className={styles.header}>
            <div className={styles.headerRow}>
              <button
                type="button"
                className={styles.backButton}
                onClick={() => setLocation("/")}
              >
                ← Volver a viajes
              </button>
              <div className={styles.titleBlock}>
                <h1 className={styles.title}>{data?.tripName ?? "Planilla de viaje"}</h1>
                <p className={styles.subtitle}>
                  Vista de cuotas y estados de pago por participante.
                </p>
                {data ? (
                  <span className={styles.counter}>
                    {data.totalElements} participantes · {data.installmentsCount} cuotas
                  </span>
                ) : null}
              </div>
            </div>

            <div className={styles.toolbar}>
              <input
                type="search"
                className={styles.searchInput}
                placeholder="Buscar por nombre, apellido o email..."
                value={rawSearch}
                onChange={(event) => setRawSearch(event.target.value)}
              />
              <select
                className={styles.select}
                value={params.status ?? ""}
                onChange={(event) => handleStatusChange(event.target.value)}
              >
                <option value="">Todos los estados</option>
                <option value="GREEN">Verde (al día)</option>
                <option value="YELLOW">Amarillo (aviso)</option>
                <option value="RED">Rojo (mora)</option>
                <option value="RETROACTIVE">Retroactivo</option>
              </select>
              <select
                className={styles.select}
                value={
                  params.sortBy === "name"
                    ? "name-asc"
                    : params.sortBy === "lastname" && params.order === "desc"
                      ? "lastname-desc"
                      : "lastname-asc"
                }
                onChange={(event) => handleOrderChange(event.target.value)}
              >
                <option value="lastname-asc">Apellido A→Z</option>
                <option value="lastname-desc">Apellido Z→A</option>
                <option value="name-asc">Nombre A→Z</option>
              </select>
            </div>
          </header>

          <RequestState
            isLoading={isLoading}
            error={error ?? null}
            loadingLabel="Cargando planilla..."
          >
            <div id="spreadsheet-table-top" className={styles.tableContainer}>
              <div ref={tableContainerRef} className={styles.scrollShell}>
                {!hasScrolledHorizontally ? (
                  <div className={styles.scrollHint}>Deslizá → para ver todas las cuotas</div>
                ) : null}
                <table className={styles.table}>
                  <thead className={styles.thead}>
                    <tr>
                      <th className={`${styles.th} ${styles.userCol}`}>Participante</th>
                      {Array.from({ length: installmentsCount }).map((_, index) => (
                        <th key={index} className={styles.th}>
                          Cuota {index + 1}
                        </th>
                      ))}
                    </tr>
                  </thead>
                  <tbody>
                    {isLoading
                      ? Array.from({ length: 5 }).map((_, rowIndex) => (
                          <tr key={rowIndex} className={styles.skeletonRow}>
                            <td className={`${styles.td} ${styles.userCell}`}>
                              <div className={styles.skeletonBlock} />
                            </td>
                            {Array.from({ length: Math.max(1, installmentsCount) }).map((__, colIndex) => (
                              <td key={colIndex} className={styles.td}>
                                <div className={styles.skeletonBlock} />
                              </td>
                            ))}
                          </tr>
                        ))
                      : rows.map((row) => (
                          <tr key={row.userId}>
                            <td className={`${styles.td} ${styles.userCell}`}>
                              <span className={styles.userMain}>
                                {row.lastname}, {row.name}
                              </span>
                              <span className={styles.userSecondary}>{row.email}</span>
                              {row.phone ? (
                                <span className={styles.userSecondary}>Tel: {row.phone}</span>
                              ) : null}
                              {row.studentName ? (
                                <span className={styles.userSecondary}>Alumno: {row.studentName}</span>
                              ) : null}
                              {row.schoolName ? (
                                <span className={styles.userSecondary}>Colegio: {row.schoolName}</span>
                              ) : null}
                              {row.courseName ? (
                                <span className={styles.userSecondary}>Curso: {row.courseName}</span>
                              ) : null}
                            </td>
                            {Array.from({ length: installmentsCount }).map((_, index) => {
                              const installment = row.installments.find(
                                (item) => item.installmentNumber === index + 1,
                              );
                              if (!installment) {
                                return (
                                  <td key={index} className={styles.td}>
                                    -
                                  </td>
                                );
                              }

                              const statusClass = getStatusClass(installment.status);
                              const icon = getStatusIcon(installment.status);

                              return (
                                <td
                                  key={index}
                                  className={`${styles.td} ${styles.amountCell}`}
                                  onClick={() => setSelected({ row, installment })}
                                >
                                  <div>{currencyFormatter.format(installment.totalDue)}</div>
                                  <div className={`${styles.statusPill} ${statusClass.pill}`}>
                                    <span className={`${styles.statusDot} ${statusClass.dot}`} />
                                    <span>{icon}</span>
                                  </div>
                                </td>
                              );
                            })}
                          </tr>
                        ))}
                  </tbody>
                </table>
              </div>

              {!isLoading && rows.length === 0 ? (
                <div className={styles.emptyState}>
                  <h2 className={styles.emptyTitle}>
                    {isFiltered ? "No se encontraron participantes con esos filtros" : "Todavía no hay participantes"}
                  </h2>
                  <p className={styles.emptyDescription}>
                    {isFiltered
                      ? "Ajusta los filtros o limpia la búsqueda para ver más resultados."
                      : "Cuando asignes usuarios a este viaje, verás sus cuotas y estados de pago aquí."}
                  </p>
                </div>
              ) : null}

              <div className={styles.pagination}>
                <button
                  type="button"
                  className={styles.pageButton}
                  onClick={handlePrevPage}
                  disabled={params.page === 0}
                  aria-label="Página anterior"
                >
                  ←
                </button>
                <span>
                  Página {totalPages === 0 ? 1 : params.page + 1} de {totalPages}
                </span>
                <button
                  type="button"
                  className={styles.pageButton}
                  onClick={handleNextPage}
                  disabled={params.page >= totalPages - 1}
                  aria-label="Página siguiente"
                >
                  →
                </button>
              </div>
            </div>
          </RequestState>

          {selected ? (
            <InstallmentDrawer selected={selected} onClose={() => setSelected(null)} />
          ) : null}
        </div>
      </section>
    </CommonLayout>
  );
}

type InstallmentDrawerProps = {
  selected: SelectedInstallment;
  onClose: () => void;
};

function InstallmentDrawer({ selected, onClose }: InstallmentDrawerProps) {
  const { row, installment } = selected;

  useEffect(() => {
    const handleKeyDown = (event: KeyboardEvent) => {
      if (event.key === "Escape") {
        event.preventDefault();
        onClose();
      }
    };

    document.addEventListener("keydown", handleKeyDown);
    return () => document.removeEventListener("keydown", handleKeyDown);
  }, [onClose]);

  return (
    <div
      className={styles.drawerOverlay}
      role="presentation"
      onClick={(event) => {
        if (event.target === event.currentTarget) {
          onClose();
        }
      }}
    >
      <aside
        className={styles.drawer}
        role="complementary"
        aria-label="Detalle de cuota"
      >
        <header className={styles.drawerHeader}>
          <h2 className={styles.drawerTitle}>
            Cuota {installment.installmentNumber} · {currencyFormatter.format(installment.totalDue)}
          </h2>
          <button
            type="button"
            className={styles.drawerCloseButton}
            onClick={onClose}
          >
            Cerrar
          </button>
        </header>
        <div className={styles.drawerBody}>
          <section className={styles.drawerSection}>
            <div className={styles.drawerLabel}>Participante</div>
            <div>
              <span className={styles.strong}>
                {row.lastname}, {row.name}
              </span>
              <div>{row.email}</div>
              {row.phone ? <div>Tel: {row.phone}</div> : null}
            </div>
          </section>

          <section className={styles.drawerSection}>
            <div className={styles.drawerLabel}>Detalle de importes</div>
            <div>
              <div>
                Capital base:{" "}
                <span className={styles.strong}>
                  {currencyFormatter.format(installment.capitalAmount)}
                </span>
              </div>
              <div>
                Retroactivo acumulado:{" "}
                <span className={styles.highlightPositive}>
                  {currencyFormatter.format(installment.retroactiveAmount)}
                </span>
              </div>
              <div>
                Recargo por mora:{" "}
                <span className={styles.highlightDanger}>
                  {currencyFormatter.format(installment.fineAmount)}
                </span>
              </div>
              <div>
                Total exigible:{" "}
                <span className={styles.strong}>
                  {currencyFormatter.format(installment.totalDue)}
                </span>
              </div>
            </div>
          </section>

          <section className={styles.drawerSection}>
            <div className={styles.drawerLabel}>Estado</div>
            <div>
              <StatusBadge status={installment.status} />
            </div>
          </section>

          <section className={styles.drawerSection}>
            <div className={styles.drawerLabel}>Fecha de vencimiento</div>
            <div>{formatDateDisplay(installment.dueDate)}</div>
          </section>
        </div>
      </aside>
    </div>
  );
}

function getStatusClass(status: SpreadsheetRowInstallmentDTO["status"]): { pill: string; dot: string } {
  switch (status) {
    case "GREEN":
      return { pill: styles.statusGreen, dot: styles.statusGreenDot };
    case "YELLOW":
      return { pill: styles.statusYellow, dot: styles.statusYellowDot };
    case "RED":
      return { pill: styles.statusRed, dot: styles.statusRedDot };
    case "RETROACTIVE":
      return { pill: styles.statusRetro, dot: styles.statusRetroDot };
    default:
      return { pill: "", dot: "" };
  }
}

function getStatusIcon(status: SpreadsheetRowInstallmentDTO["status"]): string {
  switch (status) {
    case "GREEN":
      return "✓";
    case "YELLOW":
      return "•";
    case "RED":
      return "⚠";
    case "RETROACTIVE":
      return "↩";
    default:
      return "";
  }
}

type StatusBadgeProps = {
  status: SpreadsheetRowInstallmentDTO["status"];
};

function StatusBadge({ status }: StatusBadgeProps) {
  const classes = getStatusClass(status);
  const icon = getStatusIcon(status);

  return (
    <span className={`${styles.statusPill} ${classes.pill}`}>
      <span className={`${styles.statusDot} ${classes.dot}`} />
      <span>{icon}</span>
      <span>{status}</span>
    </span>
  );
}

function formatDateDisplay(isoDate: string): string {
  const date = new Date(`${isoDate}T00:00:00`);
  return Number.isNaN(date.getTime()) ? isoDate : dateFormatter.format(date);
}

