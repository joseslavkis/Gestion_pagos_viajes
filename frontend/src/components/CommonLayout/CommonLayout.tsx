import React from "react";
import { Link } from "wouter";

import { getRoleFromToken } from "@/lib/auth-role";
import { useToken } from "@/lib/session";
import logo from "@/assets/logo.png";

import styles from "./CommonLayout.module.css";

export const CommonLayout = ({ children }: React.PropsWithChildren) => {
  const [tokenState] = useToken();
  const [isMenuOpen, setIsMenuOpen] = React.useState(false);
  const menuRef = React.useRef<HTMLDivElement | null>(null);

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
            <ul className={styles.menuDropdown}>
              {tokenState.state === "LOGGED_OUT" ? <LoggedOutLinks onNavigate={closeMenu} /> : <LoggedInLinks onNavigate={closeMenu} />}
            </ul>
          ) : null}
        </div>
      </header>
      <main className={styles.body}>{children}</main>
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
