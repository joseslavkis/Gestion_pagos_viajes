import { useEffect, useMemo, useRef } from "react";

import {
  SLIDER_CENTER_POSITION,
  SLIDER_MAX_POSITION,
  SLIDER_MIN_POSITION,
  type AmountCorrection,
  decimalStringToCents,
  extendSliderUpper,
  reportedSliderRange,
  sliderCentsToPosition,
  sliderPositionToCents,
} from "@/features/payments/types/amount-slider";
import { centsToMoney, exceedsMaxMoney } from "@/features/payments/types/receipt-amounts";
import type { Currency, DecimalString } from "@/features/payments/types/payments-dtos";

import styles from "./ApprovedAmountControl.module.css";

export type ApprovedAmountControlProps = {
  inputId: string;
  reportedAmount: DecimalString;
  paymentCurrency: Currency;
  value: string;
  validationError: string | null;
  validAmount: DecimalString | null;
  correction: AmountCorrection;
  correctionLabel: string;
  upperOverrideCents: bigint | null;
  onValueChange: (value: string) => void;
  onUpperOverrideChange: (cents: bigint | null) => void;
  onReset: () => void;
  formatMoney: (amount: DecimalString, currency: Currency) => string;
};

/**
 * Synchronized manual input + native range slider for the amount to credit.
 * The slider works on abstract positions (center = reported amount) and only
 * converts to bigint cents at the edges — financial logic stays exact.
 */
export function ApprovedAmountControl({
  inputId,
  reportedAmount,
  paymentCurrency,
  value,
  validationError,
  validAmount,
  correction,
  correctionLabel,
  upperOverrideCents,
  onValueChange,
  onUpperOverrideChange,
  onReset,
  formatMoney,
}: ApprovedAmountControlProps) {
  const baseRange = useMemo(() => reportedSliderRange(reportedAmount), [reportedAmount]);
  const range = useMemo(
    () => (upperOverrideCents == null ? baseRange : extendSliderUpper(baseRange, upperOverrideCents)),
    [baseRange, upperOverrideCents],
  );

  const validCents = validAmount == null ? null : decimalStringToCents(validAmount);

  // When the manual input is invalid the thumb freezes at the last valid
  // position instead of jumping back to the center.
  const lastPositionRef = useRef(SLIDER_CENTER_POSITION);
  const position =
    validCents == null ? lastPositionRef.current : sliderCentsToPosition(validCents, range);
  useEffect(() => {
    if (validCents != null) {
      lastPositionRef.current = position;
    }
  }, [validCents, position]);

  const handleManualChange = (next: string) => {
    onValueChange(next);
    const cents = decimalStringToCents(next);
    if (cents != null && cents > range.upperCents && !exceedsMaxMoney(next)) {
      onUpperOverrideChange(cents);
    }
  };

  const handleSliderChange = (nextPosition: number) => {
    const money = centsToMoney(sliderPositionToCents(nextPosition, range));
    if (money != null) {
      onValueChange(money);
    }
  };

  const errorId = `${inputId}-error`;
  const differenceCents =
    validCents == null || correction === "none" ? null : validCents - range.centerCents;
  const differenceLabel =
    differenceCents == null || differenceCents === 0n
      ? null
      : `${differenceCents > 0n ? "+" : "-"}${formatMoney(centsToMoney(differenceCents < 0n ? -differenceCents : differenceCents) ?? "0.00", paymentCurrency)} respecto de lo informado`;
  const sliderValueText =
    validAmount == null
      ? "Monto a imputar: valor inválido. Escribí un importe válido o usá la barra."
      : `Monto a imputar: ${formatMoney(validAmount, paymentCurrency)}. ${correctionLabel}.`;

  return (
    <div className={styles.block}>
      <p className={styles.reportedLine}>
        Monto informado por el cliente · {formatMoney(reportedAmount, paymentCurrency)}
      </p>

      <p className={styles.amountHeading}>Monto a imputar</p>

      <div className={styles.inputRow}>
        <span className={styles.currencyChip} aria-hidden="true">
          {paymentCurrency}
        </span>
        <label className={styles.amountLabel}>
          <span className={styles.visuallyHidden}>Monto a imputar</span>
          <input
            id={inputId}
            className={styles.amountInput}
            value={value}
            aria-invalid={validationError != null}
            aria-describedby={validationError ? errorId : undefined}
            onChange={(event) => handleManualChange(event.target.value)}
            placeholder="0.00"
            inputMode="decimal"
          />
        </label>
      </div>
      {validationError ? (
        <p id={errorId} className={styles.errorText} role="alert">
          {validationError}
        </p>
      ) : null}

      <div className={styles.sliderGroup}>
        <input
          type="range"
          className={styles.slider}
          min={SLIDER_MIN_POSITION}
          max={SLIDER_MAX_POSITION}
          step={1}
          value={position}
          onChange={(event) => handleSliderChange(Number(event.target.value))}
          aria-label="Corregir monto con barra deslizante"
          aria-valuetext={sliderValueText}
        />
        <span className={styles.centerTick} aria-hidden="true" />
      </div>
      <div className={styles.scaleLabels} aria-hidden="true">
        <span>Menos</span>
        <span className={styles.scaleCenter}>Informado</span>
        <span>Más</span>
      </div>

      <div className={styles.badgeRow} aria-live="polite">
        {correction === "none" ? (
          <span className={styles.neutralNote}>{correctionLabel}</span>
        ) : (
          <span className={styles.correctionBadge}>{correctionLabel}</span>
        )}
      </div>
      {differenceLabel ? (
        <p className={styles.diffLine} aria-live="polite">
          {differenceLabel}
        </p>
      ) : null}
      {correction !== "none" && validAmount != null ? (
        <button type="button" className={styles.resetButton} onClick={onReset}>
          Restablecer al monto informado
        </button>
      ) : null}
    </div>
  );
}
