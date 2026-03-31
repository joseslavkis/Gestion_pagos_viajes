import React from "react";
import { Link } from "wouter";

import { getRoleFromToken } from "@/lib/auth-role";
import { useToken } from "@/lib/session";
import logo from "@/assets/logo.png";
import { createGsapMatchMedia, getMotionProfile, gsap, useGSAP } from "@/lib/gsap";

import styles from "./CommonLayout.module.css";

export const CommonLayout = ({ children }: React.PropsWithChildren) => {
  const [tokenState] = useToken();
  const [isMenuOpen, setIsMenuOpen] = React.useState(false);
  const menuRef = React.useRef<HTMLDivElement | null>(null);
  const menuListRef = React.useRef<HTMLUListElement | null>(null);
  const bodyRef = React.useRef<HTMLElement | null>(null);

  React.useEffect(() => {
    const handleClickOutside = (event: MouseEvent) => {
      if (menuRef.current && !menuRef.current.contains(event.target as Node)) {
        setIsMenuOpen(false);
      }
    };

    document.addEventListener("mousedown", handleClickOutside);
    return () => document.removeEventListener("mousedown", handleClickOutside);
  }, []);

  const toggleMenu = () => setIsMenuOpen((prev) => !prev);
  const closeMenu = () => setIsMenuOpen(false);

  useGSAP(
    () => {
      if (!bodyRef.current) {
        return;
      }

      const motion = getMotionProfile();
      const mm = createGsapMatchMedia();

      if (!mm) {
        gsap.set(bodyRef.current, { clearProps: "opacity,visibility,transform" });
        return;
      }

      mm.add("(prefers-reduced-motion: reduce)", () => {
        gsap.set(bodyRef.current, { clearProps: "opacity,visibility,transform" });
      });

      mm.add("(prefers-reduced-motion: no-preference)", () => {
        gsap.fromTo(
          bodyRef.current,
          { autoAlpha: 0, y: motion.distanceSm },
          {
            autoAlpha: 1,
            y: 0,
            duration: motion.durationBase,
            ease: "power2.out",
            clearProps: "opacity,visibility,transform",
          },
        );
      });

      return () => mm.revert();
    },
    { dependencies: [tokenState.state], scope: bodyRef, revertOnUpdate: true },
  );

  useGSAP(
    () => {
      if (!isMenuOpen || !menuListRef.current) {
        return;
      }

      const motion = getMotionProfile();
      const mm = createGsapMatchMedia();

      if (!mm) {
        gsap.set(menuListRef.current, { clearProps: "opacity,visibility,transform" });
        return;
      }

      mm.add("(prefers-reduced-motion: reduce)", () => {
        gsap.set(menuListRef.current, { clearProps: "opacity,visibility,transform" });
      });

      mm.add("(prefers-reduced-motion: no-preference)", () => {
        const items = menuListRef.current?.querySelectorAll("li");

        gsap.fromTo(
          menuListRef.current,
          {
            autoAlpha: 0,
            y: -motion.distanceSm,
            scale: motion.isCompact ? 0.99 : 0.98,
            transformOrigin: "top right",
          },
          {
            autoAlpha: 1,
            y: 0,
            scale: 1,
            duration: motion.durationFast,
            ease: "power2.out",
          },
        );

        if (items && items.length > 0) {
          gsap.fromTo(
            items,
            { autoAlpha: 0, x: motion.distanceSm },
            {
              autoAlpha: 1,
              x: 0,
              duration: motion.durationFast,
              stagger: motion.staggerFast,
              ease: "power2.out",
            },
          );
        }
      });

      return () => mm.revert();
    },
    { dependencies: [isMenuOpen], scope: menuRef, revertOnUpdate: true },
  );

  return (
    <div className={styles.mainLayout}>
      <header className={styles.topBar}>
        <Link href="/" className={styles.brand}>
          <img
            className={styles.logo}
            src={logo}
            alt="TravelPay"
          />
        </Link>

        <div className={styles.menuWrapper} ref={menuRef}>
          <button
            type="button"
            className={`${styles.menuToggle} ${isMenuOpen ? styles.menuToggleOpen : ""}`}
            aria-label="Abrir menú"
            aria-expanded={isMenuOpen}
            onClick={toggleMenu}
          >
            <span className={styles.menuBar} />
            <span className={styles.menuBar} />
            <span className={styles.menuBar} />
          </button>

          {isMenuOpen ? (
            <ul ref={menuListRef} className={styles.menuDropdown}>
              {tokenState.state === "LOGGED_OUT" ? <LoggedOutLinks onNavigate={closeMenu} /> : <LoggedInLinks onNavigate={closeMenu} />}
            </ul>
          ) : null}
        </div>
      </header>
      <main ref={bodyRef} className={styles.body}>{children}</main>
    </div>
  );
};

const LoggedOutLinks = ({ onNavigate }: { onNavigate: () => void }) => {
  return (
    <>
      <li>
        <Link href="/login" className={styles.navLink} onClick={onNavigate}>
          Ingresar
        </Link>
      </li>
      <li>
        <Link href="/signup" className={styles.navLink} onClick={onNavigate}>
          Crear cuenta
        </Link>
      </li>
    </>
  );
};

const LoggedInLinks = ({ onNavigate }: { onNavigate: () => void }) => {
  const [tokenState, setTokenState] = useToken();
  const role = tokenState.state === "LOGGED_IN" ? getRoleFromToken(tokenState.accessToken) : null;

  const logOut = () => {
    setTokenState({ state: "LOGGED_OUT" });
    onNavigate();
  };

  return (
    <>
      <li>
        <Link href="/" className={styles.navLink} onClick={onNavigate}>
          {role === "ADMIN" ? "Viajes" : "Inicio"}
        </Link>
      </li>
      {role === "USER" ? (
        <li>
          <Link href="/mis-hijos" className={styles.navLink} onClick={onNavigate}>
            Mis hijos
          </Link>
        </li>
      ) : null}
      {role === "ADMIN" ? (
        <>
          <li>
            <Link href="/payments/pending-review" className={styles.navLink} onClick={onNavigate}>
              Pendientes de revisión
            </Link>
          </li>
          <li>
            <Link href="/users/search" className={styles.navLink} onClick={onNavigate}>
              Buscar usuarios
            </Link>
          </li>
          <li>
            <Link href="/bank-accounts" className={styles.navLink} onClick={onNavigate}>
              Cuentas bancarias
            </Link>
          </li>
          <li>
            <Link href="/schools" className={styles.navLink} onClick={onNavigate}>
              Colegios
            </Link>
          </li>
        </>
      ) : null}
      <li>
        <button className={styles.logoutButton} onClick={logOut}>
          Cerrar sesión
        </button>
      </li>
    </>
  );
};
