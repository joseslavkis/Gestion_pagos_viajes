import { useEffect, useMemo, useRef, useState } from "react";

import { AttachmentList } from "@/features/payments/components/AttachmentList";
import { ManualImputationSection } from "@/features/payments/components/ManualImputationSection";
import {
  useInstallmentReceipts,
  useVoidPayment,
} from "@/features/payments/services/payments-service";
import { useQueryClient } from "@tanstack/react-query";
import type { Currency, PaymentHistoryStatus, PaymentInstallmentHistoryDTO } from "@/features/payments/types/payments-dtos";
import {
  getSpreadsheetParticipantParentLabel,
  getSpreadsheetParticipantPrimaryLabel,
  getSpreadsheetStatusVariant,
} from "@/features/trips/lib/spreadsheet-ui";
import styles from "@/features/trips/pages/SpreadsheetPage.module.css";
import type { SpreadsheetRowDTO, SpreadsheetRowInstallmentDTO } from "@/features/trips/types/trips-dtos";
import { createGsapMatchMedia, getMotionProfile, gsap, useGSAP } from "@/lib/gsap";

/** Trip-denominated amounts must be formatted with the trip currency, never
 *  inferred from the payment currency or from the presence of a rate. */
function formatTripMoney(amount: number, tripCurrency: Currency): string {
  return new Intl.NumberFormat("es-AR", { style: "currency", currency: tripCurrency }).format(amount);
}

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

function formatMoneyByCurrency(amount: number | string, currency: Currency): string {
  return new Intl.NumberFormat("es-AR", {
    style: "currency",
    currency,
  }).format(typeof amount === "string" ? Number.parseFloat(amount) : amount);
}

function formatDate(isoDate: string): string {
  const d = new Date(`${isoDate}T00:00:00`);
  return Number.isNaN(d.getTime()) ? isoDate : dateFormatter.format(d);
}

type PaymentDrawerProps = {
  installment: SpreadsheetRowInstallmentDTO;
  row: SpreadsheetRowDTO;
  /**
   * Currency of the trip the installment belongs to. It is a property of the
   * trip, so it comes from the trip context and never from the installment row
   * nor from payment data (the payment currency can differ from it).
   */
  tripCurrency: Currency;
  onClose: () => void;
};

export function PaymentDrawer({ installment, row, tripCurrency, onClose }: PaymentDrawerProps) {
  const { data: history, isLoading: isHistoryLoading, error: historyError } = useInstallmentReceipts(installment.id);
  const voidPayment = useVoidPayment();
  const queryClient = useQueryClient();

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
      <aside ref={drawerRef} className={styles.drawer} role="dialog" aria-modal="true" aria-labelledby={titleId}>
        <header className={styles.drawerHeader}>
          <h2 id={titleId} className={styles.drawerTitle}>
            Cuota {installment.installmentNumber} ·{" "}
            {formatTripMoney(installment.totalDue, tripCurrency)}
          </h2>
          <button type="button" className={styles.drawerCloseButton} onClick={onClose}>
            Cerrar
          </button>
        </header>

        <div className={styles.drawerBody}>
          <section className={styles.drawerSection}>
            <div className={styles.drawerLabel}>Info de la cuota</div>
            <div>
              <div className={styles.strong}>{getSpreadsheetParticipantPrimaryLabel(row)}</div>
              {getSpreadsheetParticipantParentLabel(row) ? (
                <div>{getSpreadsheetParticipantParentLabel(row)}</div>
              ) : null}
              <div>{row.email}</div>
              {row.phone ? <div>Tel: {row.phone}</div> : null}
              {row.studentDni ? <div>DNI alumno: {row.studentDni}</div> : null}
              <div>
                Nro cuota: <span className={styles.strong}>{installment.installmentNumber}</span>
              </div>
              <div>
                Total:{" "}
                <span className={styles.strong}>
                  {formatTripMoney(installment.totalDue, tripCurrency)}
                </span>
              </div>
              <div>
                Vencimiento: <span className={styles.strong}>{formatDate(installment.dueDate)}</span>
              </div>
              <div>
                Estado: <StatusBadge installment={installment} label={installment.uiStatusLabel} />
              </div>
            </div>
          </section>

          <ManualImputationSection
            installmentId={installment.id}
            onImputed={() => {
              queryClient.invalidateQueries({ queryKey: ["payments", "installment", installment.id] });
              queryClient.invalidateQueries({ queryKey: ["spreadsheet"] });
            }}
          />

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
                  <span className={styles.strong}>Monto reportado:</span>{" "}
                  {formatMoneyByCurrency(entry.reportedAmount, entry.paymentCurrency)}
                </div>
                <div>
                  <span className={styles.strong}>Equivalente imputado al viaje:</span>{" "}
                  {formatTripMoney(Number.parseFloat(entry.amountInTripCurrency), tripCurrency)}
                </div>
                <div>
                  <span className={styles.strong}>Fecha:</span> {formatDate(entry.reportedPaymentDate)}
                </div>
                <div>
                  <span className={styles.strong}>Método:</span>{" "}
                  {entry.source === "ADMIN_MANUAL"
                    ? "Imputación manual"
                    : (entry.paymentMethod
                        ? (paymentMethodLabels[entry.paymentMethod] ?? entry.paymentMethod)
                        : "No informado")}
                </div>
                {entry.source === "ADMIN_MANUAL" && entry.manualReason ? (
                  <div>
                    <span className={styles.strong}>Motivo:</span> {entry.manualReason}
                  </div>
                ) : null}
                {entry.source === "ADMIN_MANUAL" ? null : (
                  <div>
                    <span className={styles.strong}>Cuenta acreditada:</span>{" "}
                    {entry.bankAccountDisplayName ?? "Cuenta no informada"}
                    {entry.bankAccountAlias ? ` · ${entry.bankAccountAlias}` : ""}
                  </div>
                )}
                {entry.adminObservation && entry.source !== "ADMIN_MANUAL" ? (
                  <div>
                    <span className={styles.strong}>Observación:</span> {entry.adminObservation}
                  </div>
                ) : null}

                <AttachmentList receipt={entry} />

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
  installment: Pick<SpreadsheetRowInstallmentDTO, "uiStatusCode">;
  label: SpreadsheetRowInstallmentDTO["uiStatusLabel"];
};

function StatusBadge({ installment, label }: StatusBadgeProps) {
  const classes = getStatusClass(getSpreadsheetStatusVariant(installment));

  return (
    <span className={`${styles.statusPill} ${classes.pill}`}>
      <span className={`${styles.statusDot} ${classes.dot}`} />
      <span>{label}</span>
    </span>
  );
}

function getStatusClass(tone: ReturnType<typeof getSpreadsheetStatusVariant>): { pill: string; dot: string } {
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
