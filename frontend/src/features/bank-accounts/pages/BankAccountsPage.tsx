import { useMemo, useState } from "react";

import { CommonLayout } from "@/components/CommonLayout/CommonLayout";
import { RequestState } from "@/components/ui/RequestState/RequestState";
import {
  useAdminBankAccounts,
  useCreateBankAccount,
  useUpdateBankAccount,
  useUpdateBankAccountActive,
} from "@/features/bank-accounts/services/bank-accounts-service";
import type { BankAccountDTO, BankAccountFormDTO } from "@/features/bank-accounts/types/bank-accounts-dtos";

import styles from "./BankAccountsPage.module.css";

type FormState = {
  bankName: string;
  accountLabel: string;
  accountHolder: string;
  accountNumber: string;
  taxId: string;
  cbu: string;
  alias: string;
  currency: "ARS" | "USD";
  displayOrder: string;
};

const emptyForm: FormState = {
  bankName: "",
  accountLabel: "",
  accountHolder: "",
  accountNumber: "",
  taxId: "",
  cbu: "",
  alias: "",
  currency: "ARS",
  displayOrder: "0",
};

function toForm(account: BankAccountDTO): FormState {
  return {
    bankName: account.bankName,
    accountLabel: account.accountLabel,
    accountHolder: account.accountHolder,
    accountNumber: account.accountNumber,
    taxId: account.taxId,
    cbu: account.cbu,
    alias: account.alias,
    currency: account.currency,
    displayOrder: String(account.displayOrder),
  };
}

function toPayload(form: FormState): BankAccountFormDTO {
  return {
    bankName: form.bankName.trim(),
    accountLabel: form.accountLabel.trim(),
    accountHolder: form.accountHolder.trim(),
    accountNumber: form.accountNumber.trim(),
    taxId: form.taxId.trim(),
    cbu: form.cbu.trim(),
    alias: form.alias.trim(),
    currency: form.currency,
    displayOrder: Number(form.displayOrder || 0),
  };
}

export function BankAccountsPage() {
  const { data, isLoading, error } = useAdminBankAccounts();
  const createBankAccount = useCreateBankAccount();
  const updateBankAccount = useUpdateBankAccount();
  const updateBankAccountActive = useUpdateBankAccountActive();

  const [editingId, setEditingId] = useState<number | null>(null);
  const [form, setForm] = useState<FormState>(emptyForm);
  const [formError, setFormError] = useState<string | null>(null);

  const accounts = useMemo(() => data ?? [], [data]);
  const isSubmitting = createBankAccount.isPending || updateBankAccount.isPending;

  const resetForm = () => {
    setEditingId(null);
    setForm(emptyForm);
    setFormError(null);
  };

  const handleEdit = (account: BankAccountDTO) => {
    setEditingId(account.id);
    setForm(toForm(account));
    setFormError(null);
  };

  const handleSubmit = async (event: React.FormEvent<HTMLFormElement>) => {
    event.preventDefault();
    setFormError(null);

    const payload = toPayload(form);
    const hasEmptyField = Object.values(payload).some((value) =>
      typeof value === "string" ? value.trim().length === 0 : false,
    );

    if (hasEmptyField) {
      setFormError("Completa todos los campos de la cuenta bancaria.");
      return;
    }

    try {
      if (editingId == null) {
        await createBankAccount.mutateAsync(payload);
      } else {
        await updateBankAccount.mutateAsync({ id: editingId, data: payload });
      }
      resetForm();
    } catch (submissionError) {
      setFormError(
        submissionError instanceof Error
          ? submissionError.message
          : "No se pudo guardar la cuenta bancaria.",
      );
    }
  };

  return (
    <CommonLayout>
      <section className={styles.page}>
        <div className={styles.container}>
          <header className={styles.header}>
            <div>
              <h1 className={styles.title}>Cuentas bancarias</h1>
              <p className={styles.subtitle}>Administra las cuentas que verá el usuario al reportar sus pagos.</p>
            </div>
            {editingId != null ? (
              <button type="button" className={styles.secondaryButton} onClick={resetForm}>
                Cancelar edición
              </button>
            ) : null}
          </header>

          <section className={styles.formCard}>
            <h2 className={styles.sectionTitle}>{editingId == null ? "Nueva cuenta" : "Editar cuenta"}</h2>
            <form className={styles.formGrid} onSubmit={handleSubmit}>
              <label className={styles.field}>
                <span>Banco</span>
                <input value={form.bankName} onChange={(event) => setForm((current) => ({ ...current, bankName: event.target.value }))} />
              </label>
              <label className={styles.field}>
                <span>Etiqueta</span>
                <input value={form.accountLabel} onChange={(event) => setForm((current) => ({ ...current, accountLabel: event.target.value }))} />
              </label>
              <label className={styles.field}>
                <span>Titular</span>
                <input value={form.accountHolder} onChange={(event) => setForm((current) => ({ ...current, accountHolder: event.target.value }))} />
              </label>
              <label className={styles.field}>
                <span>Número de cuenta</span>
                <input value={form.accountNumber} onChange={(event) => setForm((current) => ({ ...current, accountNumber: event.target.value }))} />
              </label>
              <label className={styles.field}>
                <span>CUIT</span>
                <input value={form.taxId} onChange={(event) => setForm((current) => ({ ...current, taxId: event.target.value }))} />
              </label>
              <label className={styles.field}>
                <span>CBU</span>
                <input value={form.cbu} onChange={(event) => setForm((current) => ({ ...current, cbu: event.target.value }))} />
              </label>
              <label className={styles.field}>
                <span>Alias</span>
                <input value={form.alias} onChange={(event) => setForm((current) => ({ ...current, alias: event.target.value }))} />
              </label>
              <label className={styles.field}>
                <span>Moneda</span>
                <select value={form.currency} onChange={(event) => setForm((current) => ({ ...current, currency: event.target.value as "ARS" | "USD" }))}>
                  <option value="ARS">Pesos (ARS)</option>
                  <option value="USD">Dólares (USD)</option>
                </select>
              </label>
              <label className={styles.field}>
                <span>Orden</span>
                <input type="number" min="0" value={form.displayOrder} onChange={(event) => setForm((current) => ({ ...current, displayOrder: event.target.value }))} />
              </label>
              <div className={styles.formActions}>
                <button type="submit" className={styles.primaryButton} disabled={isSubmitting}>
                  {isSubmitting ? "Guardando..." : editingId == null ? "Crear cuenta" : "Guardar cambios"}
                </button>
              </div>
              {formError ? <p className={styles.errorText}>{formError}</p> : null}
            </form>
          </section>

          <section className={styles.listCard}>
            <h2 className={styles.sectionTitle}>Listado</h2>
            <RequestState isLoading={isLoading} error={error ?? null} loadingLabel="Cargando cuentas bancarias...">
              {accounts.length === 0 ? <p className={styles.emptyText}>Todavía no hay cuentas bancarias configuradas.</p> : null}
              <div className={styles.list}>
                {accounts.map((account) => (
                  <article key={account.id} className={styles.accountCard}>
                    <div className={styles.accountHeader}>
                      <div>
                        <h3 className={styles.accountTitle}>{account.bankName} · {account.accountLabel}</h3>
                        <p className={styles.accountMeta}>{account.currency === "USD" ? "USD" : "ARS"} · orden {account.displayOrder}</p>
                      </div>
                      <span className={`${styles.statusBadge} ${account.active ? styles.active : styles.inactive}`}>
                        {account.active ? "Activa" : "Inactiva"}
                      </span>
                    </div>
                    <div className={styles.accountBody}>
                      <div>Titular: <strong>{account.accountHolder}</strong></div>
                      <div>Cuenta: <strong>{account.accountNumber}</strong></div>
                      <div>CUIT: <strong>{account.taxId}</strong></div>
                      <div>CBU: <strong>{account.cbu}</strong></div>
                      <div>Alias: <strong>{account.alias}</strong></div>
                    </div>
                    <div className={styles.accountActions}>
                      <button type="button" className={styles.secondaryButton} onClick={() => handleEdit(account)}>
                        Editar
                      </button>
                      <button
                        type="button"
                        className={styles.secondaryButton}
                        disabled={updateBankAccountActive.isPending}
                        onClick={() => updateBankAccountActive.mutate({ id: account.id, active: !account.active })}
                      >
                        {account.active ? "Desactivar" : "Activar"}
                      </button>
                    </div>
                  </article>
                ))}
              </div>
            </RequestState>
          </section>
        </div>
      </section>
    </CommonLayout>
  );
}
