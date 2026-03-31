import { Link } from "wouter";

import { CommonLayout } from "@/components/CommonLayout/CommonLayout";
import { RequestState } from "@/components/ui/RequestState/RequestState";
import { useAppForm } from "@/config/use-app-form";
import { LoginRequestSchema } from "@/features/auth/types/auth-dtos";
import { useLogin } from "@/features/auth/services/auth-service";

import styles from "./LoginPage.module.css";

export function LoginPage() {
  const { mutate, isPending } = useLogin();

  const formData = useAppForm({
    defaultValues: {
      email: "",
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

        <RequestState isLoading={isPending} loadingLabel="Verificando credenciales...">
          <formData.AppForm>
            <formData.FormContainer
              submitLabel="Entrar"
              pendingLabel="Verificando credenciales..."
              isPending={isPending}
              footer={
                <>
                  <p>
                    ¿No tienes cuenta?{" "}
                    <Link href="/signup" className={styles.link}>
                      Crear una ahora
                    </Link>
                  </p>
                  <p>
                    <Link href="/forgot-password" className={styles.link}>
                      ¿Olvidaste tu contraseña?
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
        </RequestState>
      </section>
    </CommonLayout>
  );
}
