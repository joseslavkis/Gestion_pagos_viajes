import { useState } from "react";

import { useManualImputationContext } from "@/features/payments/services/manual-imputation-service";
import { ManualImputationForm } from "./ManualImputationForm";
import styles from "./ManualImputation.module.css";

type Props = {
  installmentId: number;
  onImputed: () => void;
};

export function ManualImputationSection({ installmentId, onImputed }: Props) {
  const [open, setOpen] = useState(false);
  const contextQuery = useManualImputationContext(open ? null : installmentId);

  // Cuando el formulario está cerrado, precargamos el contexto para decidir
  // el estado del trigger (disabled + disclaimer si hay PENDING).
  // Cuando se abre, el formulario vuelve a consultar su propio contexto.
  const context = contextQuery.data;

  if (contextQuery.isLoading) {
    return (
      <section className={styles.section} aria-label="Imputación manual">
        <button type="button" className={styles.triggerButton} disabled>
          Imputar pago
        </button>
        <p className={styles.calculating}>Verificando elegibilidad…</p>
      </section>
    );
  }

  if (context?.hasPendingReview) {
    return (
      <section className={styles.section} aria-label="Imputación manual">
        <button type="button" className={styles.triggerButton} disabled aria-disabled="true">
          Imputar pago
        </button>
        <p className={styles.blockedMessage} role="alert">
          Hay pagos pendientes de aprobación. Revisalos antes de realizar una nueva imputación.
        </p>
      </section>
    );
  }

  if (!open) {
    return (
      <section className={styles.section} aria-label="Imputación manual">
        <button
          type="button"
          className={styles.triggerButton}
          onClick={() => setOpen(true)}
        >
          Imputar pago
        </button>
        {context && !context.eligible && context.message ? (
          <p className={styles.blockedMessage} role="alert">{context.message}</p>
        ) : null}
        {contextQuery.error ? (
          <p className={styles.error} role="alert">{contextQuery.error.message}</p>
        ) : null}
      </section>
    );
  }

  return (
    <section className={styles.section} aria-label="Imputación manual">
      <button
        type="button"
        className={styles.triggerButton}
        onClick={() => setOpen(false)}
      >
        Ocultar imputación
      </button>
      <ManualImputationForm
        installmentId={installmentId}
        onSuccess={() => {
          onImputed();
        }}
      />
    </section>
  );
}
