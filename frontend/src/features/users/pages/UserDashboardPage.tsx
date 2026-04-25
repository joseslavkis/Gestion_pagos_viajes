import { useEffect, useMemo, useRef, useState } from "react";
import { toast } from "sonner";

import { useQueryClient } from "@tanstack/react-query";

import { CommonLayout } from "@/components/CommonLayout/CommonLayout";
import { useBankAccounts } from "@/features/bank-accounts/services/bank-accounts-service";
import type { BankAccountDTO } from "@/features/bank-accounts/types/bank-accounts-dtos";
import { Folder } from "@/features/payments/components/Folder";
import {
  useMyInstallments,
  usePaymentPreview,
  useRegisterPayment,
} from "@/features/payments/services/payments-service";
import type {
  Currency,
  PaymentMethod,
  PaymentBatchPreviewDTO,
  UserInstallmentDTO,
} from "@/features/payments/types/payments-dtos";
import { PaymentBatchPreviewDTOSchema } from "@/features/payments/types/payments-dtos";
import { type ReceiptSuccessData, ReceiptSuccessScreen } from "@/features/payments/components/ReceiptSuccessScreen";
import { ApiError } from "@/lib/api-error";
import { apiPost } from "@/lib/api-client";
import { useToken } from "@/lib/session";

import styles from "./UserDashboardPage.module.css";

const ARGENTINA_TIME_ZONE = "America/Argentina/Buenos_Aires";

const currencyFormatter = new Intl.NumberFormat("es-AR", {
  style: "currency",
  currency: "ARS",
});

const usdFormatter = new Intl.NumberFormat("es-AR", {
  style: "currency",
  currency: "USD",
});

const dateFormatter = new Intl.DateTimeFormat("es-AR", {
  timeZone: "UTC",
  day: "2-digit",
  month: "2-digit",
  year: "numeric",
});

const argentinaDateInputFormatter = new Intl.DateTimeFormat("en-CA", {
  timeZone: ARGENTINA_TIME_ZONE,
  year: "numeric",
  month: "2-digit",
  day: "2-digit",
});

type InstallmentGroup = {
  groupKey: string;
  tripId: number;
  tripName: string;
  studentId: number | null;
  studentName: string | null;
  studentDni: string | null;
  installments: UserInstallmentDTO[];
};

function getTodayDate() {
  const parts = argentinaDateInputFormatter.formatToParts(new Date());
  const year = parts.find((part) => part.type === "year")?.value;
  const month = parts.find((part) => part.type === "month")?.value;
  const day = parts.find((part) => part.type === "day")?.value;

  if (!year || !month || !day) {
    return new Date().toISOString().slice(0, 10);
  }

  return `${year}-${month}-${day}`;
}

function formatReportedDate(value: string) {
  const [year, month, day] = value.split("-").map(Number);
  if (
    !Number.isInteger(year) ||
    !Number.isInteger(month) ||
    !Number.isInteger(day)
  ) {
    return value;
  }

  const parsedDate = new Date(Date.UTC(year, month - 1, day));
  if (Number.isNaN(parsedDate.getTime())) {
    return value;
  }

  return dateFormatter.format(parsedDate);
}

function resolveInstallmentDisplay(dto: UserInstallmentDTO): {
  color: "green" | "yellow" | "red";
  label: string;
} {
  return {
    color: dto.uiStatusTone,
    label: dto.uiStatusLabel,
  };
}

function buildInstallmentGroups(installments: UserInstallmentDTO[]): InstallmentGroup[] {
  if (installments.length === 0) return [];

  const map = new Map<string, InstallmentGroup>();
  for (const installment of installments) {
    const groupKey = `${installment.tripId}:${installment.studentId ?? "legacy"}`;
    const current = map.get(groupKey);

    if (current) {
      current.installments.push(installment);
      continue;
    }

    map.set(groupKey, {
      groupKey,
      tripId: installment.tripId,
      tripName: installment.tripName,
      studentId: installment.studentId,
      studentName: installment.studentName,
      studentDni: installment.studentDni,
      installments: [installment],
    });
  }

  return Array.from(map.values()).map((group) => ({
    ...group,
    installments: [...group.installments].sort((a, b) => a.installmentNumber - b.installmentNumber),
  }));
}

function getGroupBadgeColor(group: InstallmentGroup): "green" | "yellow" | "red" {
  if (groupHasPendingReview(group)) {
    return "yellow";
  }

  const colors = group.installments.map((installment) => installment.uiStatusTone);
  if (colors.includes("red")) {
    return "red";
  }

  if (colors.includes("yellow")) {
    return "yellow";
  }

  return "green";
}

function isInstallmentCovered(installment: Pick<UserInstallmentDTO, "totalDue" | "paidAmount">): boolean {
  return getInstallmentRemainingAmount(installment) <= 0;
}

function getPayableInstallments(group: InstallmentGroup): UserInstallmentDTO[] {
  return [...group.installments]
    .filter((installment) => !isInstallmentCovered(installment))
    .sort((a, b) => a.installmentNumber - b.installmentNumber);
}

function groupHasPendingReview(group: InstallmentGroup): boolean {
  return group.installments.some((installment) => installment.latestReceiptStatus === "PENDING");
}

function findNextDueDate(group: InstallmentGroup): string | null {
  const pending = getPayableInstallments(group);
  if (pending.length === 0) {
    return group.installments[group.installments.length - 1]?.dueDate ?? null;
  }
  return pending[0]?.dueDate ?? null;
}

function findPendingInstallment(group: InstallmentGroup): UserInstallmentDTO | null {
  return getPayableInstallments(group)[0] ?? null;
}

function roundMoney(value: number): number {
  return Math.round((value + Number.EPSILON) * 100) / 100;
}

function parseAmountInput(value: string): number {
  const normalized = value.replace(",", ".").trim();
  if (normalized.length === 0) {
    return 0;
  }

  const parsed = Number.parseFloat(normalized);
  return Number.isFinite(parsed) ? roundMoney(parsed) : 0;
}

function formatAmountInput(value: number): string {
  return String(roundMoney(value));
}

function getInstallmentRemainingAmount(installment: Pick<UserInstallmentDTO, "totalDue" | "paidAmount">): number {
  return Math.max(0, roundMoney(installment.totalDue - installment.paidAmount));
}

function formatInstallmentAmount(installment: Pick<UserInstallmentDTO, "tripCurrency">, amount: number): string {
  return installment.tripCurrency === "USD" ? usdFormatter.format(amount) : currencyFormatter.format(amount);
}

function formatAmountByCurrency(currency: "ARS" | "USD", amount: number): string {
  return currency === "USD" ? usdFormatter.format(amount) : currencyFormatter.format(amount);
}

function getGroupCurrency(group: InstallmentGroup): "ARS" | "USD" {
  return group.installments[0]?.tripCurrency ?? "ARS";
}

function getGroupTotalDue(group: InstallmentGroup): number {
  return roundMoney(
    group.installments.reduce((sum, installment) => sum + installment.totalDue, 0),
  );
}

function getGroupRemainingAmount(group: InstallmentGroup): number {
  return roundMoney(
    group.installments.reduce(
      (sum, installment) => sum + getInstallmentRemainingAmount(installment),
      0,
    ),
  );
}

function getGroupPaidAmount(group: InstallmentGroup): number {
  return roundMoney(getGroupTotalDue(group) - getGroupRemainingAmount(group));
}

function formatInstallmentsLabel(installments: Array<{ installmentNumber: number }>): string {
  if (installments.length === 0) {
    return "";
  }
  if (installments.length === 1) {
    return `#${installments[0].installmentNumber}`;
  }
  return installments.map((installment) => `#${installment.installmentNumber}`).join(", ");
}

function formatBankAccountTitle(account: BankAccountDTO): string {
  return `${account.bankName} - ${account.accountLabel}`;
}

function getGroupDisplayName(group: InstallmentGroup, index: number): string {
  return group.studentName ? `${group.tripName} - ${group.studentName}` : group.tripName || `Viaje ${index + 1}`;
}

function convertReportedAmountFromTripCurrency(
  amountInTripCurrency: number,
  tripCurrency: Currency,
  paymentCurrency: Currency,
  exchangeRate: number | null,
): number | null {
  if (paymentCurrency === tripCurrency) {
    return roundMoney(amountInTripCurrency);
  }

  if (exchangeRate == null || exchangeRate <= 0) {
    return null;
  }

  if (tripCurrency === "ARS" && paymentCurrency === "USD") {
    return roundMoney(amountInTripCurrency / exchangeRate);
  }

  if (tripCurrency === "USD" && paymentCurrency === "ARS") {
    return roundMoney(amountInTripCurrency * exchangeRate);
  }

  return null;
}

export function UserDashboardPage() {
  const [tokenState] = useToken();
  const queryClient = useQueryClient();
  const registerPayment = useRegisterPayment();
  const { data: installments, isLoading, error } = useMyInstallments();
  const {
    data: bankAccounts,
    isLoading: isBankAccountsLoading,
    error: bankAccountsError,
  } = useBankAccounts();

  const [expandedGroupKeys, setExpandedGroupKeys] = useState<string[]>([]);
  const [selectedGroupKey, setSelectedGroupKey] = useState<string | null>(null);
  const [selectedAnchorInstallmentId, setSelectedAnchorInstallmentId] = useState<number | null>(null);
  const [reportedAmountInput, setReportedAmountInput] = useState("");
  const [reportedPaymentDate, setReportedPaymentDate] = useState(getTodayDate);
  const [paymentCurrency, setPaymentCurrency] = useState<Currency>("ARS");
  const [paymentMethod, setPaymentMethod] = useState<PaymentMethod>("BANK_TRANSFER");
  const [selectedBankAccountId, setSelectedBankAccountId] = useState<number | null>(null);
  const [receiptFile, setReceiptFile] = useState<File | null>(null);
  const [receiptPreviewUrl, setReceiptPreviewUrl] = useState<string | null>(null);
  const [closeFolderSignal, setCloseFolderSignal] = useState(false);
  const [isDropzoneHovered, setIsDropzoneHovered] = useState(false);
  const [receiptSuccessData, setReceiptSuccessData] = useState<ReceiptSuccessData | null>(null);
  const [isCurrencyAmountUpdating, setIsCurrencyAmountUpdating] = useState(false);
  const fileInputRef = useRef<HTMLInputElement | null>(null);

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
  const allInstallments = useMemo(() => groups.flatMap((group) => group.installments), [groups]);

  const selectedGroup = selectedGroupKey != null
    ? groups.find((group) => group.groupKey === selectedGroupKey) ?? null
    : null;

  const selectedInstallment = useMemo(
    () =>
      selectedAnchorInstallmentId != null
        ? allInstallments.find((installment) => installment.installmentId === selectedAnchorInstallmentId) ?? null
        : null,
    [selectedAnchorInstallmentId, allInstallments],
  );

  const selectedInstallmentDisplay = selectedInstallment ? resolveInstallmentDisplay(selectedInstallment) : null;
  const selectedInstallmentRemaining = selectedInstallment ? getInstallmentRemainingAmount(selectedInstallment) : 0;
  const selectedGroupHasPendingReview = selectedGroup != null ? groupHasPendingReview(selectedGroup) : false;
  const selectableInstallments = selectedGroup != null ? getPayableInstallments(selectedGroup) : [];
  const selectedTripHasPending = selectableInstallments.length > 0;
  const reportedAmountValue = parseAmountInput(reportedAmountInput);

  const previewPayload = selectedAnchorInstallmentId != null && !selectedGroupHasPendingReview
    ? {
        anchorInstallmentId: selectedAnchorInstallmentId,
        reportedAmount: reportedAmountValue,
        reportedPaymentDate,
        paymentCurrency,
      }
    : null;
  const {
    data: paymentPreview,
    isLoading: isPaymentPreviewLoading,
    error: paymentPreviewError,
  } = usePaymentPreview(previewPayload);

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
    !selectedGroupHasPendingReview &&
    !registerPayment.isPending &&
    !isPaymentPreviewLoading &&
    paymentPreview != null &&
    reportedAmountValue > 0 &&
    !isBankAccountsLoading &&
    availableBankAccounts.length > 0 &&
    selectedBankAccountId != null &&
    receiptFile != null;

  useEffect(() => {
    if (groups.length === 0) {
      setExpandedGroupKeys([]);
      setSelectedGroupKey(null);
      return;
    }

    setExpandedGroupKeys((current) => {
      if (current.length === 0) {
        return [groups[0].groupKey];
      }

      const availableKeys = new Set(groups.map((group) => group.groupKey));
      const filtered = current.filter((groupKey) => availableKeys.has(groupKey));
      return filtered.length > 0 ? filtered : [groups[0].groupKey];
    });

    setSelectedGroupKey((current) => {
      if (current && groups.some((group) => group.groupKey === current)) {
        return current;
      }
      return current ?? groups[0].groupKey;
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
    if (selectedGroupKey == null) {
      setSelectedAnchorInstallmentId(null);
      setReportedAmountInput("");
      return;
    }

    const group = groups.find((item) => item.groupKey === selectedGroupKey) ?? null;
    if (!group) {
      setSelectedAnchorInstallmentId(null);
      setReportedAmountInput("");
      return;
    }

    const pendingInstallment = findPendingInstallment(group);
    if (!pendingInstallment) {
      setSelectedAnchorInstallmentId(null);
      setReportedAmountInput("");
      return;
    }

    setSelectedAnchorInstallmentId(pendingInstallment.installmentId);
    setPaymentCurrency(pendingInstallment.tripCurrency);
    setReportedAmountInput(formatAmountInput(getInstallmentRemainingAmount(pendingInstallment)));
  }, [selectedGroupKey, groups]);

  const fetchPaymentPreviewSnapshot = async (
    payload: Parameters<typeof apiPost<PaymentBatchPreviewDTO>>[1] & {
      anchorInstallmentId: number;
      reportedAmount: number;
      reportedPaymentDate: string;
      paymentCurrency: Currency;
    },
  ) =>
    queryClient.fetchQuery<PaymentBatchPreviewDTO, ApiError>({
      queryKey: [
        "payments",
        "preview",
        payload.anchorInstallmentId,
        payload.reportedAmount,
        payload.reportedPaymentDate,
        payload.paymentCurrency,
      ],
      staleTime: 0,
      queryFn: async () =>
        apiPost(
          "/api/v1/payments/preview",
          payload,
          (json) => PaymentBatchPreviewDTOSchema.parse(json),
          {
            headers:
              tokenState.state === "LOGGED_IN"
                ? {
                    Authorization: `Bearer ${tokenState.accessToken}`,
                  }
                : undefined,
          },
        ),
    });

  const handlePaymentCurrencyChange = async (nextCurrency: Currency) => {
    if (nextCurrency === paymentCurrency) {
      return;
    }

    const tripCurrency = selectedInstallment?.tripCurrency ?? null;
    const amountInTripCurrency =
      tripCurrency == null
        ? null
        : paymentCurrency === tripCurrency
          ? reportedAmountValue
          : paymentPreview?.paymentCurrency === paymentCurrency
            ? paymentPreview.amountInTripCurrency
            : null;

    let nextAmountInput = reportedAmountInput;

    if (tripCurrency != null && amountInTripCurrency != null && amountInTripCurrency > 0) {
      const existingExchangeRate =
        paymentPreview?.paymentCurrency === paymentCurrency ? paymentPreview.exchangeRate : null;
      let nextExchangeRate = existingExchangeRate;

      if (nextCurrency !== tripCurrency && (nextExchangeRate == null || nextExchangeRate <= 0) && selectedAnchorInstallmentId != null) {
        setIsCurrencyAmountUpdating(true);
        try {
          const conversionPreview = await fetchPaymentPreviewSnapshot({
            anchorInstallmentId: selectedAnchorInstallmentId,
            reportedAmount: 1,
            reportedPaymentDate,
            paymentCurrency: nextCurrency,
          });
          nextExchangeRate = conversionPreview.exchangeRate;
        } catch {
          nextExchangeRate = null;
        } finally {
          setIsCurrencyAmountUpdating(false);
        }
      }

      const convertedAmount = convertReportedAmountFromTripCurrency(
        amountInTripCurrency,
        tripCurrency,
        nextCurrency,
        nextExchangeRate,
      );

      if (convertedAmount != null) {
        nextAmountInput = formatAmountInput(convertedAmount);
      }
    }

    setPaymentCurrency(nextCurrency);
    setReportedAmountInput(nextAmountInput);
  };

  const toggleGroup = (groupKey: string) => {
    setExpandedGroupKeys((current) => {
      if (current.includes(groupKey)) {
        return current.filter((item) => item !== groupKey);
      }

      return [...current, groupKey];
    });
  };

  const handleFileChange = (event: React.ChangeEvent<HTMLInputElement>) => {
    const file = event.target.files?.[0] ?? null;
    if (receiptPreviewUrl) {
      URL.revokeObjectURL(receiptPreviewUrl);
    }
    setReceiptFile(file);
    setReceiptPreviewUrl(file ? URL.createObjectURL(file) : null);
    setCloseFolderSignal(true);
    setTimeout(() => setCloseFolderSignal(false), 200);
  };

  const handleSubmit = async (event: React.FormEvent<HTMLFormElement>) => {
    event.preventDefault();

    if (selectedAnchorInstallmentId == null) {
      toast.error("Seleccioná una inscripción con cuotas pendientes antes de enviar el comprobante.");
      return;
    }

    if (selectedGroupHasPendingReview) {
      toast.error("Esta inscripción tiene comprobantes pendientes de revisión y no admite nuevos pagos.");
      return;
    }

    if (selectedBankAccountId == null) {
      toast.error("Selecciona la cuenta donde acreditaste el pago.");
      return;
    }

    if (!receiptFile) {
      toast.error("Debés adjuntar el comprobante de pago antes de enviar.");
      return;
    }

    if (!paymentPreview) {
      toast.error("Todavía no pudimos calcular la imputación del pago. Reintentá en unos segundos.");
      return;
    }

    if (reportedAmountValue <= 0) {
      toast.error("Ingresá un monto válido antes de enviar el comprobante.");
      return;
    }

    // Capture display data before resetting state
    const groupIndex = groups.findIndex((g) => g.groupKey === selectedGroupKey);
    const tripDisplayName = selectedGroup ? getGroupDisplayName(selectedGroup, groupIndex) : "";
    const installmentsLabelSnapshot = formatInstallmentsLabel(paymentPreview.installments);
    const amountSnapshot = formatAmountByCurrency(paymentPreview.paymentCurrency, paymentPreview.reportedAmount);
    const dateSnapshot = formatReportedDate(reportedPaymentDate);
    const methodSnapshot = paymentMethod;
    const bankSnapshot =
      availableBankAccounts.find((a) => a.id === selectedBankAccountId)?.accountLabel ??
      availableBankAccounts.find((a) => a.id === selectedBankAccountId)?.bankName ??
      "";
    const fileNameSnapshot = receiptFile.name;

    try {
      await registerPayment.mutateAsync({
        anchorInstallmentId: selectedAnchorInstallmentId,
        reportedAmount: reportedAmountValue,
        reportedPaymentDate,
        paymentCurrency,
        paymentMethod,
        bankAccountId: selectedBankAccountId,
        file: receiptFile,
      });

      setSelectedAnchorInstallmentId(null);
      setReportedAmountInput("");
      setReportedPaymentDate(getTodayDate());
      setPaymentCurrency(selectedInstallment?.tripCurrency ?? "ARS");
      setPaymentMethod("BANK_TRANSFER");
      setSelectedBankAccountId(null);
      setReceiptFile(null);
      if (receiptPreviewUrl) URL.revokeObjectURL(receiptPreviewUrl);
      setReceiptPreviewUrl(null);
      if (fileInputRef.current) fileInputRef.current.value = "";

      setReceiptSuccessData({
        tripDisplayName,
        installmentsLabel: installmentsLabelSnapshot,
        amount: amountSnapshot,
        paymentDate: dateSnapshot,
        paymentMethod: methodSnapshot,
        bankAccountName: bankSnapshot,
        fileName: fileNameSnapshot,
      });

      await queryClient.invalidateQueries({ queryKey: ["payments", "my"] });
      await queryClient.invalidateQueries({ queryKey: ["payments", "my", "installments"] });
    } catch {
      // API errors are handled by global MutationCache
    }
  };

  return (
    <CommonLayout>
      {receiptSuccessData ? (
        <ReceiptSuccessScreen
          data={receiptSuccessData}
          onBack={() => setReceiptSuccessData(null)}
        />
      ) : null}
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
                  const isExpanded = expandedGroupKeys.includes(group.groupKey);
                  const nextDueDate = findNextDueDate(group);
                  const groupColor = getGroupBadgeColor(group);
                  const accountCurrency = getGroupCurrency(group);
                  const totalPaid = getGroupPaidAmount(group);
                  const totalRemaining = getGroupRemainingAmount(group);

                  return (
                    <article
                      key={group.groupKey}
                      className={`${styles.tripGroupCard} ${styles[`tripGroup${groupColor}`]}`}
                    >
                      <button
                        type="button"
                        className={styles.tripGroupHeader}
                        onClick={() => toggleGroup(group.groupKey)}
                      >
                        <div className={styles.tripGroupTitleBlock}>
                          <h3 className={styles.tripGroupTitle}>{getGroupDisplayName(group, index)}</h3>
                          <p className={styles.tripGroupSummary}>
                            {group.installments.length} cuotas
                            {nextDueDate ? ` · próximo vencimiento ${formatReportedDate(nextDueDate)}` : ""}
                          </p>
                          <p className={styles.tripGroupSummaryAccount}>
                            Estado de cuenta: Pagado {formatAmountByCurrency(accountCurrency, totalPaid)} · Resta {formatAmountByCurrency(accountCurrency, totalRemaining)}
                          </p>
                          {group.studentDni ? (
                            <p className={styles.tripGroupSummary}>DNI alumno: {group.studentDni}</p>
                          ) : null}
                        </div>
                        <div className={styles.tripGroupActions}>
                          {group.installments.every((installment) => installment.userCompletedTrip) ? (
                            <span className={styles.tripCompletedBadge}>✓ Viaje completado</span>
                          ) : null}
                          <span className={styles.expandIcon}>{isExpanded ? "−" : "+"}</span>
                        </div>
                      </button>

                      {isExpanded ? (
                        <>
                          <div className={styles.installmentGrid}>
                            {group.installments.map((installment) => {
                              const display = resolveInstallmentDisplay(installment);
                              const statusClass =
                                installment.uiStatusCode === "UP_TO_DATE"
                                  ? styles.statusneutral
                                  : styles[`status${display.color}`];

                              return (
                                <div key={installment.installmentId} className={styles.installmentChip}>
                                  <div className={styles.chipHeader}>
                                    <h4 className={styles.chipTitle}>Cuota {installment.installmentNumber}</h4>
                                    <span className={`${styles.statusBadge} ${statusClass}`}>
                                      {display.label}
                                    </span>
                                  </div>

                                  <p className={styles.chipMeta}>{currencyFormatter.format(installment.totalDue)}</p>
                                  {installment.paidAmount > 0 &&
                                  installment.uiStatusCode !== "PAID" &&
                                  getInstallmentRemainingAmount(installment) > 0 ? (
                                    <p className={styles.chipMeta}>
                                      Abonado: {formatInstallmentAmount(installment, installment.paidAmount)} · Resta:{" "}
                                      {formatInstallmentAmount(
                                        installment,
                                        getInstallmentRemainingAmount(installment),
                                      )}
                                    </p>
                                  ) : null}
                                  <p className={styles.chipMeta}>Vence: {formatReportedDate(installment.dueDate)}</p>

                                  {installment.uiStatusCode === "RECEIPT_REJECTED" &&
                                  installment.latestReceiptObservation ? (
                                    <p className={styles.rejectedObservation}>
                                      ⚠ {installment.latestReceiptObservation}
                                    </p>
                                  ) : null}

                                  {installment.uiStatusCode === "UNDER_REVIEW" ? (
                                    <p className={styles.pendingObservation}>
                                      Tu comprobante está siendo revisado por el administrador
                                    </p>
                                  ) : null}
                                </div>
                              );
                            })}
                          </div>

                          <div className={styles.accountSummary}>
                            <span className={styles.accountSummaryTitle}>Estado de cuenta</span>
                            <span>
                              Pagado: {formatAmountByCurrency(accountCurrency, totalPaid)} · Resta: {formatAmountByCurrency(accountCurrency, totalRemaining)}
                            </span>
                          </div>
                        </>
                      ) : null}
                    </article>
                  );
                })}
              </div>
            ) : null}
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
                  value={selectedGroupKey ?? ""}
                  onChange={(event) => setSelectedGroupKey(event.target.value === "" ? null : event.target.value)}
                  className={styles.select}
                >
                  <option value="">Elegí una opción</option>
                  {groups.map((group, index) => (
                    <option key={group.groupKey} value={group.groupKey}>
                      {getGroupDisplayName(group, index)}
                    </option>
                  ))}
                </select>
              </label>

              {selectedGroupKey != null && !selectedTripHasPending ? (
                <p className={styles.helperWarning}>Esta inscripción no tiene cuotas pendientes</p>
              ) : null}
              {selectedGroupHasPendingReview ? (
                <p className={styles.helperWarning}>
                  Esta inscripción tiene comprobantes pendientes de revisión. Hasta que el administrador los revise no podés enviar un nuevo pago.
                </p>
              ) : null}

              {selectedInstallment ? (
                <div className={styles.selectedInfoBox}>
                  <div className={styles.selectedInfoHeader}>
                    <span>
                      {selectedGroup?.studentName ? `${selectedGroup.studentName} · ` : ""}
                      Primera cuota pendiente #{selectedInstallment.installmentNumber} · vence {formatReportedDate(selectedInstallment.dueDate)} ·{" "}
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

              <label
                className={styles.folderContainer}
                style={{ cursor: "pointer" }}
                onMouseEnter={() => setIsDropzoneHovered(true)}
                onMouseLeave={() => setIsDropzoneHovered(false)}
              >
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
                  isHovered={isDropzoneHovered}
                  items={
                    receiptPreviewUrl
                      ? [
                          <img
                            key="preview"
                            src={receiptPreviewUrl}
                            style={{ width: "100%", height: "100%", objectFit: "cover", borderRadius: 6 }}
                            alt="Vista previa"
                          />,
                        ]
                      : []
                  }
                />
                <p className={styles.folderHint}>
                  {receiptFile
                    ? receiptFile.name
                    : <span style={{ color: "#b45309", fontWeight: 600 }}>Hacé click para adjuntar el comprobante (obligatorio)</span>}
                </p>
              </label>

              <label className={styles.formField}>
                <span className={styles.label}>Monto a reportar</span>
                <input
                  type="number"
                  min="0"
                  step="0.01"
                  inputMode="decimal"
                  value={reportedAmountInput}
                  onChange={(event) => setReportedAmountInput(event.target.value)}
                  className={styles.input}
                  disabled={!selectedTripHasPending || selectedGroupHasPendingReview}
                />
              </label>
              {paymentPreview ? (
                <div className={styles.selectedInfoBox}>
                  <div className={styles.selectedInfoHeader}>
                    <span>
                      Se imputa en {formatInstallmentsLabel(paymentPreview.installments)} · monto reportado{" "}
                      {formatAmountByCurrency(paymentPreview.paymentCurrency, paymentPreview.reportedAmount)}
                    </span>
                    <span className={`${styles.statusBadge} ${styles.statusgreen}`}>Monto libre</span>
                  </div>
                  <p className={styles.helperText}>
                    Máximo permitido para esta inscripción:{" "}
                    {formatAmountByCurrency(paymentPreview.paymentCurrency, paymentPreview.maxAllowedAmount)}.
                  </p>
                  <p className={styles.helperText}>
                    Saldo pendiente total:{" "}
                    {formatAmountByCurrency(paymentPreview.tripCurrency, paymentPreview.totalPendingAmountInTripCurrency)}.
                    {" "}Equivale a{" "}
                    {formatAmountByCurrency(paymentPreview.tripCurrency, paymentPreview.amountInTripCurrency)} del viaje
                    {paymentPreview.exchangeRate != null
                      ? ` · tipo de cambio BNA ${formatAmountByCurrency("ARS", paymentPreview.exchangeRate)}`
                      : ""}
                  </p>
                </div>
              ) : null}
              {paymentPreviewError ? (
                <p className={styles.errorText}>{paymentPreviewError.message}</p>
              ) : null}
              {isPaymentPreviewLoading && !paymentPreviewError ? (
                <p className={styles.helperText}>Calculando imputación...</p>
              ) : null}

              {selectedTripHasPending ? (
                <p className={styles.paymentWarning} role="note">
                  Importante: el pago siempre se aplica desde la primera cuota pendiente hacia adelante. Si pagás una
                  cuota y media, la mitad restante quedará imputada como saldo a favor de la siguiente.
                </p>
              ) : null}

              <label className={styles.formField}>
                <span className={styles.label}>Fecha de pago</span>
                <input
                  type="date"
                  value={reportedPaymentDate}
                  onChange={(event) => setReportedPaymentDate(event.target.value)}
                  className={styles.input}
                  disabled={!selectedTripHasPending || selectedGroupHasPendingReview}
                  required
                />
              </label>

              <label className={styles.formField}>
                <span className={styles.label}>Moneda en que pagaste</span>
                <select
                  value={paymentCurrency}
                  onChange={(event) => {
                    void handlePaymentCurrencyChange(event.target.value as Currency);
                  }}
                  className={styles.select}
                  disabled={!selectedTripHasPending || selectedGroupHasPendingReview || isCurrencyAmountUpdating}
                >
                  <option value="ARS">Pesos (ARS)</option>
                  <option value="USD">Dólares (USD)</option>
                </select>
              </label>

              {isCurrencyAmountUpdating ? (
                <p className={styles.helperText}>Actualizando el monto según la moneda seleccionada...</p>
              ) : null}

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
                  disabled={!selectedTripHasPending || selectedGroupHasPendingReview || isBankAccountsLoading || availableBankAccounts.length === 0}
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
                  disabled={!selectedTripHasPending || selectedGroupHasPendingReview}
                >
                  <option value="BANK_TRANSFER">Transferencia bancaria</option>
                  <option value="CASH">Efectivo</option>
                  <option value="DEPOSIT">Depósito</option>
                  <option value="OTHER">Otro</option>
                </select>
              </label>

              <button type="submit" className={styles.submitButton} disabled={!canSubmitPayment}>
                {registerPayment.isPending ? "Enviando..." : "Enviar comprobante"}
              </button>
            </form>
          </section>
        </div>
      </section>
    </CommonLayout>
  );
}
