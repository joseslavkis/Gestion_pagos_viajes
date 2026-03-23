import { fireEvent, screen } from "@testing-library/react";
import { HttpResponse, http } from "msw";
import { describe, expect, it } from "vitest";

import { BankAccountsPage } from "@/features/bank-accounts/pages/BankAccountsPage";
import { server } from "@/test/msw-server";
import { renderWithProviders } from "@/test/test-utils";

describe("BankAccountsPage", () => {
  it("crea una cuenta nueva y refresca el listado", async () => {
    let accounts = [
      {
        id: 1,
        bankName: "ICBC",
        accountLabel: "Cuenta en pesos",
        accountHolder: "Proyecto VA SRL",
        accountNumber: "123",
        taxId: "20-123",
        cbu: "456",
        alias: "ICBC.PESOS",
        currency: "ARS",
        active: true,
        displayOrder: 1,
      },
    ];

    server.use(
      http.get("http://localhost:30002/api/v1/bank-accounts/admin", () => HttpResponse.json(accounts)),
      http.post("http://localhost:30002/api/v1/bank-accounts", async ({ request }) => {
        const body = await request.json() as Record<string, unknown>;
        const created = {
          id: 2,
          active: true,
          ...body,
        };
        accounts = [...accounts, created as typeof accounts[number]];
        return HttpResponse.json(created, { status: 201 });
      }),
    );

    renderWithProviders(<BankAccountsPage />, "ROLE_ADMIN");

    expect(await screen.findByText("ICBC · Cuenta en pesos")).toBeInTheDocument();

    fireEvent.change(screen.getByLabelText("Banco"), { target: { value: "Galicia" } });
    fireEvent.change(screen.getByLabelText("Etiqueta"), { target: { value: "Cuenta USD" } });
    fireEvent.change(screen.getByLabelText("Titular"), { target: { value: "Proyecto VA SRL" } });
    fireEvent.change(screen.getByLabelText("Número de cuenta"), { target: { value: "999" } });
    fireEvent.change(screen.getByLabelText("CUIT"), { target: { value: "30-71131646-5" } });
    fireEvent.change(screen.getByLabelText("CBU"), { target: { value: "0070" } });
    fireEvent.change(screen.getByLabelText("Alias"), { target: { value: "GALICIA.USD" } });
    fireEvent.change(screen.getByLabelText("Moneda"), { target: { value: "USD" } });
    fireEvent.change(screen.getByLabelText("Orden"), { target: { value: "2" } });

    fireEvent.click(screen.getByRole("button", { name: "Crear cuenta" }));

    expect(await screen.findByText("Galicia · Cuenta USD")).toBeInTheDocument();
  });
});
