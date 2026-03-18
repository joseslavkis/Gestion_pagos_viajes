import { CommonLayout } from "@/components/CommonLayout/CommonLayout";
import { useAppForm } from "@/config/use-app-form";
import { SignupRequestSchema } from "@/models/Login";
import { useSignup } from "@/services/UserServices";
import { Link } from "wouter";

import styles from "./SignupScreen.module.css";

export const SignupScreen = () => {
  const { mutate, error, isPending } = useSignup();

  const formData = useAppForm({
    defaultValues: {
      username: "",
      email: "",
      firstName: "",
      lastName: "",
      dni: "",
      password: "",
    },
    validators: {
      onChange: SignupRequestSchema,
    },
    onSubmit: async ({ value }) => mutate(value),
  });

  return (
    <CommonLayout>
      <section className={styles.wrapper}>
        <header className={styles.header}>
          <p className={styles.eyebrow}>Registro Protegido</p>
          <h1>Crear cuenta</h1>
          <p className={styles.description}>Completa tus datos y activa tu espacio seguro de pagos.</p>
        </header>

        <formData.AppForm>
          <formData.FormContainer
            extraError={(error as Error | null) ?? null}
            submitLabel="Crear cuenta"
            pendingLabel="Creando cuenta..."
            isPending={isPending}
            footer={
              <p>
                ¿Ya tienes cuenta? <Link href="/login">Inicia sesión</Link>
              </p>
            }
          >
            <formData.AppField
              name="firstName"
              children={(field) => (
                <field.TextField label="Nombre" placeholder="Ej: Juan" autoComplete="given-name" />
              )}
            />
            <formData.AppField
              name="lastName"
              children={(field) => (
                <field.TextField label="Apellido" placeholder="Ej: Pérez" autoComplete="family-name" />
              )}
            />
            <formData.AppField
              name="username"
              children={(field) => (
                <field.TextField
                  label="Username"
                  placeholder="Ej: mi-usuario"
                  autoComplete="username"
                />
              )}
            />
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
            <formData.AppField
              name="password"
              children={(field) => (
                <field.PasswordField
                  label="Contraseña"
                  placeholder="Ej: Abcdef1@"
                  autoComplete="new-password"
                />
              )}
            />
            <formData.AppField
              name="dni"
              children={(field) => (
                <field.TextField
                  label="DNI"
                  placeholder="Ej: 12345678"
                  autoComplete="off"
                />
              )}
            />
          </formData.FormContainer>
        </formData.AppForm>
      </section>
    </CommonLayout>
  );
};
