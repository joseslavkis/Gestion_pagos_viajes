import { Link } from "wouter";

import styles from "./InitialLandingPage.module.css";
import logoAnimado from "@/assets/logo-animado.mov";

export function InitialLandingPage() {
  return (
    <div className={styles.page}>
      <div className={styles.orbA} aria-hidden="true" />
      <div className={styles.orbB} aria-hidden="true" />
      <div className={styles.orbC} aria-hidden="true" />

      <main className={styles.main}>
        <section className={styles.hero}>
          <div className={styles.visualColumn}>
            <div className={styles.videoShell}>
              <div className={styles.lightTrail} aria-hidden="true" />
              <div className={styles.particles} aria-hidden="true" />
              <video
                className={styles.logo}
                autoPlay
                muted
                playsInline
                preload="auto"
              >
                <source src="/logo-animado.webm" type="video/webm" />
                <source src={logoAnimado} type="video/quicktime" />
              </video>
            </div>
          </div>

          <div className={styles.copyColumn}>
            <h1 className={styles.title}>Gestiona los viajes escolares con total transparencia</h1>
            <p className={styles.description}>
              Organiza pagos, cuotas y seguimiento en un solo lugar, con claridad para familias y escuelas.
            </p>

            <div className={styles.ctas}>
              <Link href="/login" className={`${styles.button} ${styles.primary}`}>
                Acceder
              </Link>
              <Link href="/signup" className={`${styles.button} ${styles.secondary}`}>
                Crear cuenta
              </Link>
              <a href="#contacto" className={`${styles.button} ${styles.ghost}`}>
                Contacto
              </a>
            </div>
          </div>
        </section>

        <section id="contacto" className={styles.contactSection}>
          <div className={styles.contactGrid}>
            <div className={styles.contactIntro}>
              <p className={styles.kicker}>Soporte humano</p>
              <h2 className={styles.contactTitle}>Conversemos sobre tu agencia y tus viajes</h2>
              <p className={styles.contactDescription}>
                Nuestro equipo acompana a escuelas y familias para implementar un circuito de cobros simple,
                transparente y confiable.
              </p>

              <ul className={styles.contactList}>
                <li className={styles.contactItem}>
                  <span className={styles.iconBadge} aria-hidden="true">
                    T
                  </span>
                  <span>+54 11 4321-8765</span>
                </li>
                <li className={styles.contactItem}>
                  <span className={styles.iconBadge} aria-hidden="true">
                    @
                  </span>
                  <span>soporte@travelpay.com.ar</span>
                </li>
                <li className={styles.contactItem}>
                  <span className={styles.iconBadge} aria-hidden="true">
                    U
                  </span>
                  <span>Buenos Aires, Argentina</span>
                </li>
              </ul>

              <div className={styles.mapCard} aria-hidden="true">
                <div className={styles.mapPin} />
              </div>
            </div>

            <form className={styles.contactForm}>
              <label className={styles.field}>
                <span>Nombre</span>
                <input type="text" name="name" placeholder="Tu nombre" autoComplete="name" />
              </label>

              <label className={styles.field}>
                <span>Email</span>
                <input type="email" name="email" placeholder="tu-email@colegio.edu" autoComplete="email" />
              </label>

              <label className={styles.field}>
                <span>Mensaje</span>
                <textarea name="message" rows={5} placeholder="Contanos que necesitan resolver..." />
              </label>

              <button type="submit" className={styles.submitButton}>
                Enviar consulta
              </button>
            </form>
          </div>
        </section>
      </main>
    </div>
  );
}

