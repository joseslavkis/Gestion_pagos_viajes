import { useId } from "react";

import { ErrorContainer } from "@/components/form-components/ErrorContainer/ErrorContainer";
import { useFieldContext } from "@/config/form-context";

import styles from "./InputFields.module.css";

type FieldProps = {
  label: string;
  placeholder?: string;
  autoComplete?: string;
};

type NumberFieldProps = FieldProps & {
  min?: number;
  max?: number;
  step?: number;
};

export const TextField = ({ label, placeholder, autoComplete }: FieldProps) => {
  return <FieldWithType type="text" label={label} placeholder={placeholder} autoComplete={autoComplete} />;
};

export const PasswordField = ({ label, placeholder, autoComplete }: FieldProps) => {
  return <FieldWithType type="password" label={label} placeholder={placeholder} autoComplete={autoComplete} />;
};

export const NumberField = ({ label, placeholder, autoComplete, min, max, step }: NumberFieldProps) => {
  const id = useId();
  const errorId = id + "-errors";
  const field = useFieldContext<number | undefined>();
  const hasErrors = field.state.meta.errors.length > 0;
  const shouldShowErrors = hasErrors && field.state.meta.isDirty;

  return (
    <div className={styles.fieldWrapper}>
      <label htmlFor={id} className={styles.label}>
        {label}
      </label>
      <div className={styles.dataContainer}>
        <input
          id={id}
          name={field.name}
          value={field.state.value ?? ""}
          className={styles.input}
          type="number"
          placeholder={placeholder}
          autoComplete={autoComplete}
          min={min}
          max={max}
          step={step}
          aria-invalid={shouldShowErrors}
          aria-describedby={shouldShowErrors ? errorId : undefined}
          onFocus={(e) => e.target.select()}
          onBlur={field.handleBlur}
          onChange={(e) => {
            const value = e.target.value;
            if (value === "") {
              field.handleChange(undefined);
              return;
            }
            const parsed = Number(value);
            field.handleChange(Number.isNaN(parsed) ? undefined : parsed);
          }}
        />
        <div id={errorId}>
          {shouldShowErrors ? <ErrorContainer errors={field.state.meta.errors} /> : null}
        </div>
      </div>
    </div>
  );
};

const FieldWithType = ({
  label,
  type,
  placeholder,
  autoComplete,
}: {
  label: string;
  type: string;
  placeholder?: string;
  autoComplete?: string;
}) => {
  const id = useId();
  const errorId = id + "-errors";
  const field = useFieldContext<string>();
  const hasErrors = field.state.meta.errors.length > 0;
  const shouldShowErrors = hasErrors && field.state.meta.isDirty;

  return (
    <div className={styles.fieldWrapper}>
      <label htmlFor={id} className={styles.label}>
        {label}
      </label>
      <div className={styles.dataContainer}>
        <input
          id={id}
          name={field.name}
          value={field.state.value}
          className={styles.input}
          type={type}
          placeholder={placeholder}
          autoComplete={autoComplete}
          aria-invalid={shouldShowErrors}
          aria-describedby={shouldShowErrors ? errorId : undefined}
          onBlur={field.handleBlur}
          onChange={(e) => field.handleChange(e.target.value)}
        />
        <div id={errorId}>
          {shouldShowErrors ? <ErrorContainer errors={field.state.meta.errors} /> : null}
        </div>
      </div>
    </div>
  );
};
