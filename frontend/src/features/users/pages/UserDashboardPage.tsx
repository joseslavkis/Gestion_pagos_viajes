import { CommonLayout } from "@/components/CommonLayout/CommonLayout";

import styles from "./UserDashboardPage.module.css";

export function UserDashboardPage() {
  return (
    <CommonLayout>
      <section className={styles.page}>
        <div className={styles.card}>
          <h1 className={styles.title}>Panel de usuario — próximamente</h1>
          <p className={styles.description}>
            Estamos trabajando en un espacio dedicado para que familias y responsables puedan seguir sus
            pagos y cuotas de forma clara y segura.
          </p>
        </div>
      </section>
    </CommonLayout>
  );
}

