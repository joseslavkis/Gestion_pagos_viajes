import { useEffect, useMemo, useRef, useState } from "react";
import { useLocation } from "wouter";

import { CommonLayout } from "@/components/CommonLayout/CommonLayout";
import { RequestState } from "@/components/ui/RequestState/RequestState";
import { PaymentDrawer } from "@/features/payments/components/PaymentDrawer";
import {
  getSpreadsheetParticipantParentLabel,
  getSpreadsheetParticipantPrimaryLabel,
  getSpreadsheetStatusVariant,
} from "@/features/trips/lib/spreadsheet-ui";
import { downloadSpreadsheetExcel, useComprobantes, useSpreadsheet, useTrip } from "@/features/trips/services/trips-service";
import type {
  SpreadsheetParams,
  SpreadsheetReceiptParams,
  SpreadsheetReceiptRowDTO,
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

const usdCurrencyFormatter = new Intl.NumberFormat("es-AR", {
  style: "currency",
  currency: "USD",
});

const dateFormatter = new Intl.DateTimeFormat("es-AR", {
  day: "2-digit",
  month: "2-digit",
  year: "numeric",
  timeZone: "America/Argentina/Buenos_Aires",
});

const paymentMethodLabels: Record<string, string> = {
  BANK_TRANSFER: "Transferencia bancaria",
  CASH: "Efectivo",
  DEPOSIT: "Depósito",
  OTHER: "Otro",
};

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
    sortBy: "student",
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

  const [view, setView] = useState<"planilla" | "comprobantes">(() => {
    const stored = sessionStorage.getItem("spreadsheetView");
    return stored === "comprobantes" ? "comprobantes" : "planilla";
  });
  const [comprobantesParams, setComprobantesParams] = useState<SpreadsheetReceiptParams>({
    sortBy: "reportedPaymentDate",
    order: "desc",
    page: 0,
    size: 20,
  });

  const { data, isLoading, error } = useSpreadsheet(tripId, params);
  const { data: comprobantesData, isLoading: comprobantesLoading, error: comprobantesError } = useComprobantes(tripId, comprobantesParams);
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

  const orderMap: Record<string, Pick<SpreadsheetParams, "sortBy" | "order">> = {
    "student-asc": { sortBy: "student", order: "asc" },
    "student-desc": { sortBy: "student", order: "desc" },
    "parent-asc": { sortBy: "parent", order: "asc" },
    "parent-desc": { sortBy: "parent", order: "desc" },
    "email-asc": { sortBy: "email", order: "asc" },
    "date-asc": { sortBy: "date", order: "asc" },
    "date-desc": { sortBy: "date", order: "desc" },
  };

  const currentOrderKey =
    Object.entries(orderMap).find(
      ([, v]) => v.sortBy === params.sortBy && v.order === params.order,
    )?.[0] ?? "student-asc";

  const handleOrderChange = (value: string) => {
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
                  {view === "planilla"
                    ? `Vista de cuotas y estados de pago por participante. Moneda: ${tripData?.currency ?? "ARS"}`
                    : `Vista de comprobantes registrados para el viaje. Moneda del viaje: ${tripData?.currency ?? "ARS"}`}
                </p>
                {view === "planilla" && data ? (
                  <span className={styles.counter}>
                    {data.totalElements} participantes · {data.installmentsCount} cuotas
                  </span>
                ) : view === "comprobantes" && comprobantesData ? (
                  <span className={styles.counter}>{comprobantesData.totalElements} comprobantes</span>
                ) : null}
              </div>
            </div>

            <div className={styles.viewToggle}>
              <button
                type="button"
                className={`${styles.viewToggleOption} ${view === "planilla" ? styles.viewToggleOptionActive : ""}`}
                onClick={() => {
                  setView("planilla");
                  sessionStorage.setItem("spreadsheetView", "planilla");
                  setParams((c) => ({ ...c, page: 0 }));
                  setComprobantesParams((c) => ({ ...c, page: 0 }));
                }}
                disabled={view === "planilla"}
              >
                Planilla
              </button>
              <button
                type="button"
                className={`${styles.viewToggleOption} ${view === "comprobantes" ? styles.viewToggleOptionActive : ""}`}
                onClick={() => {
                  setView("comprobantes");
                  sessionStorage.setItem("spreadsheetView", "comprobantes");
                  setParams((c) => ({ ...c, page: 0 }));
                  setComprobantesParams((c) => ({ ...c, page: 0 }));
                }}
                disabled={view === "comprobantes"}
              >
                Comprobantes
              </button>
            </div>

            {view === "planilla" ? (
            <div className={styles.toolbar}>
              <div className={styles.searchField}>
                <input
                  type="search"
                  className={styles.searchInput}
                  placeholder="Buscar por alumno, responsable o email..."
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
                value={currentOrderKey}
                onChange={(event) => handleOrderChange(event.target.value)}
              >
                <option value="student-asc">Alumno A→Z</option>
                <option value="student-desc">Alumno Z→A</option>
                <option value="parent-asc">Responsable A→Z</option>
                <option value="parent-desc">Responsable Z→A</option>
                <option value="email-asc">Email A→Z</option>
                <option value="date-asc">Fecha ↑</option>
                <option value="date-desc">Fecha ↓</option>
              </select>
            </div>
            ) : (
            <div className={styles.toolbar}>
              <select
                className={styles.select}
                value={comprobantesParams.order}
                onChange={(event) => {
                  setComprobantesParams((c) => ({
                    ...c,
                    page: 0,
                    order: event.target.value as "asc" | "desc",
                  }));
                }}
              >
                <option value="desc">Fecha ↓</option>
                <option value="asc">Fecha ↑</option>
              </select>
            </div>
            )}
          </header>

          {view === "planilla" ? (
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
                                {getSpreadsheetParticipantPrimaryLabel(row)}
                                {row.userCompleted ? (
                                  <span className={styles.completedBadge}>✓ Completado</span>
                                ) : null}
                              </span>
                              {getSpreadsheetParticipantParentLabel(row) ? (
                                <span className={styles.userSecondary}>{getSpreadsheetParticipantParentLabel(row)}</span>
                              ) : null}
                              <span className={styles.userSecondary}>{row.email}</span>
                              {row.phone ? (
                                <span className={styles.userSecondary}>Tel: {row.phone}</span>
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

                              const statusClass = getStatusClass(getSpreadsheetStatusVariant(installment));
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
          ) : (
          <RequestState
            isLoading={comprobantesLoading}
            error={comprobantesError ?? null}
            loadingLabel="Cargando comprobantes..."
          >
            <div className={`${styles.tableContainer} ${hasScrolledHorizontally ? styles.tableContainerScrolled : ""}`}>
              <div
                ref={tableContainerRef}
                className={`${styles.scrollShell} ${canScrollRight ? styles.scrollShellFadeRight : ""}`}
              >
                <table className={`${styles.table} ${styles.comprobantesTable}`}>
                  <thead className={styles.thead}>
                    <tr>
                      <th className={styles.th}># Cuota</th>
                      <th className={styles.th}>Fecha vencimiento</th>
                      <th className={styles.th}>Apellido alumno</th>
                      <th className={styles.th}>Nombre alumno</th>
                      <th className={styles.th}>DNI alumno</th>
                      <th className={styles.th}>Fecha pago declarado</th>
                      <th className={styles.th}>Medio de pago</th>
                      <th className={styles.th}>Monto declarado</th>
                      <th className={styles.th}>Moneda</th>
                      <th className={styles.th}>Tipo de cambio</th>
                      <th className={styles.th}>Monto en moneda viaje</th>
                      <th className={styles.th}>Estado</th>
                      <th className={styles.th}>Observación admin</th>
                    </tr>
                  </thead>
                  <tbody>
                    {comprobantesLoading
                      ? Array.from({ length: 5 }).map((_, rowIndex) => (
                          <tr key={rowIndex} className={styles.skeletonRow}>
                            {Array.from({ length: 13 }).map((__, colIndex) => (
                              <td key={colIndex} className={styles.td}>
                                <div className={styles.skeletonBlock} />
                              </td>
                            ))}
                          </tr>
                        ))
                      : (comprobantesData?.content ?? []).map((receipt: SpreadsheetReceiptRowDTO, index: number) => (
                          <tr key={index} className={index % 2 === 0 ? styles.rowEven : styles.rowOdd}>
                            <td className={styles.td}>{receipt.installmentNumber ?? "-"}</td>
                            <td className={styles.td}>{formatDate(receipt.installmentDueDate)}</td>
                            <td className={styles.td}>{receipt.studentLastname ?? "-"}</td>
                            <td className={styles.td}>{receipt.studentName ?? "-"}</td>
                            <td className={styles.td}>{receipt.studentDni ?? "-"}</td>
                            <td className={styles.td}>{formatDate(receipt.reportedPaymentDate)}</td>
                            <td className={styles.td}>{formatPaymentMethod(receipt.paymentMethod)}</td>
                            <td className={styles.td}>
                              {formatMoneyByCurrency(receipt.reportedAmount, receipt.paymentCurrency ?? tripData?.currency ?? "ARS")}
                            </td>
                            <td className={styles.td}>{receipt.paymentCurrency ?? "-"}</td>
                            <td className={styles.td}>{receipt.exchangeRate != null ? receipt.exchangeRate.toFixed(2) : "-"}</td>
                            <td className={styles.td}>{tripCurrencyFormatter.format(receipt.amountInTripCurrency)}</td>
                            <td className={styles.td}>{receipt.status}</td>
                            <td className={styles.td}>{receipt.adminObservation ?? "-"}</td>
                          </tr>
                        ))}
                  </tbody>
                </table>
              </div>

              {!comprobantesLoading && (comprobantesData?.content ?? []).length === 0 ? (
                <div className={styles.emptyState}>
                  <h2 className={styles.emptyTitle}>No hay comprobantes registrados para este viaje</h2>
                </div>
              ) : null}

              <div className={styles.pagination}>
                <button
                  type="button"
                  className={styles.pageButton}
                  onClick={() => setComprobantesParams((c) => ({ ...c, page: Math.max(0, c.page - 1) }))}
                  disabled={comprobantesParams.page === 0}
                  aria-label="Página anterior"
                >
                  ←
                </button>
                <span>
                  Página {(comprobantesData?.totalPages ?? 0) === 0 ? 1 : comprobantesParams.page + 1} de {comprobantesData?.totalPages ?? 1}
                </span>
                <button
                  type="button"
                  className={styles.pageButton}
                  onClick={() => setComprobantesParams((c) => ({ ...c, page: c.page + 1 }))}
                  disabled={comprobantesParams.page >= (comprobantesData?.totalPages ?? 1) - 1}
                  aria-label="Página siguiente"
                >
                  →
                </button>
              </div>
            </div>
          </RequestState>
          )}

          {view === "planilla" && selected ? (
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

function formatDate(isoDate: string | null | undefined): string {
  if (!isoDate) {
    return "-";
  }

  const date = new Date(`${isoDate}T00:00:00`);
  return Number.isNaN(date.getTime()) ? isoDate : dateFormatter.format(date);
}

function formatMoneyByCurrency(amount: number, currency: string): string {
  if (currency === "USD") {
    return usdCurrencyFormatter.format(amount);
  }

  return currencyFormatter.format(amount);
}

function formatPaymentMethod(paymentMethod: string | null | undefined): string {
  if (!paymentMethod) {
    return "-";
  }

  return paymentMethodLabels[paymentMethod] ?? paymentMethod;
}

function getStatusClass(
  tone: ReturnType<typeof getSpreadsheetStatusVariant>,
): { pill: string; dot: string } {
  switch (tone) {
    case "green":
      return { pill: styles.statusGreen, dot: styles.statusGreenDot };
    case "neutral":
      return { pill: styles.statusNeutral, dot: styles.statusNeutralDot };
    case "yellow":
      return { pill: styles.statusYellow, dot: styles.statusYellowDot };
    case "red":
      return { pill: styles.statusRed, dot: styles.statusRedDot };
    default:
      return { pill: "", dot: "" };
  }
}
