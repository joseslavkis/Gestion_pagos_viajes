import { screen } from "@testing-library/react";
import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { HttpResponse, http } from "msw";
import { describe, expect, it } from "vitest";

import { SignupPage } from "@/features/auth/pages/SignupPage";
import { TokenProvider } from "@/lib/session";
import { server } from "@/test/msw-server";
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
  it("usa solo colegios cargados por administracion", async () => {
    server.use(
      http.get("http://localhost:30002/api/v1/schools", () =>
        HttpResponse.json([
          { id: 1, name: "Colegio Ward" },
          { id: 2, name: "Colegio San Jose" },
        ]),
      ),
    );

    renderSignupPage();

    expect(await screen.findByRole("option", { name: "Colegio Ward" })).toBeInTheDocument();
    expect(screen.getByRole("option", { name: "Colegio San Jose" })).toBeInTheDocument();
    expect(screen.queryByRole("option", { name: "Instituto San Martín de Tours" })).not.toBeInTheDocument();
  });
});
