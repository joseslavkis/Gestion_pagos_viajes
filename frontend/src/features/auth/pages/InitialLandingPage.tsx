import type { FormEvent, MouseEvent } from "react";
import { useMemo, useState } from "react";
import { Link } from "wouter";

import { ErrorContainer } from "@/components/form-components/ErrorContainer/ErrorContainer";
import { useSendContactMessage } from "@/features/contact/services/contact-service";

import styles from "./InitialLandingPage.module.css";
import logoAnimado from "@/assets/logo-animado.mov";
import logoAnimadoMp4 from "@/assets/logo-animado.mp4";

export function InitialLandingPage() {
  const { mutateAsync, error, isPending, reset } = useSendContactMessage();
  const [sent, setSent] = useState(false);

  const [name, setName] = useState("");
  const [email, setEmail] = useState("");
  const [message, setMessage] = useState("");

  const errors = useMemo(() => (error ? [error] : []), [error]);

  const handleContactClick = (event: MouseEvent<HTMLAnchorElement>) => {
    event.preventDefault();
    document.getElementById("contacto")?.scrollIntoView({ behavior: "smooth", block: "start" });
  };

  const handleContactSubmit = async (e: FormEvent<HTMLFormElement>) => {
    e.preventDefault();
    setSent(false);
    reset();

    await mutateAsync({
      name: name.trim(),
      email: email.trim(),
      message: message.trim(),
    });

    setSent(true);
    setName("");
    setEmail("");
    setMessage("");
  };

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
                preload="metadata"
              >
                <source src={logoAnimado} type="video/quicktime" />
                <source src={logoAnimadoMp4} type="video/mp4" />
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
              <a href="#contacto" className={`${styles.button} ${styles.ghost}`} onClick={handleContactClick}>
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
                  <span>Tel. (54) (9) (11) 5664 2755</span>
                </li>
                <li className={styles.contactItem}>
                  <span className={styles.iconBadge} aria-hidden="true">
                    @
                  </span>
                  <span>consultas@proyectova.com.ar</span>
                </li>
                <li className={styles.contactItem}>
                  <span className={styles.iconBadge} aria-hidden="true">
                    U
                  </span>
                  <span>Av. Sto. My. C. Beliera 3025 (RN8), Pilar, Buenos Aires</span>
                </li>
                <li className={styles.contactItem}>
                  <span className={styles.iconBadge} aria-hidden="true">
                    I
                  </span>
                  <span>Parque Empresarial Austral - Edificio Insignia M3 - Espacio VA EVT Leg. 14325</span>
                </li>
              </ul>

              <a
                href="https://maps.google.com/?q=Av.+Sto.+My.+C.+Beliera+3025+Pilar+Buenos+Aires"
                target="_blank"
                rel="noreferrer"
                className={styles.mapLinkCard}
              >
                <div className={styles.mapLinkTop}>
                  <span className={styles.mapLogo} aria-hidden="true">
                    G
                  </span>
                  <span className={styles.mapProvider}>Google Maps</span>
                </div>
                <p className={styles.mapLinkText}>Ver ubicacion de Espacio VA</p>
                <span className={styles.mapLinkHint}>Se abre en una nueva pestana</span>
              </a>
            </div>

            <form className={styles.contactForm} onSubmit={handleContactSubmit}>
              <label className={styles.field}>
                <span>Nombre</span>
                <input
                  type="text"
                  name="name"
                  placeholder="Tu nombre"
                  autoComplete="name"
                  value={name}
                  onChange={(e) => setName(e.target.value)}
                  disabled={isPending}
                  required
                />
              </label>

              <label className={styles.field}>
                <span>Email</span>
                <input
                  type="email"
                  name="email"
                  placeholder="tu-email@colegio.edu"
                  autoComplete="email"
                  value={email}
                  onChange={(e) => setEmail(e.target.value)}
                  disabled={isPending}
                  required
                />
              </label>

              <label className={styles.field}>
                <span>Mensaje</span>
                <textarea
                  name="message"
                  rows={5}
                  placeholder="Contanos que necesitan resolver..."
                  value={message}
                  onChange={(e) => setMessage(e.target.value)}
                  disabled={isPending}
                  required
                />
              </label>

              {errors.length > 0 ? <ErrorContainer errors={errors} /> : null}
              {sent ? <p className={styles.successMessage}>¡Mensaje enviado!</p> : null}

              <button type="submit" className={styles.submitButton}>
                {isPending ? "Loading..." : "Enviar consulta"}
              </button>
            </form>
          </div>
        </section>
      </main>
    </div>
  );
}

