import { useEffect, useMemo, useState } from "react";

import { useQueryClient } from "@tanstack/react-query";

import { CommonLayout } from "@/components/CommonLayout/CommonLayout";
import {
  useMyInstallments,
  useRegisterPayment,
} from "@/features/payments/services/payments-service";
import type {
  PaymentMethod,
  UserInstallmentDTO,
} from "@/features/payments/types/payments-dtos";

import styles from "./UserDashboardPage.module.css";

const currencyFormatter = new Intl.NumberFormat("es-AR", {
  style: "currency",
  currency: "ARS",
});

const dateFormatter = new Intl.DateTimeFormat("es-AR", {
  day: "2-digit",
  month: "2-digit",
  year: "numeric",
});

function getTodayDate() {
  return new Date().toISOString().slice(0, 10);
}

function formatReportedDate(value: string) {
  const parsedDate = new Date(`${value}T00:00:00`);
  if (Number.isNaN(parsedDate.getTime())) {
    return value;
  }

  return dateFormatter.format(parsedDate);
}

function resolveInstallmentDisplay(dto: UserInstallmentDTO): {
  color: "green" | "yellow" | "red";
  label: string;
} {
  if (dto.latestReceiptStatus === "REJECTED") {
    return { color: "red", label: "Comprobante rechazado" };
  }

  if (dto.latestReceiptStatus === "PENDING") {
    return { color: "yellow", label: "En revisión" };
  }

  if (dto.installmentStatus === "RETROACTIVE") {
    return { color: "red", label: "Deuda retroactiva" };
  }

  if (dto.installmentStatus === "RED") {
    return { color: "red", label: "Vencida" };
  }

  if (dto.installmentStatus === "GREEN") {
    if (dto.latestReceiptStatus === "APPROVED") {
      return { color: "green", label: "Pagada" };
    }
    return { color: "green", label: "Al día" };
  }

  if (dto.installmentStatus === "YELLOW") {
    return { color: "yellow", label: "Vence pronto" };
  }

  return { color: "green", label: "Al día" };
}

type InstallmentGroup = {
  tripIndex: number;
  installments: UserInstallmentDTO[];
};

function buildInstallmentGroups(installments: UserInstallmentDTO[]): InstallmentGroup[] {
  if (installments.length === 0) return [];

  const map = new Map<number, UserInstallmentDTO[]>();
  for (const installment of installments) {
    const group = map.get(installment.tripId) ?? [];
    group.push(installment);
    map.set(installment.tripId, group);
  }

  return Array.from(map.values()).map((group, index) => ({
    tripIndex: index,
    installments: group.sort(
      (a, b) => a.installmentNumber - b.installmentNumber,
    ),
  }));
}

function getGroupBadgeColor(group: InstallmentGroup): "green" | "yellow" | "red" {
  const colors = group.installments.map((installment) => resolveInstallmentDisplay(installment).color);
  if (colors.includes("red")) {
    return "red";
  }

  if (colors.includes("yellow")) {
    return "yellow";
  }

  return "green";
}

function findNextDueDate(group: InstallmentGroup): string | null {
  const pending = group.installments.filter(
    (i) =>
      i.latestReceiptStatus !== "APPROVED" &&
      i.installmentStatus !== "GREEN",
  );
  if (pending.length === 0) {
    return group.installments[group.installments.length - 1]?.dueDate ?? null;
  }
  return [...pending].sort((a, b) =>
    a.dueDate.localeCompare(b.dueDate),
  )[0]?.dueDate ?? null;
}

function findPendingInstallment(group: InstallmentGroup): UserInstallmentDTO | null {
  const pending = group.installments
    .filter(
      (installment) =>
        installment.latestReceiptStatus !== "APPROVED" &&
        installment.latestReceiptStatus !== "PENDING",
    )
    .sort((a, b) => {
      if (a.dueDate === b.dueDate) {
        return a.installmentNumber - b.installmentNumber;
      }

      return a.dueDate.localeCompare(b.dueDate);
    });

  return pending[0] ?? null;
}

export function UserDashboardPage() {
  const queryClient = useQueryClient();
  const registerPayment = useRegisterPayment();
  const { data: installments, isLoading, error } = useMyInstallments();

  const [expandedTripIndexes, setExpandedTripIndexes] = useState<number[]>([]);
  const [selectedTripIndex, setSelectedTripIndex] = useState<number | null>(null);
  const [selectedInstallmentId, setSelectedInstallmentId] = useState<number | null>(null);
  const [reportedAmount, setReportedAmount] = useState("");
  const [reportedPaymentDate, setReportedPaymentDate] = useState(getTodayDate);
  const [paymentMethod, setPaymentMethod] = useState<PaymentMethod>("BANK_TRANSFER");
  const [submitError, setSubmitError] = useState<string | null>(null);
  const [successMessage, setSuccessMessage] = useState<string | null>(null);

  useEffect(() => {
    if (!successMessage) {
      return;
    }

    const timeoutId = window.setTimeout(() => {
      setSuccessMessage(null);
    }, 4000);

    return () => window.clearTimeout(timeoutId);
  }, [successMessage]);

  const installmentItems = useMemo(() => installments ?? [], [installments]);
  const groups = useMemo(() => buildInstallmentGroups(installmentItems), [installmentItems]);
  const allInstallments = useMemo(
    () => groups.flatMap((g) => g.installments),
    [groups],
  );

  const selectedGroup =
    selectedTripIndex != null ? groups.find((group) => group.tripIndex === selectedTripIndex) ?? null : null;

  const selectedInstallment = useMemo(
    () =>
      selectedInstallmentId != null
        ? allInstallments.find(
            (i) => i.installmentId === selectedInstallmentId,
          ) ?? null
        : null,
    [selectedInstallmentId, allInstallments],
  );

  const selectedInstallmentDisplay = selectedInstallment
    ? resolveInstallmentDisplay(selectedInstallment)
    : null;

  const selectedTripHasPending =
    selectedGroup != null ? findPendingInstallment(selectedGroup) != null : false;

  useEffect(() => {
    if (groups.length === 0) {
      setExpandedTripIndexes([]);
      return;
    }

    setExpandedTripIndexes((current) => {
      if (current.length === 0) {
        return [groups[0].tripIndex];
      }

      const availableIndexes = new Set(groups.map((group) => group.tripIndex));
      const filtered = current.filter((index) => availableIndexes.has(index));
      return filtered.length > 0 ? filtered : [groups[0].tripIndex];
    });
  }, [groups]);

  useEffect(() => {
    if (selectedTripIndex == null) {
      setSelectedInstallmentId(null);
      setReportedAmount("");
      return;
    }

    const group = groups.find((item) => item.tripIndex === selectedTripIndex) ?? null;
    if (!group) {
      setSelectedInstallmentId(null);
      setReportedAmount("");
      return;
    }

    const pendingInstallment = findPendingInstallment(group);
    if (!pendingInstallment) {
      setSelectedInstallmentId(null);
      setReportedAmount("");
      return;
    }

    setSelectedInstallmentId(pendingInstallment.installmentId);
    setReportedAmount(String(pendingInstallment.totalDue));
  }, [selectedTripIndex, groups]);

  const toggleTrip = (tripIndex: number) => {
    setExpandedTripIndexes((current) => {
      if (current.includes(tripIndex)) {
        return current.filter((item) => item !== tripIndex);
      }

      return [...current, tripIndex];
    });
  };

  const handleSubmit = async (event: React.FormEvent<HTMLFormElement>) => {
    event.preventDefault();
    setSubmitError(null);
    setSuccessMessage(null);

    if (selectedInstallmentId == null) {
      setSubmitError("Seleccioná un viaje con cuotas pendientes antes de enviar el comprobante.");
      return;
    }

    const parsedAmount = Number(reportedAmount);

    if (!Number.isFinite(parsedAmount) || parsedAmount <= 0) {
      setSubmitError("Ingresá un monto válido mayor a 0.");
      return;
    }

    try {
      await registerPayment.mutateAsync({
        installmentId: selectedInstallmentId,
        reportedAmount: parsedAmount,
        reportedPaymentDate,
        paymentMethod,
      });

      setSelectedTripIndex(null);
      setSelectedInstallmentId(null);
      setReportedAmount("");
      setReportedPaymentDate(getTodayDate());
      setPaymentMethod("BANK_TRANSFER");
      setSuccessMessage("Comprobante enviado. El administrador lo revisará pronto.");

      await queryClient.invalidateQueries({ queryKey: ["payments", "my"] });
      await queryClient.invalidateQueries({ queryKey: ["payments", "my", "installments"] });
    } catch (submitError) {
      setSubmitError(
        submitError instanceof Error
          ? submitError.message
          : "No se pudo enviar el comprobante. Intentá nuevamente.",
      );
    }
  };

  return (
    <CommonLayout>
      <section className={styles.page}>
        <div className={styles.container}>
          <h1 className={styles.title}>Panel de pagos</h1>

          <section className={styles.section}>
            <h2 className={styles.sectionTitle}>Mis cuotas</h2>

            {isLoading ? <p className={styles.helperText}>Cargando cuotas...</p> : null}
            {error ? <p className={styles.errorText}>{error.message}</p> : null}
            {!isLoading && !error && installmentItems.length === 0 ? (
              <p className={styles.helperText}>Todavía no estás inscripto en ningún viaje</p>
            ) : null}

            {!isLoading && !error && groups.length > 0 ? (
              <div className={styles.tripGroupList}>
                {groups.map((group, index) => {
                  const isExpanded = expandedTripIndexes.includes(group.tripIndex);
                  const nextDueDate = findNextDueDate(group);
                  const groupColor = getGroupBadgeColor(group);

                  return (
                    <article
                      key={`trip-${group.tripIndex}`}
                      className={`${styles.tripGroupCard} ${styles[`tripGroup${groupColor}`]}`}
                    >
                      <button
                        type="button"
                        className={styles.tripGroupHeader}
                        onClick={() => toggleTrip(group.tripIndex)}
                      >
                        <div className={styles.tripGroupTitleBlock}>
                          <h3 className={styles.tripGroupTitle}>Viaje {index + 1}</h3>
                          <p className={styles.tripGroupSummary}>
                            {group.installments.length} cuotas
                            {nextDueDate ? ` · próximo vencimiento ${formatReportedDate(nextDueDate)}` : ""}
                          </p>
                        </div>
                        <span className={styles.expandIcon}>{isExpanded ? "−" : "+"}</span>
                      </button>

                      {isExpanded ? (
                        <div className={styles.installmentGrid}>
                          {group.installments.map((installment) => {
                            const display = resolveInstallmentDisplay(installment);

                            return (
                              <div key={installment.installmentId} className={styles.installmentChip}>
                                <div className={styles.chipHeader}>
                                  <h4 className={styles.chipTitle}>Cuota {installment.installmentNumber}</h4>
                                  <span className={`${styles.statusBadge} ${styles[`status${display.color}`]}`}>
                                    {display.label}
                                  </span>
                                </div>

                                <p className={styles.chipMeta}>{currencyFormatter.format(installment.totalDue)}</p>
                                <p className={styles.chipMeta}>
                                  Vence: {formatReportedDate(installment.dueDate)}
                                </p>

                                {installment.latestReceiptStatus === "REJECTED" &&
                                installment.latestReceiptObservation ? (
                                  <p className={styles.rejectedObservation}>
                                    ⚠ {installment.latestReceiptObservation}
                                  </p>
                                ) : null}

                                {installment.latestReceiptStatus === "PENDING" ? (
                                  <p className={styles.pendingObservation}>
                                    Tu comprobante está siendo revisado por el administrador
                                  </p>
                                ) : null}
                              </div>
                            );
                          })}
                        </div>
                      ) : null}
                    </article>
                  );
                })}
              </div>
            ) : null}
          </section>

          <section className={styles.section}>
            <h2 className={styles.sectionTitle}>Reportar un pago</h2>

            <form className={styles.form} onSubmit={handleSubmit}>
              <label className={styles.formField}>
                <span className={styles.label}>Seleccioná el viaje</span>
                <select
                  value={selectedTripIndex ?? ""}
                  onChange={(event) => {
                    const value = event.target.value;
                    setSelectedTripIndex(value === "" ? null : Number(value));
                  }}
                  className={styles.select}
                >
                  <option value="">Elegí una opción</option>
                  {groups.map((group, index) => (
                    <option key={group.tripIndex} value={group.tripIndex}>
                      Viaje {index + 1}
                    </option>
                  ))}
                </select>
              </label>

              {selectedTripIndex != null && !selectedTripHasPending ? (
                <p className={styles.helperWarning}>Este viaje no tiene cuotas pendientes</p>
              ) : null}

              {selectedInstallment ? (
                <div className={styles.selectedInfoBox}>
                  <div className={styles.selectedInfoHeader}>
                    <span>
                      Cuota {selectedInstallment.installmentNumber} · vence {formatReportedDate(selectedInstallment.dueDate)} ·{" "}
                      {currencyFormatter.format(selectedInstallment.totalDue)}
                    </span>
                    {selectedInstallmentDisplay ? (
                      <span className={`${styles.statusBadge} ${styles[`status${selectedInstallmentDisplay.color}`]}`}>
                        {selectedInstallmentDisplay.label}
                      </span>
                    ) : null}
                  </div>
                </div>
              ) : null}

              <label className={styles.formField}>
                <span className={styles.label}>Monto pagado</span>
                <input
                  type="number"
                  min="0"
                  step="0.01"
                  value={reportedAmount}
                  onChange={(event) => setReportedAmount(event.target.value)}
                  className={styles.input}
                  disabled={!selectedTripHasPending}
                  required
                />
              </label>

              <label className={styles.formField}>
                <span className={styles.label}>Fecha de pago</span>
                <input
                  type="date"
                  value={reportedPaymentDate}
                  onChange={(event) => setReportedPaymentDate(event.target.value)}
                  className={styles.input}
                  disabled={!selectedTripHasPending}
                  required
                />
              </label>

              <label className={styles.formField}>
                <span className={styles.label}>Método de pago</span>
                <select
                  value={paymentMethod}
                  onChange={(event) => setPaymentMethod(event.target.value as PaymentMethod)}
                  className={styles.select}
                  disabled={!selectedTripHasPending}
                >
                  <option value="BANK_TRANSFER">Transferencia bancaria</option>
                  <option value="CASH">Efectivo</option>
                  <option value="CARD">Tarjeta</option>
                  <option value="OTHER">Otro</option>
                </select>
              </label>

              <button
                type="submit"
                className={styles.submitButton}
                disabled={registerPayment.isPending || !selectedTripHasPending}
              >
                {registerPayment.isPending ? "Enviando..." : "Enviar comprobante"}
              </button>

              {submitError ? <p className={styles.errorText}>{submitError}</p> : null}
              {successMessage ? <p className={styles.successText}>{successMessage}</p> : null}
            </form>
          </section>
        </div>
      </section>
    </CommonLayout>
  );
}

