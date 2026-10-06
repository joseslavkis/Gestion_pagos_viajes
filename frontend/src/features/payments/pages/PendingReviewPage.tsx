import { useMemo, useState } from "react";

import { CommonLayout } from "@/components/CommonLayout/CommonLayout";
import { RequestState } from "@/components/ui/RequestState/RequestState";
import { AttachmentList } from "@/features/payments/components/AttachmentList";
import { ApprovedAmountControl } from "@/features/payments/components/ApprovedAmountControl";
import { usePendingReviewPayments, useReviewPayment } from "@/features/payments/services/payments-service";
import {
  compareNonNegativeDecimalStrings,
  normalizePaymentMoneyInput,
} from "@/features/payments/types/decimal-strings";
import { exceedsMaxMoney } from "@/features/payments/types/receipt-amounts";
import type {
  Currency,
  DecimalString,
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

function formatMoneyByCurrency(amount: DecimalString, currency: "ARS" | "USD"): string {
  // DISPLAY ONLY: approval validation and submission retain the original decimal string.
  return new Intl.NumberFormat("es-AR", {
    style: "currency",
    currency,
  }).format(Number.parseFloat(amount));
}

function formatDate(isoDate: string): string {
  const date = new Date(`${isoDate}T12:00:00Z`);
  return Number.isNaN(date.getTime()) ? isoDate : dateFormatter.format(date);
}

function formatInstallmentList(allocations: PaymentBatchInstallmentDTO[]): string {
  return allocations.map((allocation) => `#${allocation.installmentNumber}`).join(", ");
}

function formatTripLine(tripName: string, allocations: PaymentBatchInstallmentDTO[]): string {
  if (allocations.length === 0) {
    return tripName;
  }
  const installments = formatInstallmentList(allocations);
  return `${tripName} · ${allocations.length > 1 ? "Cuotas" : "Cuota"} ${installments}`;
}

function validateApprovalAmount(input: string): { amount: DecimalString | null; error: string | null } {
  const amount = normalizePaymentMoneyInput(input);
  if (amount == null) {
    return { amount: null, error: "Ingresá un monto válido con hasta dos decimales." };
  }
  if (exceedsMaxMoney(amount)) {
    return { amount: null, error: "El monto no puede superar $99.999.999,99." };
  }
  return { amount, error: null };
}

type CorrectionDirection = "up" | "down" | "none";

function correctionDirection(approved: DecimalString, reported: DecimalString): CorrectionDirection {
  const comparison = compareNonNegativeDecimalStrings(approved, reported);
  if (comparison > 0) {
    return "up";
  }
  if (comparison < 0) {
    return "down";
  }
  return "none";
}

const correctionLabels: Record<CorrectionDirection, string> = {
  up: "Corrección al alza",
  down: "Corrección a la baja",
  none: "Sin corrección",
};

export function PendingReviewPage() {
  const { data, isLoading, error } = usePendingReviewPayments();
  const reviewPayment = useReviewPayment();

  const [search, setSearch] = useState("");
  const [expandedSubmissionIds, setExpandedSubmissionIds] = useState<number[]>([]);
  const [approvedAmounts, setApprovedAmounts] = useState<Record<number, string>>({});
  const [approvedCurrencies, setApprovedCurrencies] = useState<Record<number, Currency>>({});
  const [sliderUpperOverrides, setSliderUpperOverrides] = useState<Record<number, string>>({});
  const [observations, setObservations] = useState<Record<number, string>>({});
  const [actionErrors, setActionErrors] = useState<Record<number, string>>({});

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
      current.includes(submissionId) ? current.filter((item) => item !== submissionId) : [...current, submissionId],
    );
  };

  const clearActionError = (submissionId: number) => {
    setActionErrors((current) => {
      if (!(submissionId in current)) {
        return current;
      }
      const next = { ...current };
      delete next[submissionId];
      return next;
    });
  };

  const resetApprovedAmount = (item: PendingPaymentReviewDTO) => {
    clearActionError(item.submissionId);
    setApprovedCurrencies((current) => ({ ...current, [item.submissionId]: item.paymentCurrency }));
    setApprovedAmounts((current) => ({
      ...current,
      [item.submissionId]: item.reportedAmount,
    }));
    setSliderUpperOverrides((current) => {
      if (!(item.submissionId in current)) {
        return current;
      }
      const next = { ...current };
      delete next[item.submissionId];
      return next;
    });
  };

  const changeApprovedCurrency = (item: PendingPaymentReviewDTO, currency: Currency) => {
    if (currency === (approvedCurrencies[item.submissionId] ?? item.paymentCurrency)) {
      return;
    }
    clearActionError(item.submissionId);
    setApprovedCurrencies((current) => ({ ...current, [item.submissionId]: currency }));
    setApprovedAmounts((current) => ({ ...current, [item.submissionId]: "" }));
    setSliderUpperOverrides((current) => {
      const next = { ...current };
      delete next[item.submissionId];
      return next;
    });
  };

  const submitDecision = async (item: PendingPaymentReviewDTO, approvedAmount: DecimalString, approvedCurrency: Currency) => {
    if (reviewPayment.isPending) {
      return;
    }
    clearActionError(item.submissionId);

    const observation = observations[item.submissionId]?.trim() ?? "";

    try {
      await reviewPayment.mutateAsync({
        id: item.submissionId,
        data: {
          approvedAmount,
          approvedCurrency,
          adminObservation: observation.length > 0 ? observation : undefined,
        },
      });
    } catch (reviewError) {
      setActionErrors((current) => ({
        ...current,
        [item.submissionId]: reviewError instanceof Error ? reviewError.message : "No se pudo guardar la decisión.",
      }));
      setExpandedSubmissionIds((current) =>
        current.includes(item.submissionId) ? current : [...current, item.submissionId],
      );
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
                Cada envío representa un pago completo. Podés aprobar el monto informado o corregirlo según el
                monto realmente acreditado.
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
            {items.length === 0 ? (
              <p className={styles.emptyText}>No hay comprobantes pendientes de revisión.</p>
            ) : null}
            <div className={styles.list}>
              {items.map((item) => {
                const isExpanded = expandedSubmissionIds.includes(item.submissionId);
                const approvedAmountInput = approvedAmounts[item.submissionId] ?? item.reportedAmount;
                const approvedCurrency = approvedCurrencies[item.submissionId] ?? item.paymentCurrency;
                const sameOriginalCurrency = approvedCurrency === item.paymentCurrency;
                const approvalValidation = validateApprovalAmount(approvedAmountInput);
                const observation = observations[item.submissionId] ?? "";
                const correction: CorrectionDirection =
                  !sameOriginalCurrency || approvalValidation.amount == null
                    ? "none"
                    : correctionDirection(approvalValidation.amount, item.reportedAmount);
                // Same-currency payments need no conversion note; only real
                // cross-currency conversions earn a compact equivalence line.
                const showExchangeInfo = item.paymentCurrency !== item.tripCurrency;

                return (
                  <article key={item.submissionId} className={styles.card}>
                    <div className={styles.cardHeader}>
                      <div>
                        <h2 className={styles.cardTitle}>
                          {item.userLastname}, {item.userName}
                        </h2>
                        <p className={styles.cardSubtitle}>
                          {item.studentName || "Sin alumno informado"}
                          {item.studentDni ? ` · DNI ${item.studentDni}` : ""}
                        </p>
                        <p className={styles.emailLine}>{item.userEmail}</p>
                      </div>
                      <span className={styles.pendingBadge}>Pago pendiente</span>
                    </div>

                    <p className={styles.tripLine}>{formatTripLine(item.tripName, item.allocations)}</p>

                    <div className={styles.reportedBlock}>
                      <span className={styles.label}>Monto informado</span>
                      <p className={styles.reportedHero}>
                        {formatMoneyByCurrency(item.reportedAmount, item.paymentCurrency)}
                      </p>
                    </div>

                    {showExchangeInfo ? (
                      <p className={styles.exchangeInfo}>
                        {formatMoneyByCurrency(item.reportedAmount, item.paymentCurrency)} →{" "}
                        {formatMoneyByCurrency(item.amountInTripCurrency, item.tripCurrency)}
                        {item.exchangeRate != null
                          ? ` · TC ${formatMoneyByCurrency(item.exchangeRate, "ARS")}`
                          : ""}
                      </p>
                    ) : null}

                    <p className={styles.metaLine}>
                      {paymentMethodLabels[item.paymentMethod] ?? item.paymentMethod} ·{" "}
                      {formatDate(item.reportedPaymentDate)}
                    </p>
                    <p className={styles.metaLine}>
                      {item.bankAccountDisplayName ?? "Cuenta no informada"}
                      {item.bankAccountAlias ? ` · ${item.bankAccountAlias}` : ""}
                    </p>

                    <AttachmentList
                      receipt={item}
                      className={styles.attachmentBox}
                      imageClassName={styles.attachmentImage}
                    />

                    {isExpanded ? (
                      <div className={`${styles.actionsRow} ${styles.actionsEnd}`}>
                        <button
                          type="button"
                          className={styles.secondaryButton}
                          onClick={() => toggleSubmission(item.submissionId)}
                        >
                          Ocultar
                        </button>
                      </div>
                    ) : (
                      <div className={styles.actionsRow}>
                        <button
                          type="button"
                          className={styles.primaryButton}
                          aria-label="Aprobar monto informado"
                          disabled={reviewPayment.isPending}
                           onClick={() => submitDecision(item, item.reportedAmount, item.paymentCurrency)}
                        >
                          Aprobar
                        </button>
                        <button
                          type="button"
                          className={styles.secondaryButton}
                          onClick={() => toggleSubmission(item.submissionId)}
                        >
                          Revisar monto
                        </button>
                      </div>
                    )}

                    {isExpanded ? (
                      <div className={styles.expandedSection}>
                        <ul className={styles.allocationList}>
                          {item.allocations.map((allocation) => (
                            <li
                              key={`${item.submissionId}-${allocation.installmentId}`}
                              className={styles.allocationRow}
                            >
                              <span className={styles.allocationTitle}>
                                Cuota #{allocation.installmentNumber}
                              </span>
                              <span className={styles.allocationDetail}>
                                vence {formatDate(allocation.dueDate)} · saldo{" "}
                                {formatMoneyByCurrency(allocation.remainingAmount, item.tripCurrency)} ·
                                previsto{" "}
                                {formatMoneyByCurrency(allocation.amountInTripCurrency, item.tripCurrency)}
                              </span>
                            </li>
                          ))}
                        </ul>

                        <div className={styles.decisionPanel}>
                          <label className={styles.searchBox}>
                            <span>Moneda a imputar</span>
                            <select
                              value={approvedCurrency}
                              disabled={reviewPayment.isPending}
                              onChange={(event) => changeApprovedCurrency(item, event.target.value as Currency)}
                            >
                              <option value="ARS">ARS</option>
                              <option value="USD">USD</option>
                            </select>
                          </label>
                          <p className={styles.helpText}>
                            {approvedCurrency === item.tripCurrency
                              ? `Este monto se imputará directamente en ${item.tripCurrency}.`
                              : `Este monto se convertirá a ${item.tripCurrency} usando la cotización correspondiente a la fecha de pago: ${formatDate(item.reportedPaymentDate)}.`}
                          </p>
                          <ApprovedAmountControl
                            key={`${item.submissionId}-${approvedCurrency}`}
                            inputId={`approved-amount-${item.submissionId}`}
                            reportedAmount={item.reportedAmount}
                            paymentCurrency={approvedCurrency}
                            originalCurrency={item.paymentCurrency}
                            disabled={reviewPayment.isPending}
                            value={approvedAmountInput}
                            validationError={approvalValidation.error}
                            validAmount={approvalValidation.amount}
                            correction={correction}
                            correctionLabel={sameOriginalCurrency ? correctionLabels[correction] : "Corrección en otra moneda"}
                            upperOverrideCents={
                              sliderUpperOverrides[item.submissionId] != null
                                ? BigInt(sliderUpperOverrides[item.submissionId])
                                : null
                            }
                            onValueChange={(next) => {
                              clearActionError(item.submissionId);
                              setApprovedAmounts((current) => ({
                                ...current,
                                [item.submissionId]: next,
                              }));
                            }}
                            onUpperOverrideChange={(cents) =>
                              setSliderUpperOverrides((current) => {
                                if (cents == null) {
                                  if (!(item.submissionId in current)) {
                                    return current;
                                  }
                                  const next = { ...current };
                                  delete next[item.submissionId];
                                  return next;
                                }
                                return { ...current, [item.submissionId]: cents.toString() };
                              })
                            }
                            onReset={() => resetApprovedAmount(item)}
                            formatMoney={formatMoneyByCurrency}
                          />
                          {correction !== "none" ? (
                            <p className={styles.helpText}>La imputación se recalcula al guardar.</p>
                          ) : null}

                          <label className={styles.searchBox}>
                            <span>Observación · opcional</span>
                            <input
                               value={observation}
                               disabled={reviewPayment.isPending}
                              maxLength={500}
                              onChange={(event) => {
                                clearActionError(item.submissionId);
                                setObservations((current) => ({
                                  ...current,
                                  [item.submissionId]: event.target.value,
                                }));
                              }}
                              placeholder="Opcional"
                            />
                          </label>

                          <div className={styles.actionsRow}>
                            <button
                              type="button"
                              className={styles.dangerButton}
                              aria-label="Rechazar total"
                              disabled={reviewPayment.isPending}
                              onClick={() => submitDecision(item, "0", item.paymentCurrency)}
                            >
                              Rechazar
                            </button>
                            <button
                              type="button"
                              className={styles.primaryButton}
                              disabled={
                                reviewPayment.isPending ||
                                approvalValidation.amount == null
                              }
                              onClick={() => {
                                if (approvalValidation.amount != null) {
                                  void submitDecision(item, approvalValidation.amount, approvedCurrency);
                                }
                              }}
                            >
                              Guardar decisión
                            </button>
                          </div>
                          {actionErrors[item.submissionId] ? (
                            <p className={styles.errorText} role="alert">
                              {actionErrors[item.submissionId]}
                            </p>
                          ) : null}
                        </div>
                      </div>
                    ) : null}
                  </article>
                );
              })}
            </div>
          </RequestState>
        </div>
      </section>
    </CommonLayout>
  );
}
