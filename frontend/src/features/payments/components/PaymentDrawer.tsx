import { useEffect, useMemo, useRef, useState } from "react";

import { Folder } from "@/features/payments/components/Folder";
import {
  useInstallmentReceipts,
  useRegisterPayment,
  useReviewPayment,
  useVoidPayment,
} from "@/features/payments/services/payments-service";
import type {
  PaymentMethod,
  PaymentReceiptDTO,
  ReceiptStatus,
  RegisterPaymentDTO,
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

type PaymentDrawerProps = {
  installment: SpreadsheetRowInstallmentDTO;
  row: SpreadsheetRowDTO;
  onClose: () => void;
};

const paymentMethods: Array<{ value: PaymentMethod; label: string }> = [
  { value: "BANK_TRANSFER", label: "Transferencia bancaria" },
  { value: "CASH", label: "Efectivo" },
  { value: "CARD", label: "Tarjeta" },
  { value: "OTHER", label: "Otro" },
];

export function PaymentDrawer({ installment, row, onClose }: PaymentDrawerProps) {
  const {
    data: receipts,
    isLoading: isReceiptsLoading,
    error: receiptsError,
  } = useInstallmentReceipts(installment.id);
  const registerPayment = useRegisterPayment();
  const reviewPayment = useReviewPayment();
  const voidPayment = useVoidPayment();

  const [amount, setAmount] = useState(String(installment.totalDue));
  const [paymentDate, setPaymentDate] = useState(new Date().toISOString().slice(0, 10));
  const [paymentMethod, setPaymentMethod] = useState<PaymentMethod>("BANK_TRANSFER");
  const [registerError, setRegisterError] = useState<string | null>(null);

  const [rejectingReceiptId, setRejectingReceiptId] = useState<number | null>(null);
  const [rejectObservation, setRejectObservation] = useState("");
  const [reviewError, setReviewError] = useState<string | null>(null);
  const [voidError, setVoidError] = useState<string | null>(null);
  const [receiptPhotoName, setReceiptPhotoName] = useState<string | null>(null);
  const [receiptPhotoPreviewUrl, setReceiptPhotoPreviewUrl] = useState<string | null>(null);
  const fileInputRef = useRef<HTMLInputElement | null>(null);

  useEffect(() => {
    return () => {
      if (receiptPhotoPreviewUrl) {
        URL.revokeObjectURL(receiptPhotoPreviewUrl);
      }
    };
  }, [receiptPhotoPreviewUrl]);

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

  const isBusy = registerPayment.isPending || reviewPayment.isPending || voidPayment.isPending;

  const receiptItems = useMemo(() => receipts ?? [], [receipts]);

  const handleRegisterPayment = async () => {
    setRegisterError(null);
    const parsedAmount = Number(amount);
    if (!Number.isFinite(parsedAmount) || parsedAmount <= 0) {
      setRegisterError("Ingresá un monto válido mayor a 0");
      return;
    }

    const payload: RegisterPaymentDTO = {
      installmentId: installment.id,
      reportedAmount: parsedAmount,
      reportedPaymentDate: paymentDate,
      paymentMethod,
    };

    try {
      await registerPayment.mutateAsync(payload);
      setAmount(String(installment.totalDue));
      setPaymentMethod("BANK_TRANSFER");
      setPaymentDate(new Date().toISOString().slice(0, 10));
    } catch (error) {
      setRegisterError(error instanceof Error ? error.message : "No se pudo registrar el pago");
    }
  };

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

  const handleFolderOpenChange = (isOpen: boolean) => {
    if (isOpen) {
      fileInputRef.current?.click();
    }
  };

  const handleReceiptPhotoChange = (event: React.ChangeEvent<HTMLInputElement>) => {
    const file = event.target.files?.[0];
    if (!file) {
      return;
    }

    if (receiptPhotoPreviewUrl) {
      URL.revokeObjectURL(receiptPhotoPreviewUrl);
    }

    const nextPreview = URL.createObjectURL(file);
    setReceiptPhotoName(file.name);
    setReceiptPhotoPreviewUrl(nextPreview);
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
                Estado: <StatusBadge status={installment.status} />
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
                  <span className={styles.strong}>Estado:</span> {receipt.status}
                </div>
                <div>
                  <span className={styles.strong}>Monto:</span> {currencyFormatter.format(receipt.reportedAmount)}
                </div>
                <div>
                  <span className={styles.strong}>Fecha:</span> {receipt.reportedPaymentDate}
                </div>
                <div>
                  <span className={styles.strong}>Método:</span> {receipt.paymentMethod}
                </div>
                {receipt.adminObservation ? (
                  <div>
                    <span className={styles.strong}>Observación:</span> {receipt.adminObservation}
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

          {installment.status !== "GREEN" ? (
            <section className={styles.drawerSection}>
              <div className={styles.drawerLabel}>Registrar nuevo pago</div>
              <div style={{ display: "grid", gap: 8 }}>
                <input
                  ref={fileInputRef}
                  type="file"
                  accept="image/*"
                  style={{ display: "none" }}
                  onChange={handleReceiptPhotoChange}
                />
                <div style={{ display: "grid", justifyContent: "center", gap: 6, marginBottom: 6 }}>
                  <Folder
                    size={1.35}
                    color="#5227FF"
                    items={
                      receiptPhotoPreviewUrl
                        ? [<img key="receipt-preview" src={receiptPhotoPreviewUrl} alt="Vista previa del comprobante" />]
                        : []
                    }
                    onOpenChange={handleFolderOpenChange}
                  />
                  <div style={{ textAlign: "center", fontSize: 12, color: "#475569" }}>
                    Abrir carpeta para adjuntar foto del comprobante
                  </div>
                  {receiptPhotoName ? (
                    <div style={{ textAlign: "center", fontSize: 12, color: "#0f2f57" }}>
                      Archivo seleccionado: {receiptPhotoName}
                    </div>
                  ) : null}
                </div>
                <input
                  type="number"
                  min="0"
                  step="0.01"
                  className={styles.searchInput}
                  value={amount}
                  onChange={(event) => setAmount(event.target.value)}
                  placeholder="Monto reportado"
                />
                <input
                  type="date"
                  className={styles.searchInput}
                  value={paymentDate}
                  onChange={(event) => setPaymentDate(event.target.value)}
                />
                <select
                  className={styles.select}
                  value={paymentMethod}
                  onChange={(event) => setPaymentMethod(event.target.value as PaymentMethod)}
                >
                  {paymentMethods.map((method) => (
                    <option key={method.value} value={method.value}>
                      {method.label}
                    </option>
                  ))}
                </select>
                <button
                  type="button"
                  className={styles.pageButton}
                  disabled={isBusy}
                  onClick={handleRegisterPayment}
                >
                  Registrar pago
                </button>
                {registerError ? <div className={styles.highlightDanger}>{registerError}</div> : null}
              </div>
            </section>
          ) : null}
        </div>
      </aside>
    </div>
  );
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
