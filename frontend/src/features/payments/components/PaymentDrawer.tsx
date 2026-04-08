import { useEffect, useMemo, useRef, useState } from "react";

import {
  useInstallmentReceipts,
  useVoidPayment,
} from "@/features/payments/services/payments-service";
import type {
  PaymentHistoryStatus,
  PaymentInstallmentHistoryDTO,
} from "@/features/payments/types/payments-dtos";
import type {
  SpreadsheetRowDTO,
  SpreadsheetRowInstallmentDTO,
} from "@/features/trips/types/trips-dtos";
import { createGsapMatchMedia, getMotionProfile, gsap, useGSAP } from "@/lib/gsap";

import styles from "@/features/trips/pages/SpreadsheetPage.module.css";

const currencyFormatter = new Intl.NumberFormat("es-AR", {
  style: "currency",
  currency: "ARS",
});

const historyStatusLabels: Record<PaymentHistoryStatus, string> = {
  PENDING: "Pendiente de revisión",
  APPROVED: "Aprobado",
  REJECTED: "Rechazado",
  PARTIALLY_APPROVED: "Aprobado parcial",
  VOIDED: "Anulado",
};

const paymentMethodLabels: Record<string, string> = {
  BANK_TRANSFER: "Transferencia bancaria",
  CASH: "Efectivo",
  DEPOSIT: "Depósito",
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
    data: history,
    isLoading: isHistoryLoading,
    error: historyError,
  } = useInstallmentReceipts(installment.id);
  const voidPayment = useVoidPayment();

  const [voidError, setVoidError] = useState<string | null>(null);
  const titleId = `payment-drawer-title-${installment.id}`;
  const overlayRef = useRef<HTMLDivElement | null>(null);
  const drawerRef = useRef<HTMLElement | null>(null);

  useGSAP(
    () => {
      if (!overlayRef.current || !drawerRef.current) {
        return;
      }

      const motion = getMotionProfile();
      const mm = createGsapMatchMedia();

      if (!mm) {
        gsap.set([overlayRef.current, drawerRef.current], {
          clearProps: "opacity,visibility,transform",
        });
        return;
      }

      mm.add("(prefers-reduced-motion: reduce)", () => {
        gsap.set([overlayRef.current, drawerRef.current], {
          clearProps: "opacity,visibility,transform",
        });
      });

      mm.add("(prefers-reduced-motion: no-preference)", () => {
        gsap.fromTo(
          overlayRef.current,
          { autoAlpha: 0 },
          { autoAlpha: 1, duration: motion.durationFast, ease: "power1.out" },
        );
        gsap.fromTo(
          drawerRef.current,
          { x: motion.distanceMd * (motion.isCompact ? 1.6 : 2.1), autoAlpha: 0 },
          {
            x: 0,
            autoAlpha: 1,
            duration: motion.durationBase,
            ease: "power2.out",
            clearProps: "opacity,visibility,transform",
          },
        );
      });

      return () => mm.revert();
    },
    { scope: overlayRef },
  );

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

  useEffect(() => {
    const previousOverflow = document.body.style.overflow;
    document.body.style.overflow = "hidden";
    return () => {
      document.body.style.overflow = previousOverflow;
    };
  }, []);

  const historyItems = useMemo(() => history ?? [], [history]);

  const handleVoid = async (entry: PaymentInstallmentHistoryDTO) => {
    if (entry.submissionId == null) {
      return;
    }

    setVoidError(null);
    try {
      await voidPayment.mutateAsync({ id: entry.submissionId });
    } catch (error) {
      setVoidError(error instanceof Error ? error.message : "No se pudo anular el pago");
    }
  };

  return (
    <div
      ref={overlayRef}
      className={styles.drawerOverlay}
      role="presentation"
      onClick={(event) => {
        if (event.target === event.currentTarget) {
          onClose();
        }
      }}
    >
      <aside
        ref={drawerRef}
        className={styles.drawer}
        role="dialog"
        aria-modal="true"
        aria-labelledby={titleId}
      >
        <header className={styles.drawerHeader}>
          <h2 id={titleId} className={styles.drawerTitle}>
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
                Estado: <StatusBadge tone={installment.uiStatusTone} label={installment.uiStatusLabel} />
              </div>
            </div>
          </section>

          <section className={styles.drawerSection}>
            <div className={styles.drawerLabel}>Historial del pago imputado</div>
            {isHistoryLoading ? <div>Cargando movimientos...</div> : null}
            {historyError ? <div className={styles.highlightDanger}>{historyError.message}</div> : null}
            {!isHistoryLoading && historyItems.length === 0 ? <div>No hay movimientos registrados.</div> : null}
            {historyItems.map((entry) => (
              <div key={entry.id} style={{ border: "1px solid #e2e8f0", borderRadius: 8, padding: 10, marginTop: 8 }}>
                <div>
                  <span className={styles.strong}>Estado:</span> {historyStatusLabels[entry.status] ?? entry.status}
                </div>
                <div>
                  <span className={styles.strong}>Monto reportado:</span> {formatMoneyByCurrency(entry.reportedAmount, entry.paymentCurrency)}
                </div>
                <div>
                  <span className={styles.strong}>Equivalente viaje:</span> {entry.amountInTripCurrency.toFixed(2)}
                </div>
                <div>
                  <span className={styles.strong}>Fecha:</span> {formatDate(entry.reportedPaymentDate)}
                </div>
                <div>
                  <span className={styles.strong}>Método:</span> {paymentMethodLabels[entry.paymentMethod] ?? entry.paymentMethod}
                </div>
                <div>
                  <span className={styles.strong}>Cuenta acreditada:</span>{" "}
                  {entry.bankAccountDisplayName ?? "Cuenta no informada"}
                  {entry.bankAccountAlias ? ` · ${entry.bankAccountAlias}` : ""}
                </div>
                {entry.adminObservation ? (
                  <div>
                    <span className={styles.strong}>Observación:</span> {entry.adminObservation}
                  </div>
                ) : null}

                {entry.fileKey ? (
                  <div style={{ marginTop: 8 }}>
                    {entry.fileKey.startsWith("data:image") ? (
                      <img
                        src={entry.fileKey}
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
                      <a href={entry.fileKey} target="_blank" rel="noreferrer">
                        Ver comprobante adjunto
                      </a>
                    )}
                  </div>
                ) : null}

                {entry.status === "APPROVED" && entry.submissionId != null ? (
                  <div style={{ marginTop: 8 }}>
                    <button
                      type="button"
                      className={styles.pageButton}
                      disabled={voidPayment.isPending}
                      onClick={() => handleVoid(entry)}
                    >
                      Anular
                    </button>
                  </div>
                ) : null}
              </div>
            ))}
            {voidError ? <div className={styles.highlightDanger}>{voidError}</div> : null}
          </section>
        </div>
      </aside>
    </div>
  );
}

type StatusBadgeProps = {
  tone: SpreadsheetRowInstallmentDTO["uiStatusTone"];
  label: SpreadsheetRowInstallmentDTO["uiStatusLabel"];
};

function StatusBadge({ tone, label }: StatusBadgeProps) {
  const classes = getStatusClass(tone);

  return (
    <span className={`${styles.statusPill} ${classes.pill}`}>
      <span className={`${styles.statusDot} ${classes.dot}`} />
      <span>{label}</span>
    </span>
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
