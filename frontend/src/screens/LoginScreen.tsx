import { CommonLayout } from "@/components/CommonLayout/CommonLayout";
import { useAppForm } from "@/config/use-app-form";
import { LoginRequestSchema } from "@/models/Login";
import { useLogin } from "@/services/UserServices";
import { Link } from "wouter";

import styles from "./LoginScreen.module.css";

export const LoginScreen = () => {
  const { mutate, error, isPending } = useLogin();

  const formData = useAppForm({
    defaultValues: {
      username: "",
      password: "",
    },
    validators: {
      onChange: LoginRequestSchema,
    },
    onSubmit: async ({ value }) => mutate(value),
  });

  return (
    <CommonLayout>
      <section className={styles.wrapper}>
        <header className={styles.header}>
          <p className={styles.eyebrow}>Acceso Seguro</p>
          <h1>Iniciar sesión</h1>
          <p className={styles.description}>Ingresa para gestionar pagos de viajes de forma confiable.</p>
        </header>

        <formData.AppForm>
          <formData.FormContainer
            extraError={(error as Error | null) ?? null}
            submitLabel="Entrar"
            pendingLabel="Verificando credenciales..."
            isPending={isPending}
            footer={
              <p>
                ¿No tienes cuenta? <Link href="/signup">Crear una ahora</Link>
              </p>
            }
          >
            <formData.AppField
              name="username"
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
                  placeholder="Ingresa tu contraseña"
                  autoComplete="current-password"
                />
              )}
            />
          </formData.FormContainer>
        </formData.AppForm>
      </section>
    </CommonLayout>
  );
};
