import React from "react";

import { ErrorContainer } from "@/components/form-components/ErrorContainer/ErrorContainer";
import { SubmitButton } from "@/components/form-components/SubmitButton/SubmitButton";
import { useFormContext } from "@/config/form-context";

import styles from "./FormContainer.module.css";

type FormContainerProps = React.PropsWithChildren<{
  extraError: Error | null;
  submitLabel?: string;
  pendingLabel?: string;
  isPending?: boolean;
  footer?: React.ReactNode;
}>;

export const FormContainer = ({
  extraError,
  submitLabel,
  pendingLabel,
  isPending,
  footer,
  children,
}: FormContainerProps) => {
  const form = useFormContext();

  return (
    <form
      className={styles.form}
      onSubmit={(e) => {
        e.stopPropagation();
        e.preventDefault();
        form.handleSubmit();
      }}
    >
      <div className={styles.fields}>{children}</div>
      {extraError && <ErrorContainer errors={[extraError]} />}
      <SubmitButton label={submitLabel} pendingLabel={pendingLabel} isPending={isPending} />
      {footer ? <div className={styles.footer}>{footer}</div> : null}
    </form>
  );
};
