import React from "react";
import { Link } from "wouter";

import { useToken } from "@/services/TokenContext";

import styles from "./CommonLayout.module.css";

export const CommonLayout = ({ children }: React.PropsWithChildren) => {
  const [tokenState] = useToken();

  return (
    <div className={styles.mainLayout}>
      <header className={styles.topBar}>
        <Link href="/" className={styles.brand}>
          TravelPay
        </Link>
        <ul className={styles.links}>{tokenState.state === "LOGGED_OUT" ? <LoggedOutLinks /> : <LoggedInLinks />}</ul>
      </header>
      <main className={styles.body}>{children}</main>
    </div>
  );
};

const LoggedOutLinks = () => {
  return (
    <>
      <li>
        <Link href="/login" className={styles.navLink}>
          Ingresar
        </Link>
      </li>
      <li>
        <Link href="/signup" className={styles.navLink}>
          Crear cuenta
        </Link>
      </li>
    </>
  );
};

const LoggedInLinks = () => {
  const [, setTokenState] = useToken();

  const logOut = () => {
    setTokenState({ state: "LOGGED_OUT" });
  };

  return (
    <>
      <li>
        <Link href="/under-construction" className={styles.navLink}>
          Inicio
        </Link>
      </li>
      <li>
        <button className={styles.logoutButton} onClick={logOut}>
          Cerrar sesión
        </button>
      </li>
    </>
  );
};
