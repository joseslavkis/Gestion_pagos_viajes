import { useEffect, useMemo, useRef, useState } from "react";

import { useQueryClient } from "@tanstack/react-query";

import { CommonLayout } from "@/components/CommonLayout/CommonLayout";
import { useBankAccounts } from "@/features/bank-accounts/services/bank-accounts-service";
import type { BankAccountDTO } from "@/features/bank-accounts/types/bank-accounts-dtos";
import { Folder } from "@/features/payments/components/Folder";
import {
  useMyInstallments,
  useRegisterPayment,
} from "@/features/payments/services/payments-service";
import type {
  PaymentMethod,
  UserInstallmentDTO,
} from "@/features/payments/types/payments-dtos";
import { resolveInstallmentBaseDisplay } from "@/lib/installment-status";

import styles from "./UserDashboardPage.module.css";

const currencyFormatter = new Intl.NumberFormat("es-AR", {
  style: "currency",
  currency: "ARS",
});

const usdFormatter = new Intl.NumberFormat("es-AR", {
  style: "currency",
  currency: "USD",
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

  const display = resolveInstallmentBaseDisplay(
    dto.installmentStatus,
    dto.dueDate,
    dto.yellowWarningDays,
  );

  return {
    color: display.tone === "retro" ? "red" : display.tone,
    label: display.label,
  };
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
        installment.installmentStatus !== "GREEN" &&
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

function roundMoney(value: number): number {
  return Math.round((value + Number.EPSILON) * 100) / 100;
}

function getInstallmentRemainingAmount(installment: Pick<UserInstallmentDTO, "totalDue" | "paidAmount">): number {
  return Math.max(0, roundMoney(installment.totalDue - installment.paidAmount));
}

function formatInstallmentAmount(installment: Pick<UserInstallmentDTO, "tripCurrency">, amount: number): string {
  return installment.tripCurrency === "USD" ? usdFormatter.format(amount) : currencyFormatter.format(amount);
}

function formatBankAccountTitle(account: BankAccountDTO): string {
  return `${account.bankName} - ${account.accountLabel}`;
}

export function UserDashboardPage() {
  const queryClient = useQueryClient();
  const registerPayment = useRegisterPayment();
  const { data: installments, isLoading, error } = useMyInstallments();
  const {
    data: bankAccounts,
    isLoading: isBankAccountsLoading,
    error: bankAccountsError,
  } = useBankAccounts();

  const [expandedTripIndexes, setExpandedTripIndexes] = useState<number[]>([]);
  const [selectedTripIndex, setSelectedTripIndex] = useState<number | null>(null);
  const [selectedInstallmentId, setSelectedInstallmentId] = useState<number | null>(null);
  const [reportedAmount, setReportedAmount] = useState("");
  const [reportedPaymentDate, setReportedPaymentDate] = useState(getTodayDate);
  const [paymentCurrency, setPaymentCurrency] = useState<"ARS" | "USD">("ARS");
  const [paymentMethod, setPaymentMethod] = useState<PaymentMethod>("BANK_TRANSFER");
  const [selectedBankAccountId, setSelectedBankAccountId] = useState<number | null>(null);
  const [receiptFile, setReceiptFile] = useState<File | null>(null);
  const [receiptPreviewUrl, setReceiptPreviewUrl] = useState<string | null>(null);
  const [closeFolderSignal, setCloseFolderSignal] = useState(false);
  const [submitError, setSubmitError] = useState<string | null>(null);
  const [successMessage, setSuccessMessage] = useState<string | null>(null);
  const fileInputRef = useRef<HTMLInputElement | null>(null);

  useEffect(() => {
    if (!successMessage) {
      return;
    }

    const timeoutId = window.setTimeout(() => {
      setSuccessMessage(null);
    }, 4000);

    return () => window.clearTimeout(timeoutId);
  }, [successMessage]);

  useEffect(() => {
    return () => {
      if (receiptPreviewUrl) {
        URL.revokeObjectURL(receiptPreviewUrl);
      }
    };
  }, [receiptPreviewUrl]);

  const installmentItems = useMemo(() => installments ?? [], [installments]);
  const bankAccountItems = useMemo(() => bankAccounts ?? [], [bankAccounts]);
  const groups = useMemo(() => buildInstallmentGroups(installmentItems), [installmentItems]);
  const completedGroups = useMemo(
    () => groups.filter((g) => g.installments.every((i) => i.userCompletedTrip)),
    [groups]
  );
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
  const selectedInstallmentRemaining = selectedInstallment
    ? getInstallmentRemainingAmount(selectedInstallment)
    : 0;

  const selectedTripHasPending =
    selectedGroup != null ? findPendingInstallment(selectedGroup) != null : false;

  const availableBankAccounts = useMemo(
    () => bankAccountItems.filter((account) => account.currency === paymentCurrency),
    [bankAccountItems, paymentCurrency],
  );

  const groupedBankAccounts = useMemo(
    () => ({
      ARS: bankAccountItems.filter((account) => account.currency === "ARS"),
      USD: bankAccountItems.filter((account) => account.currency === "USD"),
    }),
    [bankAccountItems],
  );

  const canSubmitPayment =
    selectedTripHasPending &&
    !registerPayment.isPending &&
    !isBankAccountsLoading &&
    availableBankAccounts.length > 0 &&
    selectedBankAccountId != null;

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
    if (availableBankAccounts.length === 0) {
      setSelectedBankAccountId(null);
      return;
    }

    setSelectedBankAccountId((current) => {
      if (current != null && availableBankAccounts.some((account) => account.id === current)) {
        return current;
      }
      return availableBankAccounts[0]?.id ?? null;
    });
  }, [availableBankAccounts]);

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
    setReportedAmount(getInstallmentRemainingAmount(pendingInstallment).toFixed(2));
  }, [selectedTripIndex, groups]);

  const toggleTrip = (tripIndex: number) => {
    setExpandedTripIndexes((current) => {
      if (current.includes(tripIndex)) {
        return current.filter((item) => item !== tripIndex);
      }

      return [...current, tripIndex];
    });
  };

  const handleFileChange = (e: React.ChangeEvent<HTMLInputElement>) => {
    const file = e.target.files?.[0] ?? null;
    if (receiptPreviewUrl) URL.revokeObjectURL(receiptPreviewUrl);
    setReceiptFile(file);
    setReceiptPreviewUrl(file ? URL.createObjectURL(file) : null);
    
    // Trigger the folder close animation
    setCloseFolderSignal(true);
    setTimeout(() => setCloseFolderSignal(false), 200);
  };

  const handleSubmit = async (event: React.FormEvent<HTMLFormElement>) => {
    event.preventDefault();
    setSubmitError(null);
    setSuccessMessage(null);

    if (selectedInstallmentId == null) {
      setSubmitError("Seleccioná un viaje con cuotas pendientes antes de enviar el comprobante.");
      return;
    }

    if (selectedBankAccountId == null) {
      setSubmitError("Selecciona la cuenta donde acreditaste el pago.");
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
        paymentCurrency,
        paymentMethod,
        bankAccountId: selectedBankAccountId,
        file: receiptFile,
      });

      setSelectedTripIndex(null);
      setSelectedInstallmentId(null);
      setReportedAmount("");
      setReportedPaymentDate(getTodayDate());
      setPaymentCurrency("ARS");
      setPaymentMethod("BANK_TRANSFER");
      setSelectedBankAccountId(null);
      setReceiptFile(null);
      if (receiptPreviewUrl) URL.revokeObjectURL(receiptPreviewUrl);
      setReceiptPreviewUrl(null);
      if (fileInputRef.current) fileInputRef.current.value = "";
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
                        <div className={styles.tripGroupActions}>
                          {group.installments.every((i) => i.userCompletedTrip) ? (
                            <span className={styles.tripCompletedBadge}>✓ Viaje completado</span>
                          ) : null}
                          <span className={styles.expandIcon}>{isExpanded ? "−" : "+"}</span>
                        </div>
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
                                {installment.paidAmount > 0 && installment.installmentStatus !== "GREEN" ? (
                                  <p className={styles.chipMeta}>
                                    Abonado: {formatInstallmentAmount(installment, installment.paidAmount)} · Resta:{" "}
                                    {formatInstallmentAmount(
                                      installment,
                                      getInstallmentRemainingAmount(installment),
                                    )}
                                  </p>
                                ) : null}
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
            <h2 className={styles.sectionTitle}>Historial de viajes</h2>
            {completedGroups.length === 0 ? (
              <p className={styles.helperText}>
                Todavía no tenés viajes completados.
              </p>
            ) : (
              <div className={styles.historyList}>
                {completedGroups.map((group, index) => (
                  <div key={group.tripIndex} className={styles.historyItem}>
                    <span className={styles.historyTripName}>
                      Viaje {index + 1}
                    </span>
                    <span className={styles.historyBadge}>✓ Pagado</span>
                  </div>
                ))}
              </div>
            )}
          </section>

          <section className={styles.section}>
            <h2 className={styles.sectionTitle}>Reportar un pago</h2>

            <div className={styles.bankDetailsContainer}>
              {isBankAccountsLoading ? <p className={styles.helperText}>Cargando cuentas bancarias...</p> : null}
              {bankAccountsError ? <p className={styles.errorText}>{bankAccountsError.message}</p> : null}
              {!isBankAccountsLoading && !bankAccountsError && bankAccountItems.length === 0 ? (
                <p className={styles.helperWarning}>Todavía no hay cuentas bancarias activas para mostrar.</p>
              ) : null}
              {(["ARS", "USD"] as const).map((currency) => {
                const accountsForCurrency = groupedBankAccounts[currency];
                if (accountsForCurrency.length === 0) {
                  return null;
                }

                return accountsForCurrency.map((account) => (
                  <div key={account.id} className={styles.bankCard}>
                    <h3 className={styles.bankCardTitle}>{formatBankAccountTitle(account)}</h3>
                    <div>Moneda: <strong>{currency === "USD" ? "Dólares (USD)" : "Pesos (ARS)"}</strong></div>
                    <div>Titular: <strong>{account.accountHolder}</strong></div>
                    <div>Cuenta: <strong>{account.accountNumber}</strong></div>
                    <div>CUIT: <strong>{account.taxId}</strong></div>
                    <div>CBU: <strong>{account.cbu}</strong></div>
                    <div>Alias: <strong>{account.alias}</strong></div>
                  </div>
                ));
              })}
            </div>

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
                      saldo {formatInstallmentAmount(selectedInstallment, selectedInstallmentRemaining)}
                    </span>
                    {selectedInstallmentDisplay ? (
                      <span className={`${styles.statusBadge} ${styles[`status${selectedInstallmentDisplay.color}`]}`}>
                        {selectedInstallmentDisplay.label}
                      </span>
                    ) : null}
                  </div>
                </div>
              ) : null}

              <label className={styles.folderContainer} style={{ cursor: "pointer" }}>
                <input
                  ref={fileInputRef}
                  type="file"
                  accept="image/jpeg,image/png,image/webp,application/pdf"
                  style={{ display: "none" }}
                  onChange={handleFileChange}
                />
                <Folder
                  size={1}
                  color="#0b77d5"
                  forceClose={closeFolderSignal}
                  items={
                    receiptPreviewUrl
                      ? [<img key="preview" src={receiptPreviewUrl} style={{ width: "100%", height: "100%", objectFit: "cover", borderRadius: 6 }} alt="Vista previa" />]
                      : []
                  }
                />
                <p className={styles.folderHint}>
                  {receiptFile
                    ? receiptFile.name
                    : "Hacé click para adjuntar el comprobante (opcional)"}
                </p>
              </label>

              <label className={styles.formField}>
                <span className={styles.label}>Monto pagado</span>
                <input
                  type="number"
                  min="0.01"
                  step="0.01"
                  value={reportedAmount}
                  onChange={(event) => setReportedAmount(event.target.value)}
                  className={styles.input}
                  disabled={!selectedTripHasPending}
                  required
                />
              </label>
              {selectedInstallment ? (
                <p className={styles.helperText}>
                  Saldo restante sugerido:{" "}
                  {formatInstallmentAmount(selectedInstallment, selectedInstallmentRemaining)}
                  {" "}· Si pagás más, el excedente se aplica en cascada a cuotas siguientes.
                </p>
              ) : null}

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
                <span className={styles.label}>Moneda en que pagaste</span>
                <select
                  value={paymentCurrency}
                  onChange={(event) => setPaymentCurrency(event.target.value as "ARS" | "USD")}
                  className={styles.select}
                  disabled={!selectedTripHasPending}
                >
                  <option value="ARS">Pesos (ARS)</option>
                  <option value="USD">Dólares (USD)</option>
                </select>
              </label>

              {selectedInstallment && paymentCurrency !== selectedInstallment.tripCurrency ? (
                <p className={styles.helperWarning}>
                  Se usará el tipo de cambio BNA oficial del día de pago. El administrador verá el detalle de la conversión.
                </p>
              ) : null}

              <label className={styles.formField}>
                <span className={styles.label}>Cuenta donde acreditaste el pago</span>
                <select
                  value={selectedBankAccountId ?? ""}
                  onChange={(event) => setSelectedBankAccountId(event.target.value === "" ? null : Number(event.target.value))}
                  className={styles.select}
                  disabled={!selectedTripHasPending || isBankAccountsLoading || availableBankAccounts.length === 0}
                >
                  <option value="">Elegí una cuenta</option>
                  {availableBankAccounts.map((account) => (
                    <option key={account.id} value={account.id}>
                      {formatBankAccountTitle(account)} · {account.alias}
                    </option>
                  ))}
                </select>
              </label>

              {selectedTripHasPending && !isBankAccountsLoading && availableBankAccounts.length === 0 ? (
                <p className={styles.helperWarning}>
                  No hay cuentas activas disponibles para la moneda seleccionada.
                </p>
              ) : null}

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
                disabled={!canSubmitPayment}
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
