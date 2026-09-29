import { useQueryClient } from "@tanstack/react-query";
import { useEffect, useMemo, useRef, useState } from "react";
import { toast } from "sonner";

import { CommonLayout } from "@/components/CommonLayout/CommonLayout";
import { useBankAccounts } from "@/features/bank-accounts/services/bank-accounts-service";
import type { BankAccountDTO } from "@/features/bank-accounts/types/bank-accounts-dtos";
import { Folder } from "@/features/payments/components/Folder";
import { type ReceiptSuccessData, ReceiptSuccessScreen } from "@/features/payments/components/ReceiptSuccessScreen";
import { useMyInstallments, usePaymentCalculation, useRegisterPayment } from "@/features/payments/services/payments-service";
import { centsToMoney, moneyCents, receiptSubtotals, type ReceiptAmount } from "@/features/payments/types/receipt-amounts";
import type {
  Currency,
  PaymentCalculationResponseDTO,
  PaymentMethod,
  UserInstallmentDTO,
} from "@/features/payments/types/payments-dtos";

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
  if (!Number.isInteger(year) || !Number.isInteger(month) || !Number.isInteger(day)) {
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

function isInstallmentCovered(installment: Pick<UserInstallmentDTO, "uiStatusCode">): boolean {
  return installment.uiStatusCode === "PAID";
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

// DISPLAY ONLY: these legacy installment summaries never feed payment commands or calculation requests.
function roundDisplayMoney(value: number): number {
  return Math.round((value + Number.EPSILON) * 100) / 100;
}

function getDisplayRemainingAmount(installment: Pick<UserInstallmentDTO, "totalDue" | "paidAmount">): number {
  return Math.max(0, roundDisplayMoney(installment.totalDue - installment.paidAmount));
}

function getInstallmentRemainingAmount(installment: Pick<UserInstallmentDTO, "remainingAmount">): string {
  return installment.remainingAmount;
}

function formatInstallmentAmount(
  installment: Pick<UserInstallmentDTO, "tripCurrency">,
  amount: number | string,
): string {
  const displayAmount = typeof amount === "string" ? Number.parseFloat(amount) : amount;
  return installment.tripCurrency === "USD"
    ? usdFormatter.format(displayAmount)
    : currencyFormatter.format(displayAmount);
}

function formatAmountByCurrency(currency: "ARS" | "USD", amount: number | string): string {
  const displayAmount = typeof amount === "string" ? Number.parseFloat(amount) : amount;
  return currency === "USD" ? usdFormatter.format(displayAmount) : currencyFormatter.format(displayAmount);
}

function getGroupCurrency(group: InstallmentGroup): "ARS" | "USD" {
  return group.installments[0]?.tripCurrency ?? "ARS";
}

function getGroupTotalDue(group: InstallmentGroup): number {
  return roundDisplayMoney(group.installments.reduce((sum, installment) => sum + installment.totalDue, 0));
}

function getGroupRemainingAmount(group: InstallmentGroup): number {
  return roundDisplayMoney(
    group.installments.reduce((sum, installment) => sum + getDisplayRemainingAmount(installment), 0),
  );
}

function getGroupPaidAmount(group: InstallmentGroup): number {
  return roundDisplayMoney(getGroupTotalDue(group) - getGroupRemainingAmount(group));
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

function getCalculationStatusMessage(calculation: PaymentCalculationResponseDTO): string | null {
  if (calculation.status === "READY") {
    return null;
  }
  if (calculation.status === "AMOUNT_EXCEEDS_BALANCE") {
    return calculation.message ?? "El monto supera el saldo disponible. Revisá el máximo permitido.";
  }
  if (calculation.status === "UNPAYABLE") {
    return calculation.message ?? "El saldo no puede pagarse exactamente en la moneda seleccionada.";
  }
  if (calculation.status === "QUOTE_UNAVAILABLE") {
    return calculation.message ?? "No pudimos obtener la cotización para esa fecha.";
  }
  return calculation.message ?? "El cálculo venció. Volvé a intentarlo antes de enviar el comprobante.";
}

export function UserDashboardPage() {
  const queryClient = useQueryClient();
  const registerPayment = useRegisterPayment();
  const { data: installments, isLoading, error } = useMyInstallments();
  const { data: bankAccounts, isLoading: isBankAccountsLoading, error: bankAccountsError } = useBankAccounts();

  const [expandedGroupKeys, setExpandedGroupKeys] = useState<string[]>([]);
  const [selectedGroupKey, setSelectedGroupKey] = useState<string | null>(null);
  const [selectedAnchorInstallmentId, setSelectedAnchorInstallmentId] = useState<number | null>(null);
  const [reportedPaymentDate, setReportedPaymentDate] = useState(getTodayDate);
  const [paymentMethod, setPaymentMethod] = useState<PaymentMethod>("BANK_TRANSFER");
  const [selectedBankAccountId, setSelectedBankAccountId] = useState<number | null>(null);
  const [receipts, setReceipts] = useState<ReceiptAmount[]>([]);
  const nextReceiptId = useRef(0);
  const [receiptRevision, setReceiptRevision] = useState(0);
  const [confirmation, setConfirmation] = useState<{ key: string; revision: number } | null>(null);
  const nextConfirmationRevision = useRef(0);
  const authorityRevision = useRef(0);
  const [isVerifyingConversion, setIsVerifyingConversion] = useState(false);
  const [finalExpiresAt, setFinalExpiresAt] = useState(0);
  const [fileError, setFileError] = useState<string | null>(null);
  const [receiptPreviewUrl, setReceiptPreviewUrl] = useState<string | null>(null);
  const [closeFolderSignal, setCloseFolderSignal] = useState(false);
  const [isDropzoneHovered, setIsDropzoneHovered] = useState(false);
  const [receiptSuccessData, setReceiptSuccessData] = useState<ReceiptSuccessData | null>(null);
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

  const selectedGroup =
    selectedGroupKey != null ? (groups.find((group) => group.groupKey === selectedGroupKey) ?? null) : null;

  const selectedInstallment = useMemo(
    () =>
      selectedAnchorInstallmentId != null
        ? (allInstallments.find((installment) => installment.installmentId === selectedAnchorInstallmentId) ?? null)
        : null,
    [selectedAnchorInstallmentId, allInstallments],
  );

  const selectedInstallmentDisplay = selectedInstallment ? resolveInstallmentDisplay(selectedInstallment) : null;
  const selectedInstallmentRemaining = selectedInstallment
    ? getInstallmentRemainingAmount(selectedInstallment)
    : "0.00";
  const selectedGroupHasPendingReview = selectedGroup != null ? groupHasPendingReview(selectedGroup) : false;
  const selectableInstallments = selectedGroup != null ? getPayableInstallments(selectedGroup) : [];
  const selectedTripHasPending = selectableInstallments.length > 0;
  const tripCurrency = selectedInstallment?.tripCurrency ?? "ARS";
  const oppositeCurrency: Currency = tripCurrency === "ARS" ? "USD" : "ARS";
  const subtotals = receiptSubtotals(receipts);
  const tripSubtotal = subtotals?.[tripCurrency] ?? null;
  const oppositeSubtotal = subtotals?.[oppositeCurrency] ?? null;
  const anchorId = selectedGroupHasPendingReview ? null : selectedInstallment?.installmentId ?? null;
  const auxiliaryPayload = anchorId != null && oppositeSubtotal != null && moneyCents(oppositeSubtotal) != null
    ? { anchorInstallmentId: anchorId, paymentCurrency: oppositeCurrency, reportedPaymentDate,
        intent: "MANUAL" as const, reportedAmount: oppositeSubtotal }
    : null;
  const auxiliaryAnchorId = auxiliaryPayload?.anchorInstallmentId;
  const auxiliaryAmount = auxiliaryPayload?.reportedAmount;
  const auxiliaryDate = auxiliaryPayload?.reportedPaymentDate;
  const auxiliary = usePaymentCalculation(auxiliaryPayload, {
    sourceCurrency: oppositeCurrency, sourceAmount: oppositeSubtotal ?? "", intentRevision: receiptRevision,
  });
  const auxiliaryReady = auxiliaryPayload != null && !auxiliary.isFetching && auxiliary.error == null &&
    auxiliary.dataUpdatedAt > 0 && Date.now() - auxiliary.dataUpdatedAt < 240_000 &&
    auxiliary.data?.status === "READY" && auxiliary.data.anchorInstallmentId === anchorId &&
    auxiliary.data.paymentCurrency === oppositeCurrency && auxiliary.data.tripCurrency === tripCurrency &&
    auxiliary.data.reportedPaymentDate === reportedPaymentDate &&
    moneyCents(auxiliary.data.reportedAmount ?? "") === moneyCents(oppositeSubtotal ?? "")
      ? auxiliary.data : null;
  const convertedCents = auxiliaryReady ? moneyCents(auxiliaryReady.amountInTripCurrency ?? "") : null;
  const tripCents = tripSubtotal != null ? moneyCents(tripSubtotal) ?? 0n : null;
  const total = subtotals != null && tripCents != null &&
    (oppositeSubtotal === "0.00" || convertedCents != null)
    ? centsToMoney(tripCents + (convertedCents ?? 0n)) : null;
  const currentKey = JSON.stringify([anchorId, tripCurrency, reportedPaymentDate, receiptRevision, total]);
  const contextKey = JSON.stringify([anchorId, tripCurrency, reportedPaymentDate, receiptRevision,
    tripSubtotal, oppositeSubtotal]);
  const contextKeyRef = useRef(contextKey);
  contextKeyRef.current = contextKey;
  const isConfirmed = confirmation?.key === currentKey && total != null;
  const finalPayload = isConfirmed && anchorId != null && total != null
    ? { anchorInstallmentId: anchorId, paymentCurrency: tripCurrency, reportedPaymentDate,
        intent: "MANUAL" as const, reportedAmount: total }
    : null;
  const finalCalculation = usePaymentCalculation(finalPayload, {
    sourceCurrency: tripCurrency, sourceAmount: total ?? "", intentRevision: confirmation?.revision ?? 0,
  });
  const readyPaymentCalculation = finalPayload != null && !finalCalculation.isFetching &&
    finalCalculation.error == null && finalCalculation.data?.status === "READY" &&
    finalCalculation.data.previewToken && finalCalculation.data.anchorInstallmentId === anchorId &&
    finalCalculation.data.paymentCurrency === tripCurrency && finalCalculation.data.tripCurrency === tripCurrency &&
    finalCalculation.data.reportedPaymentDate === reportedPaymentDate &&
    moneyCents(finalCalculation.data.reportedAmount ?? "") === moneyCents(total ?? "")
      ? finalCalculation.data : null;
  const calculationStatusMessage = finalCalculation.data && isConfirmed
    ? getCalculationStatusMessage(finalCalculation.data) : auxiliary.data && auxiliaryPayload
      ? getCalculationStatusMessage(auxiliary.data) : null;

  useEffect(() => {
    if (!readyPaymentCalculation) return;
    // Expire locally before the server's 300-second token lifetime; never reuse a cached token.
    const expiresAt = Date.now() + 240_000;
    const timeout = window.setTimeout(() => {
      setFinalExpiresAt(0);
      setConfirmation(null);
    }, 240_000);
    setFinalExpiresAt(expiresAt);
    return () => window.clearTimeout(timeout);
  }, [readyPaymentCalculation]);

  useEffect(() => {
    if (auxiliaryAnchorId == null || auxiliary.dataUpdatedAt === 0) return;
    const remaining = Math.max(0, auxiliary.dataUpdatedAt + 240_000 - Date.now());
    const timeout = window.setTimeout(() => {
      authorityRevision.current += 1;
      setIsVerifyingConversion(false);
      setConfirmation(null);
      setFinalExpiresAt(0);
      setReceiptRevision((revision) => revision + 1);
    }, remaining);
    return () => window.clearTimeout(timeout);
  }, [auxiliaryAnchorId, auxiliaryAmount, auxiliaryDate, auxiliary.dataUpdatedAt]);

  const revokeConfirmation = () => {
    authorityRevision.current += 1;
    setIsVerifyingConversion(false);
    setConfirmation(null);
    setFinalExpiresAt(0);
    setReceiptRevision((revision) => revision + 1);
  };

  const handleConfirmationChange = async (checked: boolean) => {
    const authority = ++authorityRevision.current;
    setConfirmation(null);
    setFinalExpiresAt(0);
    if (!checked || total == null) return;
    const key = currentKey;
    const context = contextKey;
    const confirmedCents = moneyCents(total);
    const revision = ++nextConfirmationRevision.current;
    if (auxiliaryPayload != null) {
      setIsVerifyingConversion(true);
      // A final same-currency calculation cannot validate the FX equivalence.
      // Re-fetch it before granting confirmation, even when the displayed quote is recent.
      try {
        const result = await auxiliary.refetch();
        if (authorityRevision.current !== authority || contextKeyRef.current !== context ||
            result.isError || result.data?.status !== "READY" ||
            result.data.anchorInstallmentId !== auxiliaryPayload.anchorInstallmentId ||
            result.data.paymentCurrency !== oppositeCurrency || result.data.tripCurrency !== tripCurrency ||
            result.data.reportedPaymentDate !== reportedPaymentDate ||
            moneyCents(result.data.reportedAmount ?? "") !== moneyCents(oppositeSubtotal ?? "")) return;
        const newEquivalent = moneyCents(result.data.amountInTripCurrency ?? "");
        const originalCents = tripSubtotal === "0.00" ? 0n : moneyCents(tripSubtotal ?? "");
        if (newEquivalent == null || originalCents == null ||
            confirmedCents !== originalCents + newEquivalent) return;
      } finally {
        if (authorityRevision.current === authority) setIsVerifyingConversion(false);
      }
    }
    if (authorityRevision.current === authority && contextKeyRef.current === context) {
      setConfirmation({ key, revision });
    }
  };

  const availableBankAccounts = useMemo(
    () => bankAccountItems.filter((account) => account.currency === tripCurrency),
    [bankAccountItems, tripCurrency],
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
    isConfirmed &&
    finalExpiresAt > Date.now() &&
    readyPaymentCalculation != null &&
    readyPaymentCalculation.previewToken != null &&
    total != null &&
    !isBankAccountsLoading &&
    availableBankAccounts.length > 0 &&
    selectedBankAccountId != null &&
    fileError == null &&
    receipts.length >= 1 &&
    receipts.length <= 5;

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
      return;
    }

    const group = groups.find((item) => item.groupKey === selectedGroupKey) ?? null;
    if (!group) {
      setSelectedAnchorInstallmentId(null);
      return;
    }

    const pendingInstallment = findPendingInstallment(group);
    if (!pendingInstallment) {
      setSelectedAnchorInstallmentId(null);
      return;
    }

    setSelectedAnchorInstallmentId(pendingInstallment.installmentId);
  }, [selectedGroupKey, groups]);

  const toggleGroup = (groupKey: string) => {
    setExpandedGroupKeys((current) => {
      if (current.includes(groupKey)) {
        return current.filter((item) => item !== groupKey);
      }

      return [...current, groupKey];
    });
  };

  const handleFileChange = (event: React.ChangeEvent<HTMLInputElement>) => {
    const files = Array.from(event.target.files ?? []).slice(0, 5 - receipts.length);
    event.target.value = "";
    if (files.length === 0) return;
    revokeConfirmation();
    const error = files.some((file) =>
      !["image/jpeg", "image/png", "image/webp", "application/pdf"].includes(file.type) ||
      file.size > 5 * 1024 * 1024,
    ) ? "Cada archivo debe ser JPG, PNG, WEBP o PDF y no superar 5 MB." : null;
    setFileError(error);
    if (!error) {
      setReceipts((current) => [...current, ...files.map((file) => ({
        id: ++nextReceiptId.current, file, currency: tripCurrency, amount: "",
      }))]);
      if (receipts.length === 0 && files[0].type.startsWith("image/")) {
        setReceiptPreviewUrl(URL.createObjectURL(files[0]));
      }
    }
    setCloseFolderSignal(true);
    setTimeout(() => setCloseFolderSignal(false), 200);
  };

  const updateReceipt = (id: number, update: Partial<Pick<ReceiptAmount, "amount" | "currency">>) => {
    revokeConfirmation();
    setReceipts((current) => current.map((receipt) => receipt.id === id ? { ...receipt, ...update } : receipt));
  };

  const removeReceipt = (id: number) => {
    revokeConfirmation();
    setReceipts((current) => current.filter((receipt) => receipt.id !== id));
    if (receipts[0]?.id === id) {
      const next = receipts[1]?.file;
      setReceiptPreviewUrl(next?.type.startsWith("image/") ? URL.createObjectURL(next) : null);
    }
    setFileError(null);
  };

  const handleSubmit = async (event: React.FormEvent<HTMLFormElement>) => {
    event.preventDefault();

    if (receipts.length === 0 && !fileError) {
      toast.error("Debés adjuntar al menos un comprobante de pago.");
      return;
    }

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

    if (fileError || receipts.length > 5) {
      toast.error(fileError ?? "Podés adjuntar hasta 5 comprobantes.");
      return;
    }

    if (!canSubmitPayment || !readyPaymentCalculation || readyPaymentCalculation.previewToken == null) {
      toast.error("Todavía no pudimos calcular la imputación del pago. Reintentá en unos segundos.");
      return;
    }

    // Capture display data before resetting state
    const groupIndex = groups.findIndex((g) => g.groupKey === selectedGroupKey);
    const tripDisplayName = selectedGroup ? getGroupDisplayName(selectedGroup, groupIndex) : "";
    const installmentsLabelSnapshot = formatInstallmentsLabel(readyPaymentCalculation.installments);
    const amountSnapshot = formatAmountByCurrency(
      readyPaymentCalculation.paymentCurrency,
      readyPaymentCalculation.reportedAmount ?? total ?? "0",
    );
    const dateSnapshot = formatReportedDate(reportedPaymentDate);
    const methodSnapshot = paymentMethod;
    const bankSnapshot =
      availableBankAccounts.find((a) => a.id === selectedBankAccountId)?.accountLabel ??
      availableBankAccounts.find((a) => a.id === selectedBankAccountId)?.bankName ??
      "";
    const fileNameSnapshot = receipts.map(({ file }) => file.name).join(", ") || "Sin archivos";

    try {
      await registerPayment.mutateAsync({
        anchorInstallmentId: selectedAnchorInstallmentId,
        reportedAmount: total!,
        reportedPaymentDate,
        paymentCurrency: tripCurrency,
        paymentMethod,
        bankAccountId: selectedBankAccountId,
        files: receipts.map(({ file }) => file),
        previewToken: readyPaymentCalculation.previewToken,
      });

      setSelectedAnchorInstallmentId(null);
      setReportedPaymentDate(getTodayDate());
      setPaymentMethod("BANK_TRANSFER");
      setSelectedBankAccountId(null);
      setReceipts([]);
      revokeConfirmation();
      setFileError(null);
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
        <ReceiptSuccessScreen data={receiptSuccessData} onBack={() => setReceiptSuccessData(null)} />
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
                            Estado de cuenta: Pagado {formatAmountByCurrency(accountCurrency, totalPaid)} · Resta{" "}
                            {formatAmountByCurrency(accountCurrency, totalRemaining)}
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
                                    <span className={`${styles.statusBadge} ${statusClass}`}>{display.label}</span>
                                  </div>

                                  <p className={styles.chipMeta}>{currencyFormatter.format(installment.totalDue)}</p>
                                  {installment.paidAmount > 0 && installment.uiStatusCode !== "PAID" ? (
                                    <p className={styles.chipMeta}>
                                      Abonado: {formatInstallmentAmount(installment, installment.paidAmount)} · Resta:{" "}
                                      {formatInstallmentAmount(installment, getInstallmentRemainingAmount(installment))}
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
                              Pagado: {formatAmountByCurrency(accountCurrency, totalPaid)} · Resta:{" "}
                              {formatAmountByCurrency(accountCurrency, totalRemaining)}
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
                    <div>
                      Moneda: <strong>{currency === "USD" ? "Dólares (USD)" : "Pesos (ARS)"}</strong>
                    </div>
                    <div>
                      Titular: <strong>{account.accountHolder}</strong>
                    </div>
                    <div>
                      Cuenta: <strong>{account.accountNumber}</strong>
                    </div>
                    <div>
                      CUIT: <strong>{account.taxId}</strong>
                    </div>
                    <div>
                      CBU: <strong>{account.cbu}</strong>
                    </div>
                    <div>
                      Alias: <strong>{account.alias}</strong>
                    </div>
                  </div>
                ));
              })}
            </div>

            <form className={styles.form} onSubmit={handleSubmit}>
              <label className={styles.formField}>
                <span className={styles.label}>Seleccioná el viaje</span>
                <select
                  value={selectedGroupKey ?? ""}
                  onChange={(event) => {
                    revokeConfirmation();
                    setSelectedGroupKey(event.target.value === "" ? null : event.target.value);
                  }}
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
                  Esta inscripción tiene comprobantes pendientes de revisión. Hasta que el administrador los revise no
                  podés enviar un nuevo pago.
                </p>
              ) : null}

              {selectedInstallment ? (
                <div className={styles.selectedInfoBox}>
                  <div className={styles.selectedInfoHeader}>
                    <span>
                      {selectedGroup?.studentName ? `${selectedGroup.studentName} · ` : ""}
                      Primera cuota pendiente #{selectedInstallment.installmentNumber} · vence{" "}
                      {formatReportedDate(selectedInstallment.dueDate)} · saldo{" "}
                      {formatInstallmentAmount(selectedInstallment, selectedInstallmentRemaining)}
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
                style={{ cursor: receipts.length === 5 ? "not-allowed" : "pointer" }}
                onMouseEnter={() => setIsDropzoneHovered(true)}
                onMouseLeave={() => setIsDropzoneHovered(false)}
              >
                <input
                  ref={fileInputRef}
                  type="file"
                  accept="image/jpeg,image/png,image/webp,application/pdf"
                  multiple
                  disabled={receipts.length === 5}
                  className={styles.fileInput}
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
                  {receipts.length === 5
                    ? "Máximo de 5 comprobantes alcanzado"
                    : receipts.length
                    ? `${receipts.length} de 5 archivos seleccionados · agregar más`
                    : "Adjuntar comprobantes (hasta 5)"}
                </p>
              </label>
              {receipts.length > 0 ? (
                <ul className={styles.receiptList}>
                  {receipts.map((receipt) => (
                    <li key={receipt.id} className={styles.receiptItem}>
                      <strong>{receipt.file.name}</strong>
                      <label className={styles.formField}>
                        <span className={styles.label}>Moneda de {receipt.file.name}</span>
                        <select className={styles.select} value={receipt.currency}
                          onChange={(event) => updateReceipt(receipt.id, { currency: event.target.value as Currency })}>
                          <option value="ARS">Pesos (ARS)</option>
                          <option value="USD">Dólares (USD)</option>
                        </select>
                      </label>
                      <label className={styles.formField}>
                        <span className={styles.label}>Monto de {receipt.file.name}</span>
                        <input className={styles.input} type="text" inputMode="decimal" value={receipt.amount}
                          aria-invalid={receipt.amount !== "" && moneyCents(receipt.amount) == null}
                          onChange={(event) => updateReceipt(receipt.id, { amount: event.target.value })} />
                      </label>
                      {receipt.amount !== "" && moneyCents(receipt.amount) == null ? (
                        <span className={styles.errorText}>Ingresá un monto positivo con hasta dos decimales.</span>
                      ) : null}
                      <button type="button" className={styles.removeReceipt} onClick={() => removeReceipt(receipt.id)}
                        aria-label={`Quitar ${receipt.file.name}`}>Quitar</button>
                    </li>
                  ))}
                </ul>
              ) : null}
              {fileError ? (
                <p className={styles.errorText} role="alert">
                  {fileError}
                </p>
              ) : null}

              {subtotals ? (
                <div className={styles.selectedInfoBox}>
                  <p>Subtotal ARS: {subtotals.ARS} · Subtotal USD: {subtotals.USD}</p>
                  {oppositeSubtotal !== "0.00" && auxiliaryReady && convertedCents != null ? (
                    <p>Equivalente en {tripCurrency} calculado por el servidor: {auxiliaryReady.amountInTripCurrency}</p>
                  ) : null}
                  <strong>Total en {tripCurrency}: {total ?? "Pendiente de cálculo"}</strong>
                </div>
              ) : receipts.length > 0 ? (
                <p className={styles.helperWarning}>Ingresá un monto válido para cada comprobante.</p>
              ) : null}
              {total != null && !selectedGroupHasPendingReview ? (
                <label
                  className={`${styles.confirmation}${isConfirmed ? ` ${styles.confirmationActive}` : ""}`}
                >
                  <input
                    type="checkbox"
                    className={styles.confirmationCheckbox}
                    checked={isConfirmed}
                    disabled={isVerifyingConversion}
                    onChange={(event) => { void handleConfirmationChange(event.target.checked); }}
                  />
                  <span className={styles.confirmationText}>
                    Confirmo el total de {total} {tripCurrency} para estos comprobantes.
                  </span>
                </label>
              ) : null}
              {isVerifyingConversion ? (
                <p className={styles.helperText}>Verificando la cotización antes de confirmar el total...</p>
              ) : null}
              {readyPaymentCalculation ? (
                <div className={styles.selectedInfoBox}>
                  <div className={styles.selectedInfoHeader}>
                    <span>
                      Se imputa en {formatInstallmentsLabel(readyPaymentCalculation.installments)} · monto reportado{" "}
                      {formatAmountByCurrency(
                        readyPaymentCalculation.paymentCurrency,
                        readyPaymentCalculation.reportedAmount ?? total ?? "0",
                      )}
                    </span>
                    <span className={`${styles.statusBadge} ${styles.statusgreen}`}>Monto libre</span>
                  </div>
                  {readyPaymentCalculation.maxAllowedAmount != null ? (
                    <p className={styles.helperText}>
                      Máximo permitido para esta inscripción:{" "}
                      {formatAmountByCurrency(
                        readyPaymentCalculation.paymentCurrency,
                        readyPaymentCalculation.maxAllowedAmount,
                      )}
                      .
                    </p>
                  ) : null}
                  <p className={styles.helperText}>
                    Saldo pendiente total:{" "}
                    {formatAmountByCurrency(
                      readyPaymentCalculation.tripCurrency,
                      readyPaymentCalculation.totalPendingAmountInTripCurrency,
                    )}
                    . Equivale a{" "}
                    {formatAmountByCurrency(
                      readyPaymentCalculation.tripCurrency,
                      readyPaymentCalculation.amountInTripCurrency ?? "0",
                    )}{" "}
                    del viaje
                    {readyPaymentCalculation.exchangeRate != null
                      ? ` · cotización oficial ${formatAmountByCurrency("ARS", readyPaymentCalculation.exchangeRate)}${readyPaymentCalculation.quoteEffectiveDate ? ` correspondiente al ${formatReportedDate(readyPaymentCalculation.quoteEffectiveDate)}` : ""}`
                      : ""}
                  </p>
                </div>
              ) : null}
              {calculationStatusMessage ? (
                <p className={styles.errorText} role="alert">
                  {calculationStatusMessage}
                </p>
              ) : null}
              {auxiliary.error || (isConfirmed && finalCalculation.error) ? (
                <p className={styles.errorText} role="alert">
                  No pudimos calcular el monto. Revisá los comprobantes o reintentá.
                </p>
              ) : null}
              {(auxiliary.isFetching || finalCalculation.isFetching) ? (
                <p className={styles.helperText}>Calculando el monto con el servidor...</p>
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
                  onChange={(event) => {
                    const nextDate = event.target.value;
                    revokeConfirmation();
                    setReportedPaymentDate(nextDate);
                  }}
                  className={styles.input}
                  disabled={!selectedTripHasPending || selectedGroupHasPendingReview}
                  required
                />
              </label>

              {oppositeSubtotal != null && oppositeSubtotal !== "0.00" ? (
                <p className={styles.helperWarning}>
                  Se usará la cotización oficial correspondiente a la fecha informada. Si ese día no tiene cotización,
                  se usará la última disponible anterior.
                </p>
              ) : null}

              <label className={styles.formField}>
                <span className={styles.label}>Cuenta donde acreditaste el pago</span>
                <select
                  value={selectedBankAccountId ?? ""}
                  onChange={(event) =>
                    setSelectedBankAccountId(event.target.value === "" ? null : Number(event.target.value))
                  }
                  className={styles.select}
                  disabled={
                    !selectedTripHasPending ||
                    selectedGroupHasPendingReview ||
                    isBankAccountsLoading ||
                    availableBankAccounts.length === 0
                  }
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
                <p className={styles.helperWarning}>No hay cuentas activas disponibles para la moneda seleccionada.</p>
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
