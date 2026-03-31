import { screen } from "@testing-library/react";
import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { describe, expect, it } from "vitest";

import { SignupPage } from "@/features/auth/pages/SignupPage";
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
  it("solicita solo nombre y DNI del hijo, sin colegio ni curso", async () => {
    renderSignupPage();

    expect(await screen.findByLabelText("Nombre completo")).toBeInTheDocument();
    expect(screen.getByLabelText("DNI")).toBeInTheDocument();
    expect(screen.queryByLabelText("Colegio")).not.toBeInTheDocument();
    expect(screen.queryByLabelText("Curso")).not.toBeInTheDocument();
    expect(screen.getByText(/Solo podés cargar DNIs precargados por administración/i)).toBeInTheDocument();
    expect(screen.getByText(/la referencia del viaje se vinculará automáticamente/i)).toBeInTheDocument();
  });
});
