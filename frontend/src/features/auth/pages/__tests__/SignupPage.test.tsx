import { screen } from "@testing-library/react";
import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { describe, expect, it } from "vitest";

import { SignupPage } from "@/features/auth/pages/SignupPage";
import { StudentCreateSchema } from "@/features/auth/types/auth-dtos";
import { TokenProvider } from "@/lib/session";
import { render } from "@testing-library/react";

function renderSignupPage() {
  const queryClient = new QueryClient({
    defaultOptions: {
      queries: { retry: false },
      mutations: { retry: false },
    },
  });

  return render(
    <QueryClientProvider client={queryClient}>
      <TokenProvider>
        <SignupPage />
      </TokenProvider>
    </QueryClientProvider>,
  );
}

describe("SignupPage", () => {
  it("solicita nombre y apellido del hijo por separado, sin colegio ni curso", async () => {
    renderSignupPage();

    expect((await screen.findAllByLabelText("Nombre")).length).toBeGreaterThanOrEqual(2);
    expect(screen.getAllByLabelText("Apellido")).toHaveLength(2);
    expect(screen.getByLabelText("DNI")).toBeInTheDocument();
    expect(screen.queryByLabelText("Colegio")).not.toBeInTheDocument();
    expect(screen.queryByLabelText("Curso")).not.toBeInTheDocument();
    expect(screen.getByText(/Solo podés cargar DNIs precargados por administración/i)).toBeInTheDocument();
    expect(screen.getByText(/la referencia del viaje se vinculará automáticamente/i)).toBeInTheDocument();
  });

  it("el schema del alumno exige apellido", () => {
    const result = StudentCreateSchema.safeParse({
      name: "Luca",
      lastname: "",
      dni: "40111222",
    });

    expect(result.success).toBe(false);
  });

  it("normaliza nombre y apellido del alumno a mayusculas", () => {
    const result = StudentCreateSchema.safeParse({
      name: "LuCa",
      lastname: "péRez",
      dni: "40111222",
    });

    expect(result.success).toBe(true);
    if (result.success) {
      expect(result.data.name).toBe("LUCA");
      expect(result.data.lastname).toBe("PÉREZ");
    }
  });
});
