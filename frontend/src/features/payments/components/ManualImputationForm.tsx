import { useEffect, useMemo, useState } from "react";

import { usePaymentCalculation } from "@/features/payments/services/payments-service";
import {
  useManualImputation,
  useManualImputationContext,
} from "@/features/payments/services/manual-imputation-service";
import type { Currency } from "@/features/payments/types/payments-dtos";
import type { PaymentCalculationResponseDTO } from "@/features/payments/types/payments-dtos";
import {
  compareNonNegativeDecimalStrings,
  normalizePaymentMoneyInput,
} from "@/features/payments/types/decimal-strings";
import {
  formatDecimalMoney,
  shouldKeepCompleteAnchorChecked,
  todayIsoDate,
} from "@/features/payments/types/manual-imputation-helpers";
import styles from "./ManualImputation.module.css";

type Props = {
  installmentId: number;
  onSuccess: () => void;
};

function formatDateEs(iso: string): string {
  const d = new Date(`${iso}T00:00:00`);
  if (Number.isNaN(d.getTime())) return iso;
  return new Intl.DateTimeFormat("es-AR", {
    day: "2-digit",
    month: "2-digit",
    year: "numeric",
    timeZone: "America/Argentina/Buenos_Aires",
  }).format(d);
}

export function ManualImputationForm({ installmentId, onSuccess }: Props) {
  const contextQuery = useManualImputationContext(installmentId);
  const manualMutation = useManualImputation();

  const [amount, setAmount] = useState("");
  const [currency, setCurrency] = useState<Currency>("ARS");
  const [paymentDate, setPaymentDate] = useState(() => todayIsoDate());
  const [completeChecked, setCompleteChecked] = useState(false);
  const [completedAmount, setCompletedAmount] = useState<string | null>(null);
  const [reason, setReason] = useState("");
  const [file, setFile] = useState<File | null>(null);
  const [formError, setFormError] = useState<string | null>(null);
  const [wantsRemaining, setWantsRemaining] = useState(false);
  const [manualPayload, setManualPayload] = useState<string | null>(null);
  const [confirmedPreview, setConfirmedPreview] = useState<PaymentCalculationResponseDTO | null>(null);
  const [successMessage, setSuccessMessage] = useState<string | null>(null);

  const remainingRequest = useMemo(
    () =>
      wantsRemaining
        ? {
            anchorInstallmentId: installmentId,
            paymentCurrency: currency,
            reportedPaymentDate: paymentDate,
            intent: "REMAINING" as const,
          }
        : null,
    [wantsRemaining, installmentId, currency, paymentDate],
  );
  const remainingQuery = usePaymentCalculation(remainingRequest);

  const manualRequest = useMemo(
    () =>
      manualPayload != null
        ? {
            anchorInstallmentId: installmentId,
            paymentCurrency: currency,
            reportedPaymentDate: paymentDate,
            intent: "MANUAL" as const,
            reportedAmount: manualPayload as `${string}` & {},
          }
        : null,
    [manualPayload, installmentId, currency, paymentDate],
  );
  const manualQuery = usePaymentCalculation(manualRequest);

  // Quick action: cuando el backend devuelve el REMAINING, volcarlo al input.
  // Es server state → local state, por eso el efecto es legítimo.
  useEffect(() => {
    if (!wantsRemaining) return;
    const data = remainingQuery.data;
    if (data?.status === "READY" && data.reportedAmount != null) {
      setAmount(data.reportedAmount);
      setCompletedAmount(data.reportedAmount);
      setCompleteChecked(true);
      setWantsRemaining(false);
      setFormError(null);
    }
    if (data && data.status !== "READY") {
      setWantsRemaining(false);
      setFormError(data.message ?? "No se pudo calcular el saldo de la cuota.");
    }
  }, [wantsRemaining, remainingQuery.data]);

  useEffect(() => {
    if (remainingQuery.error) {
      setWantsRemaining(false);
      setFormError(remainingQuery.error.message);
    }
  }, [remainingQuery.error]);

  // Fijar la confirmación cuando el MANUAL está READY.
  useEffect(() => {
    const data = manualQuery.data;
    if (manualPayload != null && data?.status === "READY" && data.previewToken) {
      setConfirmedPreview(data);
    }
  }, [manualPayload, manualQuery.data]);

  const context = contextQuery.data;

  const handleAmountChange = (value: string) => {
    setAmount(value);
    setConfirmedPreview(null);
    setManualPayload(null);
    setFormError(null);
    if (completeChecked && completedAmount != null) {
      const keep = shouldKeepCompleteAnchorChecked(
        true,
        value,
        completedAmount as `${string}` & {},
      );
      if (!keep) {
        setCompleteChecked(false);
        setCompletedAmount(null);
      }
    }
  };

  const handleCurrencyChange = (value: Currency) => {
    setCurrency(value);
    setConfirmedPreview(null);
    setManualPayload(null);
    setCompleteChecked(false);
    setCompletedAmount(null);
    setWantsRemaining(false);
    setFormError(null);
  };

  const handleDateChange = (value: string) => {
    setPaymentDate(value);
    setConfirmedPreview(null);
    setManualPayload(null);
    setCompleteChecked(false);
    setCompletedAmount(null);
    setWantsRemaining(false);
    setFormError(null);
  };

  const handleCompleteToggle = () => {
    if (completeChecked) {
      setCompleteChecked(false);
      setCompletedAmount(null);
      return;
    }
    setFormError(null);
    setConfirmedPreview(null);
    setManualPayload(null);
    setWantsRemaining(true);
  };

  const handleContinue = () => {
    setFormError(null);
    setConfirmedPreview(null);
    const normalized = normalizePaymentMoneyInput(amount);
    if (normalized == null) {
      setFormError("Ingresá un monto válido mayor a cero.");
      return;
    }
    setManualPayload(normalized);
  };

  const handleConfirm = async (preview: PaymentCalculationResponseDTO) => {
    if (manualMutation.isPending) return;
    setFormError(null);
    if (preview.previewToken == null) {
      setFormError("La previsualización venció. Volvé a calcular la imputación.");
      return;
    }
    if (preview.reportedAmount == null) {
      setFormError("La previsualización venció. Volvé a calcular la imputación.");
      return;
    }
    try {
      await manualMutation.mutateAsync({
        anchorInstallmentId: installmentId,
        reportedAmount: preview.reportedAmount,
        paymentCurrency: preview.paymentCurrency,
        reportedPaymentDate: preview.reportedPaymentDate,
        previewToken: preview.previewToken,
        reason: reason.trim() ? reason.trim() : undefined,
        file,
      });
      setSuccessMessage("Imputación registrada correctamente.");
      setConfirmedPreview(null);
      setManualPayload(null);
      setAmount("");
      setCompleteChecked(false);
      setCompletedAmount(null);
      setReason("");
      setFile(null);
      onSuccess();
    } catch (error) {
      setFormError(error instanceof Error ? error.message : "No se pudo registrar la imputación.");
    }
  };

  if (contextQuery.isLoading) {
    return <p className={styles.calculating}>Verificando elegibilidad…</p>;
  }
  if (contextQuery.error) {
    return <p className={styles.error} role="alert">{contextQuery.error.message}</p>;
  }
  if (!context) return null;

  if (!context.eligible) {
    return <p className={styles.blockedMessage} role="alert">{context.message}</p>;
  }

  if (successMessage) {
    return (
      <div>
        <p className={styles.success} role="status">{successMessage}</p>
        <div className={styles.actions}>
          <button
            type="button"
            className={styles.secondaryButton}
            onClick={() => {
              setSuccessMessage(null);
              contextQuery.refetch();
            }}
          >
            Nueva imputación
          </button>
        </div>
      </div>
    );
  }

  if (confirmedPreview) {
    const preview = confirmedPreview;
    return (
      <div className={styles.confirmation} aria-live="polite">
        <h4 className={styles.confirmationTitle}>Confirmar imputación</h4>
        <div className={styles.confirmationRow}>
          <span>Monto recibido:</span>
          <span className={styles.confirmationStrong}>
            {preview.reportedAmount
              ? formatDecimalMoney(preview.reportedAmount, preview.paymentCurrency)
              : "—"}
          </span>
        </div>
        <div className={styles.confirmationRow}>
          <span>Fecha de pago:</span>
          <span className={styles.confirmationStrong}>{formatDateEs(preview.reportedPaymentDate)}</span>
        </div>
        {preview.exchangeRate ? (
          <div className={styles.confirmationRow}>
            <span>Cotización utilizada:</span>
            <span className={styles.confirmationStrong}>${preview.exchangeRate} / USD</span>
          </div>
        ) : null}
        {preview.amountInTripCurrency ? (
          <div className={styles.confirmationRow}>
            <span>Equivalente en el viaje:</span>
            <span className={styles.confirmationStrong}>
              {formatDecimalMoney(preview.amountInTripCurrency, preview.tripCurrency)}
            </span>
          </div>
        ) : null}
        <div>
          <span className={styles.label}>Se imputará:</span>
          <ul className={styles.allocationList}>
            {preview.installments.map((allocation) => {
              const isComplete =
                compareNonNegativeDecimalStrings(
                  allocation.amountInTripCurrency,
                  allocation.remainingAmount,
                ) === 0;
              return (
                <li key={allocation.installmentId} className={styles.allocationItem}>
                  <span>
                    Cuota #{allocation.installmentNumber} ·{" "}
                    {formatDecimalMoney(allocation.amountInTripCurrency, preview.tripCurrency)}
                  </span>
                  <span>{isComplete ? "→ completa" : "→ parcial"}</span>
                </li>
              );
            })}
          </ul>
        </div>
        {formError ? <p className={styles.error} role="alert">{formError}</p> : null}
        {manualMutation.error ? (
          <p className={styles.error} role="alert">{manualMutation.error.message}</p>
        ) : null}
        <div className={styles.actions}>
          <button
            type="button"
            className={styles.secondaryButton}
            disabled={manualMutation.isPending}
            onClick={() => {
              setConfirmedPreview(null);
              setManualPayload(null);
            }}
          >
            Volver
          </button>
          <button
            type="button"
            className={styles.primaryButton}
            disabled={manualMutation.isPending}
            onClick={() => handleConfirm(preview)}
          >
            {manualMutation.isPending ? "Confirmando…" : "Confirmar imputación"}
          </button>
        </div>
      </div>
    );
  }

  const isCalculating = remainingQuery.isFetching || manualQuery.isFetching;
  const manualError =
    manualPayload != null && manualQuery.data && manualQuery.data.status !== "READY"
      ? (manualQuery.data.message ?? "No se pudo calcular la imputación.")
      : null;

  return (
    <form
      className={styles.form}
      onSubmit={(event) => {
        event.preventDefault();
        handleContinue();
      }}
    >
      <div className={styles.field}>
        <label className={styles.label} htmlFor={`manual-amount-${installmentId}`}>
          Monto a imputar
        </label>
        <input
          id={`manual-amount-${installmentId}`}
          className={styles.input}
          inputMode="decimal"
          placeholder="300.000,00"
          value={amount}
          onChange={(event) => handleAmountChange(event.target.value)}
          disabled={isCalculating}
        />
      </div>

      <div className={styles.field}>
        <label className={styles.label} htmlFor={`manual-currency-${installmentId}`}>
          Moneda del pago
        </label>
        <select
          id={`manual-currency-${installmentId}`}
          className={styles.select}
          value={currency}
          onChange={(event) => handleCurrencyChange(event.target.value as Currency)}
        >
          <option value="ARS">ARS</option>
          <option value="USD">USD</option>
        </select>
      </div>

      <div className={styles.field}>
        <label className={styles.label} htmlFor={`manual-date-${installmentId}`}>
          Fecha de pago
        </label>
        <input
          id={`manual-date-${installmentId}`}
          type="date"
          className={styles.input}
          value={paymentDate}
          max={todayIsoDate()}
          onChange={(event) => handleDateChange(event.target.value)}
        />
      </div>

      <label className={styles.checkboxRow}>
        <input
          type="checkbox"
          checked={completeChecked}
          onChange={handleCompleteToggle}
          disabled={isCalculating}
        />
        Completar cuota actual
      </label>
      {isCalculating ? <p className={styles.calculating}>Calculando…</p> : null}
      {remainingQuery.error ? (
        <p className={styles.error} role="alert">{remainingQuery.error.message}</p>
      ) : null}
      {manualQuery.error ? (
        <p className={styles.error} role="alert">{manualQuery.error.message}</p>
      ) : null}
      {manualError ? <p className={styles.error} role="alert">{manualError}</p> : null}

      <div className={styles.field}>
        <label className={styles.label} htmlFor={`manual-reason-${installmentId}`}>
          Motivo (opcional)
        </label>
        <input
          id={`manual-reason-${installmentId}`}
          className={styles.input}
          value={reason}
          maxLength={500}
          placeholder="Pago en efectivo, transferencia verificada, ajuste…"
          onChange={(event) => {
            setReason(event.target.value);
            setConfirmedPreview(null);
          }}
        />
      </div>

      <div className={styles.field}>
        <label className={styles.label} htmlFor={`manual-file-${installmentId}`}>
          Comprobante (opcional)
        </label>
        <input
          id={`manual-file-${installmentId}`}
          type="file"
          className={styles.input}
          accept="image/jpeg,image/png,image/webp,application/pdf"
          onChange={(event) => setFile(event.target.files?.[0] ?? null)}
        />
      </div>

      {formError ? <p className={styles.error} role="alert">{formError}</p> : null}

      <div className={styles.actions}>
        <button
          type="submit"
          className={styles.primaryButton}
          disabled={isCalculating || manualMutation.isPending}
        >
          {isCalculating ? "Calculando…" : "Continuar"}
        </button>
      </div>
    </form>
  );
}
