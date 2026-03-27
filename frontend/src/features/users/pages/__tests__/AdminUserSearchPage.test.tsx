import { fireEvent, screen, waitFor } from "@testing-library/react";
import { HttpResponse, http } from "msw";
import { describe, expect, it } from "vitest";

import { AdminUserSearchPage } from "@/features/users/pages/AdminUserSearchPage";
import { server } from "@/test/msw-server";
import { renderWithProviders } from "@/test/test-utils";

describe("AdminUserSearchPage", () => {
  it("busca usuarios admin y muestra resultados navegables", async () => {
    server.use(
      http.get("http://localhost:30002/api/v1/users/admin/search", ({ request }) => {
        const url = new URL(request.url);
        const query = url.searchParams.get("q");

        if (query === "clara") {
          return HttpResponse.json([
            {
              id: 7,
              email: "clara@test.com",
              name: "Clara",
              lastname: "Benitez",
              dni: "33444555",
              phone: "1133344455",
              role: "USER",
              studentsCount: 2,
            },
          ]);
        }

        return HttpResponse.json([]);
      }),
    );

    renderWithProviders(<AdminUserSearchPage />, "ROLE_ADMIN");

    fireEvent.change(screen.getByPlaceholderText("Buscar por nombre, mail o DNI..."), {
      target: { value: "clara" },
    });

    await waitFor(() => {
      expect(screen.getByRole("link", { name: /Benitez, Clara/i })).toBeInTheDocument();
    });

    expect(screen.getByText("clara@test.com")).toBeInTheDocument();
    expect(screen.getByText("2 hijos")).toBeInTheDocument();
  });
});
