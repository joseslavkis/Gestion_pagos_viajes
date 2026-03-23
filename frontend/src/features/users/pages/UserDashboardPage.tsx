import { useEffect, useMemo, useRef, useState, type CSSProperties } from "react";

import { useQueryClient } from "@tanstack/react-query";

import { SchoolAutocomplete } from "@/components/form-components/SchoolAutocomplete/SchoolAutocomplete";
import { CommonLayout } from "@/components/CommonLayout/CommonLayout";
import { useBankAccounts } from "@/features/bank-accounts/services/bank-accounts-service";
import type { BankAccountDTO } from "@/features/bank-accounts/types/bank-accounts-dtos";
import type { StudentCreateDTO } from "@/features/auth/types/auth-dtos";
import { StudentCreateSchema } from "@/features/auth/types/auth-dtos";
import { Folder } from "@/features/payments/components/Folder";
import {
  useMyInstallments,
  useRegisterPayment,
} from "@/features/payments/services/payments-service";
import type {
  PaymentMethod,
  UserInstallmentDTO,
} from "@/features/payments/types/payments-dtos";
import {
  useAddStudent,
  useDeleteStudent,
  useStudents,
} from "@/features/users/services/users-service";

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

const studentCardStyle: CSSProperties = {
  border: "1px solid #dbe5f0",
  borderRadius: 16,
  padding: "1rem",
  background: "#fff",
  display: "grid",
  gap: "0.45rem",
};

const studentActionsStyle: CSSProperties = {
  display: "flex",
  gap: 8,
  flexWrap: "wrap",
};

type InstallmentGroup = {
  groupKey: string;
  tripId: number;
  studentId: number | null;
  studentName: string | null;
  studentDni: string | null;
  schoolName: string | null;
  courseName: string | null;
  installments: UserInstallmentDTO[];
};

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
      studentId: installment.studentId,
      studentName: installment.studentName,
      studentDni: installment.studentDni,
      schoolName: installment.schoolName,
      courseName: installment.courseName,
      installments: [installment],
    });
  }

  return Array.from(map.values()).map((group) => ({
    ...group,
    installments: [...group.installments].sort((a, b) => a.installmentNumber - b.installmentNumber),
  }));
}

function getGroupBadgeColor(group: InstallmentGroup): "green" | "yellow" | "red" {
  const colors = group.installments.map((installment) => installment.uiStatusTone);
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
    (installment) =>
      installment.uiStatusCode !== "PAID" &&
      !(installment.uiStatusCode === "UP_TO_DATE" && installment.paidAmount >= installment.totalDue),
  );
  if (pending.length === 0) {
    return group.installments[group.installments.length - 1]?.dueDate ?? null;
  }
  return [...pending].sort((a, b) => a.dueDate.localeCompare(b.dueDate))[0]?.dueDate ?? null;
}

function findPendingInstallment(group: InstallmentGroup): UserInstallmentDTO | null {
  const pending = group.installments
    .filter(
      (installment) =>
        installment.uiStatusCode !== "PAID" &&
        installment.uiStatusCode !== "UNDER_REVIEW",
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

function getGroupDisplayName(group: InstallmentGroup, index: number): string {
  return group.studentName ? `Viaje ${index + 1} - ${group.studentName}` : `Viaje ${index + 1}`;
}

const emptyStudent = (): StudentCreateDTO => ({
  name: "",
  dni: "",
  schoolName: "",
  courseName: "",
});

export function UserDashboardPage() {
  const queryClient = useQueryClient();
  const registerPayment = useRegisterPayment();
  const addStudent = useAddStudent();
  const deleteStudent = useDeleteStudent();
  const { data: installments, isLoading, error } = useMyInstallments();
  const {
    data: bankAccounts,
    isLoading: isBankAccountsLoading,
    error: bankAccountsError,
  } = useBankAccounts();
  const {
    data: students,
    isLoading: isStudentsLoading,
    error: studentsError,
  } = useStudents();

  const [expandedGroupKeys, setExpandedGroupKeys] = useState<string[]>([]);
  const [selectedGroupKey, setSelectedGroupKey] = useState<string | null>(null);
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
  const [newStudent, setNewStudent] = useState<StudentCreateDTO>(emptyStudent());
  const [studentFormError, setStudentFormError] = useState<string | null>(null);
  const [studentSuccessMessage, setStudentSuccessMessage] = useState<string | null>(null);
  const [deletingStudentId, setDeletingStudentId] = useState<number | null>(null);
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
    if (!studentSuccessMessage) {
      return;
    }

    const timeoutId = window.setTimeout(() => {
      setStudentSuccessMessage(null);
    }, 4000);

    return () => window.clearTimeout(timeoutId);
  }, [studentSuccessMessage]);

  useEffect(() => {
    return () => {
      if (receiptPreviewUrl) {
        URL.revokeObjectURL(receiptPreviewUrl);
      }
    };
  }, [receiptPreviewUrl]);

  const installmentItems = useMemo(() => installments ?? [], [installments]);
  const bankAccountItems = useMemo(() => bankAccounts ?? [], [bankAccounts]);
  const studentItems = useMemo(() => students ?? [], [students]);
  const groups = useMemo(() => buildInstallmentGroups(installmentItems), [installmentItems]);
  const completedGroups = useMemo(
    () => groups.filter((group) => group.installments.every((installment) => installment.userCompletedTrip)),
    [groups],
  );
  const allInstallments = useMemo(() => groups.flatMap((group) => group.installments), [groups]);

  const selectedGroup = selectedGroupKey != null
    ? groups.find((group) => group.groupKey === selectedGroupKey) ?? null
    : null;

  const selectedInstallment = useMemo(
    () =>
      selectedInstallmentId != null
        ? allInstallments.find((installment) => installment.installmentId === selectedInstallmentId) ?? null
        : null,
    [selectedInstallmentId, allInstallments],
  );

  const selectedInstallmentDisplay = selectedInstallment ? resolveInstallmentDisplay(selectedInstallment) : null;
  const selectedInstallmentRemaining = selectedInstallment ? getInstallmentRemainingAmount(selectedInstallment) : 0;

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
      setSelectedInstallmentId(null);
      setReportedAmount("");
      return;
    }

    const group = groups.find((item) => item.groupKey === selectedGroupKey) ?? null;
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
    setSubmitError(null);
    setSuccessMessage(null);

    if (selectedInstallmentId == null) {
      setSubmitError("Seleccioná una inscripción con cuotas pendientes antes de enviar el comprobante.");
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
    } catch (currentError) {
      setSubmitError(
        currentError instanceof Error
          ? currentError.message
          : "No se pudo enviar el comprobante. Intentá nuevamente.",
      );
    }
  };

  const handleAddStudent = async (event: React.FormEvent<HTMLFormElement>) => {
    event.preventDefault();
    setStudentFormError(null);
    setStudentSuccessMessage(null);

    const parsed = StudentCreateSchema.safeParse(newStudent);
    if (!parsed.success) {
      setStudentFormError(parsed.error.issues[0]?.message ?? "Revisá los datos del alumno.");
      return;
    }

    try {
      await addStudent.mutateAsync(parsed.data);
      setNewStudent(emptyStudent());
      setStudentSuccessMessage("Alumno agregado correctamente.");
    } catch (currentError) {
      setStudentFormError(
        currentError instanceof Error
          ? currentError.message
          : "No se pudo agregar el alumno.",
      );
    }
  };

  const handleDeleteStudent = async (studentId: number) => {
    setStudentFormError(null);
    setStudentSuccessMessage(null);

    try {
      await deleteStudent.mutateAsync(studentId);
      setDeletingStudentId(null);
      setStudentSuccessMessage("Alumno eliminado correctamente.");
    } catch (currentError) {
      setStudentFormError(
        currentError instanceof Error
          ? currentError.message
          : "No se pudo eliminar el alumno.",
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
                  const isExpanded = expandedGroupKeys.includes(group.groupKey);
                  const nextDueDate = findNextDueDate(group);
                  const groupColor = getGroupBadgeColor(group);

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
              <p className={styles.helperText}>Todavía no tenés viajes completados.</p>
            ) : (
              <div className={styles.historyList}>
                {completedGroups.map((group, index) => (
                  <div key={group.groupKey} className={styles.historyItem}>
                    <span className={styles.historyTripName}>{getGroupDisplayName(group, index)}</span>
                    <span className={styles.historyBadge}>✓ Pagado</span>
                  </div>
                ))}
              </div>
            )}
          </section>

          <section className={styles.section}>
            <h2 className={styles.sectionTitle}>Mis hijos</h2>
            <p className={styles.helperText}>Podés agregar más hijos para que el administrador los asigne a viajes.</p>

            {isStudentsLoading ? <p className={styles.helperText}>Cargando alumnos...</p> : null}
            {studentsError ? <p className={styles.errorText}>{studentsError.message}</p> : null}

            {!isStudentsLoading && !studentsError && studentItems.length === 0 ? (
              <p className={styles.helperText}>Todavía no registraste hijos en tu cuenta.</p>
            ) : null}

            <div style={{ display: "grid", gap: "0.85rem", marginBottom: "1rem" }}>
              {studentItems.map((student) => (
                <div key={student.id} style={studentCardStyle}>
                  <div style={{ display: "flex", justifyContent: "space-between", gap: 12, alignItems: "center", flexWrap: "wrap" }}>
                    <strong>{student.name}</strong>
                    {deletingStudentId === student.id ? (
                      <div style={studentActionsStyle}>
                        <button
                          type="button"
                          className={styles.submitButton}
                          onClick={() => handleDeleteStudent(student.id)}
                          disabled={deleteStudent.isPending}
                        >
                          Confirmar
                        </button>
                        <button
                          type="button"
                          className={styles.select}
                          onClick={() => setDeletingStudentId(null)}
                          disabled={deleteStudent.isPending}
                        >
                          Cancelar
                        </button>
                      </div>
                    ) : (
                      <button
                        type="button"
                        className={styles.select}
                        onClick={() => setDeletingStudentId(student.id)}
                        disabled={deleteStudent.isPending}
                      >
                        ✕
                      </button>
                    )}
                  </div>
                  <div>DNI: <strong>{student.dni}</strong></div>
                  {student.schoolName ? <div>Colegio: <strong>{student.schoolName}</strong></div> : null}
                  {student.courseName ? <div>Curso: <strong>{student.courseName}</strong></div> : null}
                  {deletingStudentId === student.id ? (
                    <p className={styles.helperWarning}>
                      ¿Eliminar a {student.name}? Esta acción no se puede deshacer si no tiene cuotas.
                    </p>
                  ) : null}
                </div>
              ))}
            </div>

            <form className={styles.form} onSubmit={handleAddStudent}>
              <label className={styles.formField}>
                <span className={styles.label}>Nombre completo</span>
                <input
                  className={styles.input}
                  value={newStudent.name}
                  onChange={(event) => setNewStudent((current) => ({ ...current, name: event.target.value }))}
                />
              </label>
              <label className={styles.formField}>
                <span className={styles.label}>DNI</span>
                <input
                  className={styles.input}
                  value={newStudent.dni}
                  onChange={(event) => setNewStudent((current) => ({ ...current, dni: event.target.value }))}
                />
              </label>
              <div className={styles.formField}>
                <SchoolAutocomplete
                  label="Colegio"
                  value={newStudent.schoolName ?? ""}
                  onChange={(value) => setNewStudent((current) => ({ ...current, schoolName: value }))}
                />
              </div>
              <label className={styles.formField}>
                <span className={styles.label}>Curso</span>
                <input
                  className={styles.input}
                  value={newStudent.courseName ?? ""}
                  onChange={(event) => setNewStudent((current) => ({ ...current, courseName: event.target.value }))}
                />
              </label>

              <button type="submit" className={styles.submitButton} disabled={addStudent.isPending}>
                {addStudent.isPending ? "Agregando..." : "Agregar hijo"}
              </button>

              {studentFormError ? <p className={styles.errorText}>{studentFormError}</p> : null}
              {studentSuccessMessage ? <p className={styles.successText}>{studentSuccessMessage}</p> : null}
            </form>
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

              {selectedInstallment ? (
                <div className={styles.selectedInfoBox}>
                  <div className={styles.selectedInfoHeader}>
                    <span>
                      {selectedGroup?.studentName ? `${selectedGroup.studentName} · ` : ""}
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
                  {receiptFile ? receiptFile.name : "Hacé click para adjuntar el comprobante (opcional)"}
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
                  Saldo restante sugerido: {formatInstallmentAmount(selectedInstallment, selectedInstallmentRemaining)} · Si pagás más, el excedente se aplica en cascada a cuotas siguientes.
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

              <button type="submit" className={styles.submitButton} disabled={!canSubmitPayment}>
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
