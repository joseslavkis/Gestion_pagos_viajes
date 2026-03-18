import styles from "./ErrorContainer.module.css";

type Props = {
  errors: Array<unknown>;
};

export const ErrorContainer = ({ errors }: Props) => {
  const normalizedMessages = errors
    .map((error) => {
      if (!error) {
        return null;
      }
      if (typeof error === "string") {
        return error;
      }
      if (error instanceof Error) {
        return error.message;
      }
      if (typeof error === "object" && "message" in error) {
        const maybeMessage = (error as { message?: unknown }).message;
        return typeof maybeMessage === "string" ? maybeMessage : null;
      }
      return null;
    })
    .filter((msg): msg is string => Boolean(msg && msg.trim()));

  if (normalizedMessages.length === 0) {
    return null;
  }

  return (
    <ul className={styles.errorContainer} role="alert" aria-live="polite">
      {normalizedMessages.map((message, index) => (
        <li key={message + "-" + index}>{message}</li>
      ))}
    </ul>
  );
};
