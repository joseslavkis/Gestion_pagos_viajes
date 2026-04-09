import { useMemo, useState } from "react";

import { CommonLayout } from "@/components/CommonLayout/CommonLayout";
import { RequestState } from "@/components/ui/RequestState/RequestState";
import {
  usePendingReviewPayments,
  useReviewPayment,
} from "@/features/payments/services/payments-service";
import { isImageAttachment } from "@/features/payments/lib/attachment-preview";
import type {
  PaymentBatchInstallmentDTO,
  PendingPaymentReviewDTO,
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

function formatInstallmentList(allocations: PaymentBatchInstallmentDTO[]): string {
  return allocations.map((allocation) => `#${allocation.installmentNumber}`).join(", ");
}

function parseAmount(value: string, fallback: number): number {
  const normalized = value.replace(",", ".").trim();
  if (normalized.length === 0) {
    return fallback;
  }

  const parsed = Number.parseFloat(normalized);
  return Number.isFinite(parsed) ? parsed : fallback;
}

export function PendingReviewPage() {
  const { data, isLoading, error } = usePendingReviewPayments();
  const reviewPayment = useReviewPayment();

  const [search, setSearch] = useState("");
  const [expandedSubmissionIds, setExpandedSubmissionIds] = useState<number[]>([]);
  const [approvedAmounts, setApprovedAmounts] = useState<Record<number, string>>({});
  const [observations, setObservations] = useState<Record<number, string>>({});
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
        formatInstallmentList(item.allocations),
      ]
        .join(" ")
        .toLowerCase();
      return haystack.includes(term);
    });
  }, [data, search]);

  const toggleSubmission = (submissionId: number) => {
    setExpandedSubmissionIds((current) =>
      current.includes(submissionId)
        ? current.filter((item) => item !== submissionId)
        : [...current, submissionId],
    );
  };

  const submitDecision = async (item: PendingPaymentReviewDTO, approvedAmount: number) => {
    setActionError(null);

    const safeApprovedAmount = Math.max(0, approvedAmount);
    const observation = observations[item.submissionId]?.trim() ?? "";

    if (safeApprovedAmount < item.reportedAmount && observation.length === 0) {
      setActionError("La observación es obligatoria cuando no se aprueba el monto completo.");
      return;
    }

    try {
      await reviewPayment.mutateAsync({
        id: item.submissionId,
        data: {
          approvedAmount: safeApprovedAmount,
          adminObservation: observation.length > 0 ? observation : undefined,
        },
      });
    } catch (reviewError) {
      setActionError(reviewError instanceof Error ? reviewError.message : "No se pudo guardar la decisión.");
    }
  };

  return (
    <CommonLayout>
      <section className={styles.page}>
        <div className={styles.container}>
          <header className={styles.header}>
            <div>
              <h1 className={styles.title}>Pendientes de revisión</h1>
              <p className={styles.subtitle}>
                Cada envío representa un pago completo. Podés aprobarlo total o parcialmente, o rechazarlo.
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
                const isExpanded = expandedSubmissionIds.includes(item.submissionId);
                const approvedAmountInput = approvedAmounts[item.submissionId] ?? String(item.reportedAmount);
                const approvedAmountValue = parseAmount(approvedAmountInput, item.reportedAmount);
                const observation = observations[item.submissionId] ?? "";

                return (
                  <article key={item.submissionId} className={styles.card}>
                    <div className={styles.cardHeader}>
                      <div>
                        <h2 className={styles.cardTitle}>{item.userLastname}, {item.userName}</h2>
                        <p className={styles.cardSubtitle}>
                          {item.userEmail} · {item.studentName || "Sin alumno informado"}
                          {item.studentDni ? ` · DNI ${item.studentDni}` : ""}
                        </p>
                      </div>
                      <span className={styles.pendingBadge}>Pago pendiente</span>
                    </div>

                    <div className={styles.grid}>
                      <div>
                        <span className={styles.label}>Viaje</span>
                        <p className={styles.value}>{item.tripName}</p>
                      </div>
                      <div>
                        <span className={styles.label}>Imputación prevista</span>
                        <p className={styles.value}>{formatInstallmentList(item.allocations)}</p>
                      </div>
                      <div>
                        <span className={styles.label}>Monto informado</span>
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

                    <p className={styles.exchangeInfo}>
                      Equivale a {formatMoneyByCurrency(item.amountInTripCurrency, item.tripCurrency)} del viaje
                      {item.exchangeRate != null
                        ? ` · tipo de cambio ${formatMoneyByCurrency(item.exchangeRate, "ARS")}`
                        : ""}
                    </p>

                    {item.fileKey ? (
                      <div className={styles.attachmentBox}>
                        {isImageAttachment(item.fileKey) ? (
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
                        className={styles.primaryButton}
                        disabled={reviewPayment.isPending}
                        onClick={() => submitDecision(item, item.reportedAmount)}
                      >
                        Aprobar total
                      </button>
                      <button
                        type="button"
                        className={styles.secondaryButton}
                        onClick={() => toggleSubmission(item.submissionId)}
                      >
                        {isExpanded ? "Ocultar detalle" : "Ver imputación y decidir"}
                      </button>
                    </div>

                    {isExpanded ? (
                      <div style={{ display: "grid", gap: 12 }}>
                        {item.allocations.map((allocation) => (
                          <div
                            key={`${item.submissionId}-${allocation.installmentId}`}
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
                                <h3 className={styles.cardTitle}>Cuota #{allocation.installmentNumber}</h3>
                                <p className={styles.cardSubtitle}>
                                  Vence {formatDate(allocation.dueDate)} · total {formatMoneyByCurrency(allocation.totalDue, item.tripCurrency)}
                                </p>
                              </div>
                              <span className={styles.pendingBadge}>
                                {formatMoneyByCurrency(allocation.amountInTripCurrency, item.tripCurrency)}
                              </span>
                            </div>
                            <div className={styles.grid}>
                              <div>
                                <span className={styles.label}>Saldo previo</span>
                                <p className={styles.value}>{formatMoneyByCurrency(allocation.remainingAmount, item.tripCurrency)}</p>
                              </div>
                              <div>
                                <span className={styles.label}>Monto imputado</span>
                                <p className={styles.value}>{formatMoneyByCurrency(allocation.amountInTripCurrency, item.tripCurrency)}</p>
                              </div>
                            </div>
                          </div>
                        ))}

                        <div className={styles.rejectBox}>
                          <label className={styles.searchBox}>
                            <span>Monto a aprobar</span>
                            <input
                              value={approvedAmountInput}
                              onChange={(event) =>
                                setApprovedAmounts((current) => ({
                                  ...current,
                                  [item.submissionId]: event.target.value,
                                }))
                              }
                              placeholder="0.00"
                              inputMode="decimal"
                            />
                          </label>

                          <label className={styles.searchBox}>
                            <span>Observación admin</span>
                            <input
                              value={observation}
                              onChange={(event) =>
                                setObservations((current) => ({
                                  ...current,
                                  [item.submissionId]: event.target.value,
                                }))
                              }
                              placeholder="Obligatoria si aprobás menos del total"
                            />
                          </label>

                          <div className={styles.actionsRow}>
                            <button
                              type="button"
                              className={styles.secondaryButton}
                              disabled={reviewPayment.isPending}
                              onClick={() => submitDecision(item, 0)}
                            >
                              Rechazar total
                            </button>
                            <button
                              type="button"
                              className={styles.primaryButton}
                              disabled={reviewPayment.isPending}
                              onClick={() => submitDecision(item, approvedAmountValue)}
                            >
                              Guardar decisión
                            </button>
                          </div>
                        </div>
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
