import { ReactNode } from "react";

import { ApiError } from "@/lib/api-error";
import { ErrorContainer } from "@/components/form-components/ErrorContainer/ErrorContainer";

import styles from "./RequestState.module.css";

type RequestStateProps = {
  isLoading?: boolean;
  error?: ApiError | null;
  loadingLabel?: string;
  children: ReactNode;
};

export function RequestState({ isLoading = false, error = null, loadingLabel = "Cargando...", children }: RequestStateProps) {
  return (
    <div className={styles.container}>
      {error ? <ErrorContainer errors={[error]} /> : null}
      {isLoading ? (
        <div className={styles.loading} role="status" aria-live="polite">
          {loadingLabel}
        </div>
      ) : null}
      {children}
    </div>
  );
}

