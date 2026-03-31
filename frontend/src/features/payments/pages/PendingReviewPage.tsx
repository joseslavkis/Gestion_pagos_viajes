import { useMemo, useState } from "react";

import { CommonLayout } from "@/components/CommonLayout/CommonLayout";
import { RequestState } from "@/components/ui/RequestState/RequestState";
import {
  usePendingReviewPayments,
  useReviewPayment,
} from "@/features/payments/services/payments-service";
import type {
  PendingPaymentReviewDTO,
  PendingPaymentReviewLineDTO,
  ReceiptStatus,
} from "@/features/payments/types/payments-dtos";

import styles from "./PendingReviewPage.module.css";

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

const receiptStatusLabels: Record<string, string> = {
  PENDING: "Pendiente",
  APPROVED: "Aprobada",
  REJECTED: "Rechazada",
};

function formatMoneyByCurrency(amount: number, currency: "ARS" | "USD"): string {
  return new Intl.NumberFormat("es-AR", {
    style: "currency",
    currency,
  }).format(amount);
}

function formatDate(isoDate: string): string {
  const date = new Date(`${isoDate}T00:00:00`);
  return Number.isNaN(date.getTime()) ? isoDate : dateFormatter.format(date);
}

function getBatchKey(item: PendingPaymentReviewDTO): string {
  if (item.batchId != null) {
    return `batch-${item.batchId}`;
  }
  return `legacy-${item.receipts[0]?.receiptId ?? `${item.tripId}-${item.userId}`}`;
}

function formatInstallmentList(lines: PendingPaymentReviewLineDTO[]): string {
  return lines.map((line) => `#${line.installmentNumber}`).join(", ");
}

export function PendingReviewPage() {
  const { data, isLoading, error } = usePendingReviewPayments();
  const reviewPayment = useReviewPayment();

  const [search, setSearch] = useState("");
  const [expandedBatchKeys, setExpandedBatchKeys] = useState<string[]>([]);
  const [rejectingReceiptId, setRejectingReceiptId] = useState<number | null>(null);
  const [rejectObservation, setRejectObservation] = useState("");
  const [actionError, setActionError] = useState<string | null>(null);

  const items = useMemo(() => {
    const baseItems = data ?? [];
    const term = search.trim().toLowerCase();

    if (term.length === 0) {
      return baseItems;
    }

    return baseItems.filter((item) => {
      const haystack = [
        item.userName,
        item.userLastname,
        item.userEmail,
        item.tripName,
        item.studentName ?? "",
        item.studentDni ?? "",
        item.bankAccountDisplayName ?? "",
        item.bankAccountAlias ?? "",
        formatInstallmentList(item.receipts),
      ]
        .join(" ")
        .toLowerCase();
      return haystack.includes(term);
    });
  }, [data, search]);

  const handleApprove = async (line: PendingPaymentReviewLineDTO) => {
    setActionError(null);
    try {
      await reviewPayment.mutateAsync({
        id: line.receiptId,
        installmentId: line.installmentId,
        data: { decision: "APPROVED" as ReceiptStatus },
      });
    } catch (reviewError) {
      setActionError(reviewError instanceof Error ? reviewError.message : "No se pudo aprobar el comprobante.");
    }
  };

  const handleReject = async (line: PendingPaymentReviewLineDTO) => {
    setActionError(null);
    try {
      await reviewPayment.mutateAsync({
        id: line.receiptId,
        installmentId: line.installmentId,
        data: { decision: "REJECTED" as ReceiptStatus, adminObservation: rejectObservation },
      });
      setRejectingReceiptId(null);
      setRejectObservation("");
    } catch (reviewError) {
      setActionError(reviewError instanceof Error ? reviewError.message : "No se pudo rechazar el comprobante.");
    }
  };

  const toggleBatch = (batchKey: string) => {
    setExpandedBatchKeys((current) =>
      current.includes(batchKey)
        ? current.filter((item) => item !== batchKey)
        : [...current, batchKey],
    );
  };

  return (
    <CommonLayout>
      <section className={styles.page}>
        <div className={styles.container}>
          <header className={styles.header}>
            <div>
              <h1 className={styles.title}>Pendientes de revisión</h1>
              <p className={styles.subtitle}>
                Cada envío entra agrupado por comprobante. Desplegalo para revisar cuota por cuota.
              </p>
            </div>
            <label className={styles.searchBox}>
              <span>Buscar</span>
              <input
                value={search}
                onChange={(event) => setSearch(event.target.value)}
                placeholder="Usuario, email o viaje"
              />
            </label>
          </header>

          <RequestState isLoading={isLoading} error={error ?? null} loadingLabel="Cargando pendientes...">
            {items.length === 0 ? <p className={styles.emptyText}>No hay comprobantes pendientes de revisión.</p> : null}
            <div className={styles.list}>
              {items.map((item) => {
                const batchKey = getBatchKey(item);
                const isExpanded = expandedBatchKeys.includes(batchKey);
                const pendingLines = item.receipts.filter((line) => line.status === "PENDING");

                return (
                  <article key={batchKey} className={styles.card}>
                    <div className={styles.cardHeader}>
                      <div>
                        <h2 className={styles.cardTitle}>{item.userLastname}, {item.userName}</h2>
                        <p className={styles.cardSubtitle}>
                          {item.userEmail} · {item.studentName || "Sin alumno informado"}
                          {item.studentDni ? ` · DNI ${item.studentDni}` : ""}
                        </p>
                      </div>
                      <span className={styles.pendingBadge}>
                        {pendingLines.length} pendiente{pendingLines.length === 1 ? "" : "s"}
                      </span>
                    </div>

                    <div className={styles.grid}>
                      <div>
                        <span className={styles.label}>Viaje</span>
                        <p className={styles.value}>{item.tripName}</p>
                      </div>
                      <div>
                        <span className={styles.label}>Cuotas</span>
                        <p className={styles.value}>{formatInstallmentList(item.receipts)}</p>
                      </div>
                      <div>
                        <span className={styles.label}>Monto total informado</span>
                        <p className={styles.value}>{formatMoneyByCurrency(item.reportedAmount, item.paymentCurrency)}</p>
                      </div>
                      <div>
                        <span className={styles.label}>Método</span>
                        <p className={styles.value}>{paymentMethodLabels[item.paymentMethod] ?? item.paymentMethod}</p>
                      </div>
                      <div>
                        <span className={styles.label}>Cuenta acreditada</span>
                        <p className={styles.value}>
                          {item.bankAccountDisplayName ?? "Cuenta no informada"}
                          {item.bankAccountAlias ? ` · ${item.bankAccountAlias}` : ""}
                        </p>
                      </div>
                      <div>
                        <span className={styles.label}>Fecha de pago</span>
                        <p className={styles.value}>{formatDate(item.reportedPaymentDate)}</p>
                      </div>
                    </div>

                    {item.exchangeRate != null ? (
                      <p className={styles.exchangeInfo}>
                        Pagó {formatMoneyByCurrency(item.reportedAmount, item.paymentCurrency)} · tipo de cambio {formatMoneyByCurrency(item.exchangeRate, "ARS")} · equivalente {formatMoneyByCurrency(item.amountInTripCurrency, item.tripCurrency)}
                      </p>
                    ) : null}

                    {item.fileKey ? (
                      <div className={styles.attachmentBox}>
                        {item.fileKey.startsWith("data:image") ? (
                          <img src={item.fileKey} alt="Comprobante" className={styles.attachmentImage} />
                        ) : (
                          <a href={item.fileKey} target="_blank" rel="noreferrer" className={styles.linkButton}>
                            Ver comprobante adjunto
                          </a>
                        )}
                      </div>
                    ) : null}

                    <div className={styles.actionsRow}>
                      <button
                        type="button"
                        className={styles.secondaryButton}
                        onClick={() => toggleBatch(batchKey)}
                      >
                        {isExpanded ? "Ocultar cuotas" : "Desglosar cuotas"}
                      </button>
                    </div>

                    {isExpanded ? (
                      <div style={{ display: "grid", gap: 12 }}>
                        {item.receipts.map((line) => (
                          <div
                            key={line.receiptId}
                            style={{
                              border: "1px solid #d8e6fb",
                              borderRadius: 12,
                              padding: 12,
                              display: "grid",
                              gap: 10,
                              background: "#f8fbff",
                            }}
                          >
                            <div className={styles.cardHeader}>
                              <div>
                                <h3 className={styles.cardTitle}>Cuota #{line.installmentNumber}</h3>
                                <p className={styles.cardSubtitle}>
                                  Vence {formatDate(line.installmentDueDate)} · total {formatMoneyByCurrency(line.installmentTotalDue, item.tripCurrency)}
                                </p>
                              </div>
                              <span className={styles.pendingBadge}>
                                {receiptStatusLabels[line.status] ?? line.status}
                              </span>
                            </div>

                            <div className={styles.grid}>
                              <div>
                                <span className={styles.label}>Importe línea</span>
                                <p className={styles.value}>{formatMoneyByCurrency(line.reportedAmount, item.paymentCurrency)}</p>
                              </div>
                              <div>
                                <span className={styles.label}>Importe viaje</span>
                                <p className={styles.value}>{formatMoneyByCurrency(line.amountInTripCurrency, item.tripCurrency)}</p>
                              </div>
                              <div>
                                <span className={styles.label}>Estado</span>
                                <p className={styles.value}>{receiptStatusLabels[line.status] ?? line.status}</p>
                              </div>
                            </div>

                            {line.adminObservation ? (
                              <p className={styles.errorText}>{line.adminObservation}</p>
                            ) : null}

                            {line.status === "PENDING" ? (
                              <div className={styles.actionsRow}>
                                <button
                                  type="button"
                                  className={styles.primaryButton}
                                  disabled={reviewPayment.isPending}
                                  onClick={() => handleApprove(line)}
                                >
                                  Aprobar cuota
                                </button>
                                <button
                                  type="button"
                                  className={styles.secondaryButton}
                                  disabled={reviewPayment.isPending}
                                  onClick={() => {
                                    setRejectingReceiptId(line.receiptId);
                                    setRejectObservation("");
                                  }}
                                >
                                  Rechazar cuota
                                </button>
                              </div>
                            ) : null}

                            {rejectingReceiptId === line.receiptId ? (
                              <div className={styles.rejectBox}>
                                <input
                                  value={rejectObservation}
                                  onChange={(event) => setRejectObservation(event.target.value)}
                                  placeholder="Observación obligatoria para rechazar"
                                />
                                <div className={styles.actionsRow}>
                                  <button
                                    type="button"
                                    className={styles.secondaryButton}
                                    onClick={() => {
                                      setRejectingReceiptId(null);
                                      setRejectObservation("");
                                    }}
                                  >
                                    Cancelar
                                  </button>
                                  <button
                                    type="button"
                                    className={styles.primaryButton}
                                    disabled={reviewPayment.isPending}
                                    onClick={() => handleReject(line)}
                                  >
                                    Confirmar rechazo
                                  </button>
                                </div>
                              </div>
                            ) : null}
                          </div>
                        ))}
                      </div>
                    ) : null}
                  </article>
                );
              })}
            </div>
            {actionError ? <p className={styles.errorText}>{actionError}</p> : null}
          </RequestState>
        </div>
      </section>
    </CommonLayout>
  );
}
