import { useMemo, useState, type CSSProperties } from "react";
import { Link, useSearch } from "wouter";
import { z } from "zod";

import { CommonLayout } from "@/components/CommonLayout/CommonLayout";
import { RequestState } from "@/components/ui/RequestState/RequestState";
import { useAppForm } from "@/config/use-app-form";
import { useResetPassword } from "@/features/auth/services/auth-service";
import { ResetPasswordSchema } from "@/features/auth/types/auth-dtos";

import styles from "./LoginPage.module.css";

const resetPasswordFormSchema = z.object({
  newPassword: z.string()
    .regex(
      /^(?=.*[a-z])(?=.*[A-Z])(?=.*\d)(?=.*[@$!%*?&]).{8,}$/,
      "La contraseña debe incluir mayúscula, minúscula, número y símbolo (@$!%*?&)",
    ),
  confirmPassword: z.string(),
}).refine(
  (data) => data.newPassword === data.confirmPassword,
  { message: "Las contraseñas no coinciden", path: ["confirmPassword"] },
);

const infoBoxStyle: CSSProperties = {
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

export function ResetPasswordPage() {
  const search = useSearch();
  const resetPassword = useResetPassword();
  const [isResetSuccessful, setIsResetSuccessful] = useState(false);

  const token = useMemo(() => new URLSearchParams(search).get("token")?.trim() ?? "", [search]);

  const formData = useAppForm({
    defaultValues: {
      newPassword: "",
      confirmPassword: "",
    },
    validators: {
      onChange: resetPasswordFormSchema,
    },
    onSubmit: async ({ value }) => {
      const parsed = ResetPasswordSchema.safeParse({ token, ...value });
      if (!parsed.success) {
        return;
      }

      resetPassword.mutate(
        { token: parsed.data.token, newPassword: parsed.data.newPassword },
        {
          onSuccess: () => setIsResetSuccessful(true),
        },
      );
    },
  });

  return (
    <CommonLayout>
      <section className={styles.wrapper}>
        <header className={styles.header}>
          <p className={styles.eyebrow}>Acceso Seguro</p>
          <h1>Restablecer contraseña</h1>
          <p className={styles.description}>Elige una nueva contraseña para volver a ingresar a tu cuenta.</p>
        </header>

        <RequestState isLoading={resetPassword.isPending} loadingLabel="Actualizando contraseña...">
          {!token ? (
            <div style={infoBoxStyle}>
              <p style={{ margin: 0 }}>El enlace no es válido. Solicitá uno nuevo desde el login.</p>
              <Link href="/forgot-password" style={buttonLinkStyle}>
                Solicitar nuevo enlace
              </Link>
            </div>
          ) : isResetSuccessful ? (
            <div style={infoBoxStyle}>
              <p style={{ margin: 0 }}>¡Contraseña actualizada! Ya podés iniciar sesión.</p>
              <Link href="/login" style={buttonLinkStyle}>
                Ir al login
              </Link>
            </div>
          ) : (
            <formData.AppForm>
              <formData.FormContainer
                submitLabel="Actualizar contraseña"
                pendingLabel="Actualizando..."
                isPending={resetPassword.isPending}
                footer={
                  <>
                    {resetPassword.error ? <p style={errorTextStyle}>{resetPassword.error.message}</p> : null}
                    <p>
                      <Link href="/forgot-password" className={styles.link}>
                        Solicitar un enlace nuevo
                      </Link>
                    </p>
                  </>
                }
              >
                <formData.AppField
                  name="newPassword"
                  children={(field) => (
                    <field.PasswordField
                      label="Nueva contraseña"
                      placeholder="Ingresa tu nueva contraseña"
                      autoComplete="new-password"
                    />
                  )}
                />
                <formData.AppField
                  name="confirmPassword"
                  children={(field) => (
                    <field.PasswordField
                      label="Confirmar contraseña"
                      placeholder="Repite tu nueva contraseña"
                      autoComplete="new-password"
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
