import styles from "./ReceiptSuccessScreen.module.css";

export type ReceiptSuccessData = {
  tripDisplayName: string;
  installmentsLabel: string;
  amount: string;
  paymentDate: string;
  paymentMethod: string;
  bankAccountName: string;
  fileName: string;
};

const paymentMethodLabels: Record<string, string> = {
  BANK_TRANSFER: "Transferencia bancaria",
  CASH: "Efectivo",
  DEPOSIT: "Depósito",
  OTHER: "Otro",
};

type Props = {
  data: ReceiptSuccessData;
  onBack: () => void;
};

export function ReceiptSuccessScreen({ data, onBack }: Props) {
  return (
    <div className={styles.overlay}>
      <div className={styles.card}>
        {/* Animated checkmark */}
        <div className={styles.iconWrap}>
          <svg className={styles.checkIcon} viewBox="0 0 52 52" aria-hidden="true">
            <circle className={styles.checkCircle} cx="26" cy="26" r="25" fill="none" />
            <path className={styles.checkMark} fill="none" d="M14.1 27.2l7.1 7.2 16.7-16.8" />
          </svg>
        </div>

        <h1 className={styles.title}>¡Comprobante adjuntado!</h1>
        <p className={styles.subtitle}>
          Tu comprobante fue enviado al administrador y será revisado pronto.
        </p>

        <div className={styles.detailsGrid}>
          <div className={styles.detailRow}>
            <span className={styles.detailLabel}>Viaje</span>
            <span className={styles.detailValue}>{data.tripDisplayName}</span>
          </div>
          <div className={styles.detailRow}>
            <span className={styles.detailLabel}>Cuotas</span>
            <span className={styles.detailValue}>{data.installmentsLabel}</span>
          </div>
          <div className={styles.detailRow}>
            <span className={styles.detailLabel}>Monto reportado</span>
            <span className={styles.detailValue}>{data.amount}</span>
          </div>
          <div className={styles.detailRow}>
            <span className={styles.detailLabel}>Fecha de pago</span>
            <span className={styles.detailValue}>{data.paymentDate}</span>
          </div>
          <div className={styles.detailRow}>
            <span className={styles.detailLabel}>Método</span>
            <span className={styles.detailValue}>
              {paymentMethodLabels[data.paymentMethod] ?? data.paymentMethod}
            </span>
          </div>
          <div className={styles.detailRow}>
            <span className={styles.detailLabel}>Cuenta acreditada</span>
            <span className={styles.detailValue}>{data.bankAccountName}</span>
          </div>
          <div className={styles.detailRow}>
            <span className={styles.detailLabel}>Archivo</span>
            <span className={styles.detailValue}>{data.fileName}</span>
          </div>
        </div>

        <button type="button" className={styles.backButton} onClick={onBack}>
          ← Volver
        </button>
      </div>
    </div>
  );
}
