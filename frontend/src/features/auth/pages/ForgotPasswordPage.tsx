import { useState, type CSSProperties } from "react";
import { Link } from "wouter";

import { CommonLayout } from "@/components/CommonLayout/CommonLayout";
import { RequestState } from "@/components/ui/RequestState/RequestState";
import { useAppForm } from "@/config/use-app-form";
import { useForgotPassword } from "@/features/auth/services/auth-service";
import { ForgotPasswordSchema } from "@/features/auth/types/auth-dtos";

import styles from "./LoginPage.module.css";

const successBoxStyle: CSSProperties = {
  width: "100%",
  border: "1px solid #d8e6fb",
  borderRadius: 18,
  background: "#f8fbff",
  padding: "1.1rem",
  display: "grid",
  gap: "0.85rem",
  color: "#0f2f57",
};

const buttonLinkStyle: CSSProperties = {
  display: "inline-flex",
  alignItems: "center",
  justifyContent: "center",
  minHeight: 46,
  padding: "0.8rem 1rem",
  borderRadius: 14,
  background: "#0b5bcf",
  color: "#fff",
  textDecoration: "none",
  fontWeight: 700,
};

const errorTextStyle: CSSProperties = {
  margin: 0,
  color: "#b42318",
};

export function ForgotPasswordPage() {
  const forgotPassword = useForgotPassword();
  const [submitted, setSubmitted] = useState(false);

  const formData = useAppForm({
    defaultValues: {
      email: "",
    },
    validators: {
      onChange: ForgotPasswordSchema,
    },
    onSubmit: async ({ value }) => {
      forgotPassword.mutate(value, {
        onSuccess: () => setSubmitted(true),
      });
    },
  });

  return (
    <CommonLayout>
      <section className={styles.wrapper}>
        <header className={styles.header}>
          <p className={styles.eyebrow}>Recuperación Segura</p>
          <h1>Recuperar contraseña</h1>
          <p className={styles.description}>Ingresa tu email y te enviaremos un enlace para restablecer tu acceso.</p>
        </header>

        <RequestState isLoading={forgotPassword.isPending} loadingLabel="Enviando enlace de recuperación...">
          {submitted ? (
            <div style={successBoxStyle}>
              <p style={{ margin: 0 }}>
                Si el email está registrado, recibirás un enlace en los próximos minutos. Revisá también tu carpeta de spam.
              </p>
              <Link href="/login" style={buttonLinkStyle}>
                Volver al login
              </Link>
            </div>
          ) : (
            <formData.AppForm>
              <formData.FormContainer
                submitLabel="Enviar enlace"
                pendingLabel="Enviando enlace..."
                isPending={forgotPassword.isPending}
                footer={
                  <>
                    {forgotPassword.error ? <p style={errorTextStyle}>{forgotPassword.error.message}</p> : null}
                    <p>
                      <Link href="/login" className={styles.link}>
                        Volver al login
                      </Link>
                    </p>
                  </>
                }
              >
                <formData.AppField
                  name="email"
                  children={(field) => (
                    <field.TextField
                      label="Email"
                      placeholder="tu-email@empresa.com"
                      autoComplete="email"
                    />
                  )}
                />
              </formData.FormContainer>
            </formData.AppForm>
          )}
        </RequestState>
      </section>
    </CommonLayout>
  );
}
