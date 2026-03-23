import { useMemo, useState } from "react";

import { CommonLayout } from "@/components/CommonLayout/CommonLayout";
import { RequestState } from "@/components/ui/RequestState/RequestState";
import {
  usePendingReviewPayments,
  useReviewPayment,
} from "@/features/payments/services/payments-service";
import type { PendingPaymentReviewDTO, ReceiptStatus } from "@/features/payments/types/payments-dtos";

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
  CARD: "Tarjeta",
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

export function PendingReviewPage() {
  const { data, isLoading, error } = usePendingReviewPayments();
  const reviewPayment = useReviewPayment();

  const [search, setSearch] = useState("");
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
        item.bankAccountDisplayName ?? "",
        item.bankAccountAlias ?? "",
      ]
        .join(" ")
        .toLowerCase();
      return haystack.includes(term);
    });
  }, [data, search]);

  const handleApprove = async (item: PendingPaymentReviewDTO) => {
    setActionError(null);
    try {
      await reviewPayment.mutateAsync({
        id: item.receiptId,
        installmentId: item.installmentId,
        data: { decision: "APPROVED" as ReceiptStatus },
      });
    } catch (reviewError) {
      setActionError(reviewError instanceof Error ? reviewError.message : "No se pudo aprobar el comprobante.");
    }
  };

  const handleReject = async (item: PendingPaymentReviewDTO) => {
    setActionError(null);
    try {
      await reviewPayment.mutateAsync({
        id: item.receiptId,
        installmentId: item.installmentId,
        data: { decision: "REJECTED" as ReceiptStatus, adminObservation: rejectObservation },
      });
      setRejectingReceiptId(null);
      setRejectObservation("");
    } catch (reviewError) {
      setActionError(reviewError instanceof Error ? reviewError.message : "No se pudo rechazar el comprobante.");
    }
  };

  return (
    <CommonLayout>
      <section className={styles.page}>
        <div className={styles.container}>
          <header className={styles.header}>
            <div>
              <h1 className={styles.title}>Pendientes de revisión</h1>
              <p className={styles.subtitle}>Revisa todos los comprobantes pendientes sin entrar viaje por viaje.</p>
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
              {items.map((item) => (
                <article key={item.receiptId} className={styles.card}>
                  <div className={styles.cardHeader}>
                    <div>
                      <h2 className={styles.cardTitle}>{item.userLastname}, {item.userName}</h2>
                      <p className={styles.cardSubtitle}>{item.userEmail} · {item.studentName || "Sin alumno informado"}</p>
                    </div>
                    <span className={styles.pendingBadge}>Pendiente</span>
                  </div>

                  <div className={styles.grid}>
                    <div>
                      <span className={styles.label}>Viaje</span>
                      <p className={styles.value}>{item.tripName}</p>
                    </div>
                    <div>
                      <span className={styles.label}>Cuota</span>
                      <p className={styles.value}>#{item.installmentNumber} · vence {formatDate(item.installmentDueDate)}</p>
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
                      className={styles.primaryButton}
                      disabled={reviewPayment.isPending}
                      onClick={() => handleApprove(item)}
                    >
                      Aprobar
                    </button>
                    <button
                      type="button"
                      className={styles.secondaryButton}
                      disabled={reviewPayment.isPending}
                      onClick={() => {
                        setRejectingReceiptId(item.receiptId);
                        setRejectObservation("");
                      }}
                    >
                      Rechazar
                    </button>
                  </div>

                  {rejectingReceiptId === item.receiptId ? (
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
                          onClick={() => handleReject(item)}
                        >
                          Confirmar rechazo
                        </button>
                      </div>
                    </div>
                  ) : null}
                </article>
              ))}
            </div>
            {actionError ? <p className={styles.errorText}>{actionError}</p> : null}
          </RequestState>
        </div>
      </section>
    </CommonLayout>
  );
}
