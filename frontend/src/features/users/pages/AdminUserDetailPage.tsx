import { Link } from "wouter";

import { CommonLayout } from "@/components/CommonLayout/CommonLayout";
import { RequestState } from "@/components/ui/RequestState/RequestState";
import { useAdminUserDetail } from "@/features/users/services/users-service";

import styles from "./AdminUserDetailPage.module.css";

const arsFormatter = new Intl.NumberFormat("es-AR", { style: "currency", currency: "ARS" });
const usdFormatter = new Intl.NumberFormat("es-AR", { style: "currency", currency: "USD" });

type AdminUserDetailPageProps = {
  userId: number;
};

export function AdminUserDetailPage({ userId }: AdminUserDetailPageProps) {
  const { data, isLoading, error } = useAdminUserDetail(Number.isFinite(userId) ? userId : null);

  return (
    <CommonLayout>
      <section className={styles.page}>
        <div className={styles.container}>
          <div className={styles.topActions}>
            <Link href="/users/search" className={styles.backLink}>
              ← Volver al buscador
            </Link>
          </div>

          <RequestState isLoading={isLoading} error={error ?? null} loadingLabel="Cargando detalle del usuario...">
            {data ? (
              <>
                <header className={styles.hero}>
                  <div>
                    <p className={styles.eyebrow}>Usuario</p>
                    <h1 className={styles.title}>
                      {data.lastname}, {data.name}
                    </h1>
                    <p className={styles.subtitle}>{data.email}</p>
                  </div>
                  <div className={styles.identityGrid}>
                    <div className={styles.identityItem}>
                      <span className={styles.identityLabel}>DNI</span>
                      <strong>{data.dni ?? "Sin DNI"}</strong>
                    </div>
                    <div className={styles.identityItem}>
                      <span className={styles.identityLabel}>Teléfono</span>
                      <strong>{data.phone ?? "Sin teléfono"}</strong>
                    </div>
                    <div className={styles.identityItem}>
                      <span className={styles.identityLabel}>Rol</span>
                      <strong>{data.role}</strong>
                    </div>
                    <div className={styles.identityItem}>
                      <span className={styles.identityLabel}>Hijos</span>
                      <strong>{data.students.length}</strong>
                    </div>
                  </div>
                </header>

                <section className={styles.sectionCard}>
                  <div className={styles.sectionHeader}>
                    <h2 className={styles.sectionTitle}>Hijos asociados</h2>
                    <span className={styles.sectionCount}>{data.students.length}</span>
                  </div>
                  {data.students.length === 0 ? (
                    <p className={styles.emptyText}>Este usuario no tiene hijos cargados.</p>
                  ) : (
                    <div className={styles.studentGrid}>
                      {data.students.map((student) => (
                        <article key={student.id} className={styles.studentCard}>
                          <h3 className={styles.cardTitle}>{student.name}</h3>
                          <p className={styles.cardMeta}>DNI: {student.dni}</p>
                        </article>
                      ))}
                    </div>
                  )}
                </section>

                <section className={styles.sectionCard}>
                  <div className={styles.sectionHeader}>
                    <h2 className={styles.sectionTitle}>Cuotas y viajes</h2>
                    <span className={styles.sectionCount}>{data.installments.length}</span>
                  </div>
                  {data.installments.length === 0 ? (
                    <p className={styles.emptyText}>No hay cuotas asociadas a este usuario.</p>
                  ) : (
                    <div className={styles.installmentList}>
                      {data.installments.map((installment) => (
                        <article key={installment.installmentId} className={styles.installmentCard}>
                          <div className={styles.installmentHeader}>
                            <div>
                              <h3 className={styles.cardTitle}>{installment.tripName}</h3>
                              <p className={styles.cardMeta}>
                                Cuota {installment.installmentNumber} · vence {formatDate(installment.dueDate)}
                              </p>
                            </div>
                            <span className={`${styles.statusBadge} ${getStatusClassName(installment.uiStatusTone)}`}>
                              {installment.uiStatusLabel}
                            </span>
                          </div>
                          <div className={styles.installmentGrid}>
                            <div>
                              <span className={styles.identityLabel}>Alumno</span>
                              <strong>{installment.studentName ?? "Sin alumno"}</strong>
                            </div>
                            <div>
                              <span className={styles.identityLabel}>Total</span>
                              <strong>{formatMoney(installment.tripCurrency, installment.totalDue)}</strong>
                            </div>
                            <div>
                              <span className={styles.identityLabel}>Pagado</span>
                              <strong>{formatMoney(installment.tripCurrency, installment.paidAmount)}</strong>
                            </div>
                          </div>
                          {installment.latestReceiptObservation ? (
                            <p className={styles.observation}>
                              Observación admin: {installment.latestReceiptObservation}
                            </p>
                          ) : null}
                        </article>
                      ))}
                    </div>
                  )}
                </section>

                <section className={styles.sectionCard}>
                  <div className={styles.sectionHeader}>
                    <h2 className={styles.sectionTitle}>Pagos y comprobantes</h2>
                    <span className={styles.sectionCount}>{data.payments.length}</span>
                  </div>
                  {data.payments.length === 0 ? (
                    <p className={styles.emptyText}>Este usuario todavía no registró comprobantes.</p>
                  ) : (
                    <div className={styles.receiptList}>
                      {data.payments.map((payment) => (
                        <article key={payment.submissionId} className={styles.receiptCard}>
                          <div className={styles.installmentHeader}>
                            <div>
                              <h3 className={styles.cardTitle}>Pago #{payment.submissionId}</h3>
                              <p className={styles.cardMeta}>
                                {formatDate(payment.reportedPaymentDate)} · {formatInstallments(payment.installments)}
                              </p>
                            </div>
                            <span className={styles.receiptStatus}>{formatPaymentStatus(payment.status)}</span>
                          </div>
                          <div className={styles.receiptGrid}>
                            <div>
                              <span className={styles.identityLabel}>Monto informado</span>
                              <strong>{formatMoney(payment.paymentCurrency, payment.reportedAmount)}</strong>
                            </div>
                            <div>
                              <span className={styles.identityLabel}>Aprobado</span>
                              <strong>{formatMoney(payment.paymentCurrency, payment.approvedAmount)}</strong>
                            </div>
                            <div>
                              <span className={styles.identityLabel}>Método</span>
                              <strong>{payment.paymentMethod}</strong>
                            </div>
                            <div>
                              <span className={styles.identityLabel}>Cuenta acreditada</span>
                              <strong>{payment.bankAccountDisplayName ?? "Sin cuenta"}</strong>
                            </div>
                          </div>
                          {payment.installments.length > 0 ? (
                            <p className={styles.observation}>
                              Imputación: {formatInstallments(payment.installments)}
                            </p>
                          ) : null}
                          {payment.adminObservation ? (
                            <p className={styles.observation}>Observación: {payment.adminObservation}</p>
                          ) : null}
                          {payment.fileKey ? (
                            <a href={payment.fileKey} target="_blank" rel="noreferrer" className={styles.attachmentLink}>
                              Ver comprobante adjunto
                            </a>
                          ) : null}
                        </article>
                      ))}
                    </div>
                  )}
                </section>
              </>
            ) : null}
          </RequestState>
        </div>
      </section>
    </CommonLayout>
  );
}

function formatDate(date: string) {
  const value = new Date(`${date}T00:00:00`);
  if (Number.isNaN(value.getTime())) {
    return date;
  }

  return new Intl.DateTimeFormat("es-AR", {
    day: "2-digit",
    month: "2-digit",
    year: "numeric",
    timeZone: "America/Argentina/Buenos_Aires",
  }).format(value);
}

function formatMoney(currency: "ARS" | "USD", amount: number) {
  return currency === "USD" ? usdFormatter.format(amount) : arsFormatter.format(amount);
}

function formatInstallments(installments: Array<{ installmentNumber: number }>) {
  if (installments.length === 0) {
    return "Sin imputación visible";
  }

  return installments.map((installment) => `#${installment.installmentNumber}`).join(", ");
}

function formatPaymentStatus(status: string) {
  switch (status) {
    case "PENDING":
      return "Pendiente";
    case "APPROVED":
      return "Aprobado";
    case "REJECTED":
      return "Rechazado";
    case "PARTIALLY_APPROVED":
      return "Aprobado parcial";
    case "VOIDED":
      return "Anulado";
    default:
      return status;
  }
}

function getStatusClassName(tone: "green" | "yellow" | "red") {
  if (tone === "green") {
    return styles.statusGreen;
  }
  if (tone === "yellow") {
    return styles.statusYellow;
  }
  return styles.statusRed;
}
