import { Link } from "wouter";

import { CommonLayout } from "@/components/CommonLayout/CommonLayout";
import { RequestState } from "@/components/ui/RequestState/RequestState";
import { useAppForm } from "@/config/use-app-form";
import { SignupRequestSchema } from "@/features/auth/types/auth-dtos";
import { useSignup } from "@/features/auth/services/auth-service";

import styles from "./SignupPage.module.css";

export function SignupPage() {
  const { mutate, error, isPending } = useSignup();

  const formData = useAppForm({
    defaultValues: {
      email: "",
      name: "",
      lastname: "",
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

        <RequestState isLoading={isPending} error={error ?? null} loadingLabel="Creando cuenta...">
          <formData.AppForm>
            <formData.FormContainer
              submitLabel="Crear cuenta"
              pendingLabel="Creando cuenta..."
              isPending={isPending}
              footer={
                <p>
                  ¿Ya tienes cuenta?{" "}
                  <Link href="/login" className={styles.link}>
                    Inicia sesión
                  </Link>
                </p>
              }
            >
              <formData.AppField
                name="name"
                children={(field) => (
                  <field.TextField
                    label="Nombre"
                    placeholder="Ej: Juan"
                    autoComplete="given-name"
                  />
                )}
              />
              <formData.AppField
                name="lastname"
                children={(field) => (
                  <field.TextField
                    label="Apellido"
                    placeholder="Ej: Pérez"
                    autoComplete="family-name"
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
                  <field.TextField label="DNI" placeholder="Ej: 12345678" autoComplete="off" />
                )}
              />
            </formData.FormContainer>
          </formData.AppForm>
        </RequestState>
      </section>
    </CommonLayout>
  );
}

