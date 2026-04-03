import { useEffect, useMemo, useRef, useState } from "react";
import { useLocation } from "wouter";

import { CommonLayout } from "@/components/CommonLayout/CommonLayout";
import { RequestState } from "@/components/ui/RequestState/RequestState";
import { PaymentDrawer } from "@/features/payments/components/PaymentDrawer";
import { downloadSpreadsheetExcel, useSpreadsheet, useTrip } from "@/features/trips/services/trips-service";
import type {
  SpreadsheetParams,
  SpreadsheetRowDTO,
  SpreadsheetRowInstallmentDTO,
} from "@/features/trips/types/trips-dtos";
import { ApiError } from "@/lib/api-error";
import { createGsapMatchMedia, getMotionProfile, gsap, useGSAP } from "@/lib/gsap";
import { useToken } from "@/lib/session";

import styles from "./SpreadsheetPage.module.css";

const currencyFormatter = new Intl.NumberFormat("es-AR", {
  style: "currency",
  currency: "ARS",
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
  const [tokenState] = useToken();

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
  const [isScrollableHorizontally, setIsScrollableHorizontally] = useState(false);
  const [canScrollRight, setCanScrollRight] = useState(false);
  const [selected, setSelected] = useState<SelectedInstallment | null>(null);
  const [isExporting, setIsExporting] = useState(false);
  const [exportError, setExportError] = useState<string | null>(null);
  const tableContainerRef = useRef<HTMLDivElement | null>(null);
  const pageRef = useRef<HTMLElement | null>(null);

  const { data, isLoading, error } = useSpreadsheet(tripId, params);
  const { data: tripData } = useTrip(tripId);

  const tripCurrencyFormatter = useMemo(() => {
    if (tripData?.currency === "USD") {
      return new Intl.NumberFormat("es-AR", { style: "currency", currency: "USD" });
    }
    return currencyFormatter;
  }, [tripData?.currency]);

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

    const updateScrollAffordances = () => {
      const maxScrollLeft = Math.max(0, element.scrollWidth - element.clientWidth);
      const nextHasScrolledHorizontally = element.scrollLeft > 4;

      setHasScrolledHorizontally(nextHasScrolledHorizontally);
      setIsScrollableHorizontally(maxScrollLeft > 4);
      setCanScrollRight(element.scrollLeft < maxScrollLeft - 4);
    };

    const resizeObserver =
      typeof ResizeObserver !== "undefined"
        ? new ResizeObserver(() => updateScrollAffordances())
        : null;
    const table = element.querySelector("table");
    const animationFrameId = window.requestAnimationFrame(updateScrollAffordances);

    resizeObserver?.observe(element);
    if (table instanceof HTMLElement) {
      resizeObserver?.observe(table);
    }

    element.addEventListener("scroll", updateScrollAffordances, { passive: true });
    window.addEventListener("resize", updateScrollAffordances);

    return () => {
      window.cancelAnimationFrame(animationFrameId);
      resizeObserver?.disconnect();
      element.removeEventListener("scroll", updateScrollAffordances);
      window.removeEventListener("resize", updateScrollAffordances);
    };
  }, [data?.installmentsCount, data?.rows?.length, isLoading, params.page]);

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

  const handleClearSearch = () => {
    setRawSearch("");
    setParams((current) => ({
      ...current,
      page: 0,
      search: undefined,
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

  const handleExport = async () => {
    if (tokenState.state !== "LOGGED_IN") return;
    setIsExporting(true);
    setExportError(null);
    try {
      await downloadSpreadsheetExcel(
        tripId,
        data?.tripName ?? String(tripId),
        tokenState.accessToken,
      );
    } catch (err) {
      const message = err instanceof ApiError ? err.message : "No se pudo descargar el archivo.";
      setExportError(message);
    } finally {
      setIsExporting(false);
    }
  };

  const rows = data?.rows ?? [];
  const installmentsCount = data?.installmentsCount ?? 0;

  useGSAP(
    () => {
      if (!pageRef.current) {
        return;
      }

      const motion = getMotionProfile();
      const mm = createGsapMatchMedia();

      if (!mm) {
        const targets = [
          pageRef.current?.querySelector(`.${styles.header}`),
          ...Array.from(pageRef.current?.querySelectorAll("tbody tr") ?? []),
        ];
        gsap.set(targets, { clearProps: "opacity,visibility,transform" });
        return;
      }

      mm.add("(prefers-reduced-motion: reduce)", () => {
        const targets = [
          pageRef.current?.querySelector(`.${styles.header}`),
          ...Array.from(pageRef.current?.querySelectorAll("tbody tr") ?? []),
        ];
        gsap.set(targets, { clearProps: "opacity,visibility,transform" });
      });

      mm.add("(prefers-reduced-motion: no-preference)", () => {
        const header = pageRef.current?.querySelector(`.${styles.header}`);
        const rowsInTable = pageRef.current?.querySelectorAll("tbody tr");

        if (header) {
          gsap.fromTo(
            header,
            { autoAlpha: 0, y: motion.distanceSm },
            { autoAlpha: 1, y: 0, duration: motion.durationFast, ease: "power2.out" },
          );
        }

        if (rowsInTable && rowsInTable.length > 0) {
          gsap.fromTo(
            rowsInTable,
            { autoAlpha: 0, y: motion.distanceSm },
            {
              autoAlpha: 1,
              y: 0,
              duration: motion.durationFast,
              stagger: motion.staggerFast,
              ease: "power2.out",
              clearProps: "opacity,visibility,transform",
            },
          );
        }
      });

      return () => mm.revert();
    },
    { dependencies: [isLoading, rows.length, params.page], scope: pageRef, revertOnUpdate: true },
  );

  return (
    <CommonLayout>
      <section ref={pageRef} className={styles.page}>
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
              <div className={`${styles.exportWrapper} ${styles.desktopExportWrapper}`}>
                <button
                  type="button"
                  className={styles.exportButton}
                  onClick={handleExport}
                  disabled={isExporting || !data}
                  aria-label="Exportar planilla como archivo Excel"
                >
                  {isExporting ? "Descargando..." : "⬇ Exportar Excel"}
                </button>
                {exportError ? (
                  <p className={styles.exportError} role="alert">
                    {exportError}
                  </p>
                ) : null}
              </div>
              <div className={styles.titleBlock}>
                <h1 className={styles.title}>{data?.tripName ?? "Planilla de viaje"}</h1>
                <p className={styles.subtitle}>
                  Vista de cuotas y estados de pago por participante. Moneda: {tripData?.currency ?? "ARS"}
                </p>
                {data ? (
                  <span className={styles.counter}>
                    {data.totalElements} participantes · {data.installmentsCount} cuotas
                  </span>
                ) : null}
              </div>
            </div>

            <div className={styles.toolbar}>
              <div className={styles.searchField}>
                <input
                  type="search"
                  className={styles.searchInput}
                  placeholder="Buscar por nombre, apellido o email..."
                  value={rawSearch}
                  onChange={(event) => setRawSearch(event.target.value)}
                />
                {rawSearch.length > 0 ? (
                  <button
                    type="button"
                    className={styles.clearSearchButton}
                    onClick={handleClearSearch}
                    aria-label="Limpiar búsqueda"
                  >
                    ×
                  </button>
                ) : null}
              </div>
              <select
                className={styles.select}
                value={params.status ?? ""}
                onChange={(event) => handleStatusChange(event.target.value)}
              >
                <option value="">Todos los estados</option>
                <option value="GREEN">Verde — Pagada</option>
                <option value="YELLOW">Amarillo — Vence pronto</option>
                <option value="RED">Rojo — Vencida</option>
                <option value="RETROACTIVE">Rojo — Deuda retroactiva</option>
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
            <div
              id="spreadsheet-table-top"
              className={`${styles.tableContainer} ${
                hasScrolledHorizontally ? styles.tableContainerScrolled : ""
              }`}
            >
              {isScrollableHorizontally && !hasScrolledHorizontally ? (
                <div className={styles.scrollHint}>Deslizá la tabla para ver más cuotas</div>
              ) : null}
              <div
                ref={tableContainerRef}
                className={`${styles.scrollShell} ${canScrollRight ? styles.scrollShellFadeRight : ""}`}
              >
                <table className={styles.table}>
                  <thead className={styles.thead}>
                    <tr>
                      <th className={`${styles.th} ${styles.userCol}`}>Participante</th>
                      {Array.from({ length: installmentsCount }).map((_, index) => (
                        <th key={index} className={`${styles.th} ${styles.quotaHeader}`}>
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
                      : rows.map((row, index) => (
                          <tr key={`${row.userId}-${row.studentId ?? "legacy"}`} className={index % 2 === 0 ? styles.rowEven : styles.rowOdd}>
                            <td
                              className={`${styles.td} ${styles.userCell} ${
                                index % 2 === 0 ? styles.userCellEven : styles.userCellOdd
                              }`}
                            >
                              <span className={styles.userMain}>
                                {row.lastname}, {row.name}
                                {row.userCompleted ? (
                                  <span className={styles.completedBadge}>✓ Completado</span>
                                ) : null}
                              </span>
                              <span className={styles.userSecondary}>{row.email}</span>
                              {row.phone ? (
                                <span className={styles.userSecondary}>Tel: {row.phone}</span>
                              ) : null}
                              {row.studentName ? (
                                <span className={styles.userSecondary}>Alumno: {row.studentName}</span>
                              ) : null}
                              {row.studentDni ? (
                                <span className={styles.userSecondary}>DNI alumno: {row.studentDni}</span>
                              ) : null}
                            </td>
                            {Array.from({ length: installmentsCount }).map((_, installmentIndex) => {
                              const installment = row.installments.find(
                                (item) => item.installmentNumber === installmentIndex + 1,
                              );
                              if (!installment) {
                                return (
                                  <td key={installmentIndex} className={styles.td}>
                                    -
                                  </td>
                                );
                              }

                              const statusClass = getStatusClass(installment.uiStatusTone);
                              const icon = installment.uiStatusLabel;

                              return (
                                <td
                                  key={installmentIndex}
                                  className={`${styles.td} ${styles.amountCell}`}
                                  onClick={() => setSelected({ row, installment })}
                                >
                                  <div className={styles.cellContent}>
                                    <span className={styles.cellAmount}>
                                      {tripCurrencyFormatter.format(installment.totalDue)}
                                    </span>
                                    {installment.paidAmount > 0 &&
                                    installment.paidAmount < installment.totalDue ? (
                                      <div className={styles.cellPartial}>
                                        <span>
                                          Abonado: {tripCurrencyFormatter.format(installment.paidAmount)}
                                        </span>
                                        <span>
                                          Resta:{" "}
                                          {tripCurrencyFormatter.format(
                                            installment.totalDue - installment.paidAmount,
                                          )}
                                        </span>
                                      </div>
                                    ) : null}
                                    <span className={`${styles.statusPill} ${statusClass.pill}`}>
                                      <span className={`${styles.statusDot} ${statusClass.dot}`} />
                                      <span>{icon}</span>
                                    </span>
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
              <div className={styles.mobileExportBar}>
                <button
                  type="button"
                  className={`${styles.exportButton} ${styles.mobileExportButton}`}
                  onClick={handleExport}
                  disabled={isExporting || !data}
                  aria-label="Descargar planilla como archivo Excel"
                >
                  {isExporting ? "Descargando..." : "Descargar Excel"}
                </button>
                {exportError ? (
                  <p className={styles.exportError} role="alert">
                    {exportError}
                  </p>
                ) : null}
              </div>
            </div>
          </RequestState>

          {selected ? (
            <PaymentDrawer
              installment={selected.installment}
              row={selected.row}
              onClose={() => setSelected(null)}
            />
          ) : null}
        </div>
      </section>
    </CommonLayout>
  );
}

function getStatusClass(
  tone: SpreadsheetRowInstallmentDTO["uiStatusTone"],
): { pill: string; dot: string } {
  switch (tone) {
    case "green":
      return { pill: styles.statusGreen, dot: styles.statusGreenDot };
    case "yellow":
      return { pill: styles.statusYellow, dot: styles.statusYellowDot };
    case "red":
      return { pill: styles.statusRed, dot: styles.statusRedDot };
    default:
      return { pill: "", dot: "" };
  }
}
