import { useEffect, useMemo, useState } from "react";

import {
  useInstallmentReceipts,
  useReviewPayment,
  useVoidPayment,
} from "@/features/payments/services/payments-service";
import type {
  PaymentReceiptDTO,
  ReceiptStatus,
} from "@/features/payments/types/payments-dtos";
import type {
  SpreadsheetRowDTO,
  SpreadsheetRowInstallmentDTO,
} from "@/features/trips/types/trips-dtos";

import styles from "@/features/trips/pages/SpreadsheetPage.module.css";

const currencyFormatter = new Intl.NumberFormat("es-AR", {
  style: "currency",
  currency: "ARS",
});

const receiptStatusLabels: Record<string, string> = {
  PENDING: "Pendiente de revision",
  APPROVED: "Aprobado",
  REJECTED: "Rechazado",
};

const paymentMethodLabels: Record<string, string> = {
  BANK_TRANSFER: "Transferencia bancaria",
  CASH: "Efectivo",
  CARD: "Tarjeta",
  OTHER: "Otro",
};

const dateFormatter = new Intl.DateTimeFormat("es-AR", {
  day: "2-digit",
  month: "2-digit",
  year: "numeric",
  timeZone: "America/Argentina/Buenos_Aires",
});

function formatMoneyByCurrency(amount: number, currency: "ARS" | "USD"): string {
  return new Intl.NumberFormat("es-AR", {
    style: "currency",
    currency,
  }).format(amount);
}

function formatDate(isoDate: string): string {
  const d = new Date(`${isoDate}T00:00:00`);
  return Number.isNaN(d.getTime()) ? isoDate : dateFormatter.format(d);
}

type PaymentDrawerProps = {
  installment: SpreadsheetRowInstallmentDTO;
  row: SpreadsheetRowDTO;
  onClose: () => void;
};

export function PaymentDrawer({ installment, row, onClose }: PaymentDrawerProps) {
  const {
    data: receipts,
    isLoading: isReceiptsLoading,
    error: receiptsError,
  } = useInstallmentReceipts(installment.id);
  const reviewPayment = useReviewPayment();
  const voidPayment = useVoidPayment();

  const [rejectingReceiptId, setRejectingReceiptId] = useState<number | null>(null);
  const [rejectObservation, setRejectObservation] = useState("");
  const [reviewError, setReviewError] = useState<string | null>(null);
  const [voidError, setVoidError] = useState<string | null>(null);

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

  const isBusy = reviewPayment.isPending || voidPayment.isPending;

  const receiptItems = useMemo(() => receipts ?? [], [receipts]);

  const handleApprove = async (receipt: PaymentReceiptDTO) => {
    setReviewError(null);
    try {
      await reviewPayment.mutateAsync({
        id: receipt.id,
        installmentId: installment.id,
        data: { decision: "APPROVED" as ReceiptStatus },
      });
    } catch (error) {
      setReviewError(error instanceof Error ? error.message : "No se pudo aprobar el comprobante");
    }
  };

  const handleRejectConfirm = async (receipt: PaymentReceiptDTO) => {
    setReviewError(null);
    try {
      await reviewPayment.mutateAsync({
        id: receipt.id,
        installmentId: installment.id,
        data: { decision: "REJECTED" as ReceiptStatus, adminObservation: rejectObservation },
      });
      setRejectingReceiptId(null);
      setRejectObservation("");
    } catch (error) {
      setReviewError(error instanceof Error ? error.message : "No se pudo rechazar el comprobante");
    }
  };

  const handleVoid = async (receipt: PaymentReceiptDTO) => {
    setVoidError(null);
    try {
      await voidPayment.mutateAsync({ id: receipt.id, installmentId: installment.id });
    } catch (error) {
      setVoidError(error instanceof Error ? error.message : "No se pudo anular el comprobante");
    }
  };

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
      <aside className={styles.drawer} role="complementary" aria-label="Gestión de comprobantes">
        <header className={styles.drawerHeader}>
          <h2 className={styles.drawerTitle}>
            Cuota {installment.installmentNumber} · {currencyFormatter.format(installment.totalDue)}
          </h2>
          <button type="button" className={styles.drawerCloseButton} onClick={onClose}>
            Cerrar
          </button>
        </header>

        <div className={styles.drawerBody}>
          <section className={styles.drawerSection}>
            <div className={styles.drawerLabel}>Info de la cuota</div>
            <div>
              <div className={styles.strong}>
                {row.lastname}, {row.name}
              </div>
              <div>{row.email}</div>
              <div>
                Nro cuota: <span className={styles.strong}>{installment.installmentNumber}</span>
              </div>
              <div>
                Total: <span className={styles.strong}>{currencyFormatter.format(installment.totalDue)}</span>
              </div>
              <div>
                Vencimiento: <span className={styles.strong}>{formatDate(installment.dueDate)}</span>
              </div>
              <div>
                Estado: <StatusBadge status={installment.status} dueDate={installment.dueDate} />
              </div>
            </div>
          </section>

          <section className={styles.drawerSection}>
            <div className={styles.drawerLabel}>Historial de comprobantes</div>
            {isReceiptsLoading ? <div>Cargando comprobantes...</div> : null}
            {receiptsError ? <div className={styles.highlightDanger}>{receiptsError.message}</div> : null}
            {!isReceiptsLoading && receiptItems.length === 0 ? <div>No hay comprobantes registrados.</div> : null}
            {receiptItems.map((receipt) => (
              <div key={receipt.id} style={{ border: "1px solid #e2e8f0", borderRadius: 8, padding: 10, marginTop: 8 }}>
                <div>
                  <span className={styles.strong}>Estado:</span> {receiptStatusLabels[receipt.status] ?? receipt.status}
                </div>
                <div>
                  <span className={styles.strong}>Monto:</span> {currencyFormatter.format(receipt.reportedAmount)}
                </div>
                {receipt.exchangeRate != null ? (
                  <>
                    <div>
                      <span className={styles.strong}>Pagó:</span> {formatMoneyByCurrency(receipt.reportedAmount, receipt.paymentCurrency)}
                    </div>
                    <div>
                      <span className={styles.strong}>Tipo de cambio BNA:</span> {formatMoneyByCurrency(receipt.exchangeRate, "ARS")}
                    </div>
                    <div>
                      <span className={styles.strong}>Equivalente:</span> {formatMoneyByCurrency(receipt.amountInTripCurrency, receipt.paymentCurrency === "ARS" ? "USD" : "ARS")} {receipt.paymentCurrency === "ARS" ? "USD" : "ARS"}
                    </div>
                  </>
                ) : null}
                <div>
                  <span className={styles.strong}>Fecha:</span> {formatDate(receipt.reportedPaymentDate)}
                </div>
                <div>
                  <span className={styles.strong}>Método:</span> {paymentMethodLabels[receipt.paymentMethod] ?? receipt.paymentMethod}
                </div>
                {receipt.adminObservation ? (
                  <div>
                    <span className={styles.strong}>Observación:</span> {receipt.adminObservation}
                  </div>
                ) : null}

                {receipt.fileKey ? (
                  <div style={{ marginTop: 8 }}>
                    {receipt.fileKey.startsWith("data:image") ? (
                      <img
                        src={receipt.fileKey}
                        alt="Comprobante"
                        style={{
                          maxWidth: "100%",
                          maxHeight: 200,
                          borderRadius: 8,
                          objectFit: "contain",
                          border: "1px solid #e2e8f0",
                        }}
                      />
                    ) : (
                      <a href={receipt.fileKey} target="_blank" rel="noreferrer">
                        Ver comprobante adjunto
                      </a>
                    )}
                  </div>
                ) : null}

                {receipt.status === "PENDING" ? (
                  <div style={{ display: "flex", gap: 8, marginTop: 8, flexWrap: "wrap" }}>
                    <button
                      type="button"
                      className={styles.pageButton}
                      disabled={isBusy}
                      onClick={() => handleApprove(receipt)}
                    >
                      Aprobar
                    </button>
                    <button
                      type="button"
                      className={styles.pageButton}
                      disabled={isBusy}
                      onClick={() => setRejectingReceiptId(receipt.id)}
                    >
                      Rechazar
                    </button>
                    {rejectingReceiptId === receipt.id ? (
                      <div style={{ display: "grid", gap: 6, width: "100%" }}>
                        <input
                          type="text"
                          className={styles.searchInput}
                          placeholder="Observación de rechazo"
                          value={rejectObservation}
                          onChange={(event) => setRejectObservation(event.target.value)}
                        />
                        <button
                          type="button"
                          className={styles.pageButton}
                          disabled={isBusy}
                          onClick={() => handleRejectConfirm(receipt)}
                        >
                          Confirmar rechazo
                        </button>
                      </div>
                    ) : null}
                  </div>
                ) : null}

                {receipt.status === "APPROVED" ? (
                  <div style={{ marginTop: 8 }}>
                    <button
                      type="button"
                      className={styles.pageButton}
                      disabled={isBusy}
                      onClick={() => handleVoid(receipt)}
                    >
                      Anular
                    </button>
                  </div>
                ) : null}
              </div>
            ))}
            {reviewError ? <div className={styles.highlightDanger}>{reviewError}</div> : null}
            {voidError ? <div className={styles.highlightDanger}>{voidError}</div> : null}
          </section>

        </div>
      </aside>
    </div>
  );
}

type StatusBadgeProps = {
  status: SpreadsheetRowInstallmentDTO["status"];
  dueDate: string;
};

function StatusBadge({ status, dueDate }: StatusBadgeProps) {
  const classes = getStatusClass(status, dueDate);
  const label = getStatusIcon(status, dueDate);

  return (
    <span className={`${styles.statusPill} ${classes.pill}`}>
      <span className={`${styles.statusDot} ${classes.dot}`} />
      <span>{label}</span>
    </span>
  );
}

function getStatusClass(status: SpreadsheetRowInstallmentDTO["status"], dueDate: string): { pill: string; dot: string } {
  switch (status) {
    case "GREEN":
      return { pill: styles.statusGreen, dot: styles.statusGreenDot };
    case "YELLOW": {
      const today = new Date();
      today.setHours(0, 0, 0, 0);
      const due = new Date(dueDate);
      due.setHours(0, 0, 0, 0);
      const diffDays = Math.ceil((due.getTime() - today.getTime()) / (1000 * 60 * 60 * 24));
      
      if (diffDays <= 30) {
        return { pill: styles.statusYellow, dot: styles.statusYellowDot };
      }
      return { pill: styles.statusGreen, dot: styles.statusGreenDot };
    }
    case "RED":
      return { pill: styles.statusRed, dot: styles.statusRedDot };
    case "RETROACTIVE":
      return { pill: styles.statusRetro, dot: styles.statusRetroDot };
    default:
      return { pill: "", dot: "" };
  }
}

function getStatusIcon(status: SpreadsheetRowInstallmentDTO["status"], dueDate: string): string {
  switch (status) {
    case "GREEN":
      return "Pagada";
    case "YELLOW": {
      const today = new Date();
      today.setHours(0, 0, 0, 0);
      const due = new Date(dueDate);
      due.setHours(0, 0, 0, 0);
      const diffDays = Math.ceil((due.getTime() - today.getTime()) / (1000 * 60 * 60 * 24));
      
      if (diffDays <= 30) {
        return "Vence pronto";
      }
      return "Al día";
    }
    case "RED":
      return "Vencida";
    case "RETROACTIVE":
      return "Deuda retroactiva";
    default:
      return "Al día";
  }
}

