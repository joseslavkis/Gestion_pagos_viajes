import { useFormContext } from "@/config/form-context";

import styles from "./SubmitButton.module.css";

type SubmitButtonProps = {
  label?: string;
  pendingLabel?: string;
  isPending?: boolean;
};

export const SubmitButton = ({
  label = "Continuar",
  pendingLabel = "Procesando...",
  isPending = false,
}: SubmitButtonProps) => {
  const form = useFormContext();

  return (
    <form.Subscribe
      selector={(state) => [state.canSubmit, state.isSubmitting]}
      children={([canSubmit, isSubmitting]) => (
        <button type="submit" className={styles.button} disabled={!canSubmit || isSubmitting || isPending}>
          {isSubmitting || isPending ? (
            <span className={styles.pendingContent}>
              <span className={styles.spinner} aria-hidden="true" />
              {pendingLabel}
            </span>
          ) : (
            label
          )}
        </button>
      )}
    />
  );
};
