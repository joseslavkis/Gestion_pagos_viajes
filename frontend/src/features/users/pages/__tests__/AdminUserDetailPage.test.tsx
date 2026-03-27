import { screen } from "@testing-library/react";
import { HttpResponse, http } from "msw";
import { describe, expect, it } from "vitest";

import { AdminUserDetailPage } from "@/features/users/pages/AdminUserDetailPage";
import { server } from "@/test/msw-server";
import { renderWithProviders } from "@/test/test-utils";

describe("AdminUserDetailPage", () => {
  it("muestra el detalle completo del usuario admin", async () => {
    server.use(
      http.get("http://localhost:30002/api/v1/users/admin/12/detail", () =>
        HttpResponse.json({
          id: 12,
          email: "clara@test.com",
          name: "Clara",
          lastname: "Benitez",
          dni: "33444555",
          phone: "1133344455",
          role: "USER",
          students: [
            {
              id: 88,
              name: "Tomas Benitez",
              dni: "44555666",
              schoolName: "Colegio Demo",
              courseName: "4to",
            },
          ],
          installments: [
            {
              tripId: 9,
              tripName: "Viaje a Mendoza",
              tripCurrency: "ARS",
              studentId: 88,
              studentName: "Tomas Benitez",
              studentDni: "44555666",
              schoolName: "Colegio Demo",
              courseName: "4to",
              installmentId: 100,
              installmentNumber: 1,
              dueDate: "2026-04-10",
              totalDue: 40000,
              paidAmount: 15000,
              installmentStatus: "YELLOW",
              latestReceiptStatus: "APPROVED",
              uiStatusCode: "DUE_SOON",
              uiStatusLabel: "Vence pronto",
              uiStatusTone: "yellow",
              latestReceiptObservation: "Pago verificado",
            },
          ],
          receipts: [
            {
              id: 501,
              installmentId: 100,
              installmentNumber: 1,
              reportedAmount: 15000,
              paymentCurrency: "ARS",
              exchangeRate: null,
              amountInTripCurrency: 15000,
              reportedPaymentDate: "2026-04-02",
              paymentMethod: "BANK_TRANSFER",
              status: "APPROVED",
              fileKey: "https://example.com/comprobante.pdf",
              adminObservation: "Pago verificado",
              bankAccountId: 3,
              bankAccountDisplayName: "Banco Test - Cuenta corriente",
              bankAccountAlias: "agencia.test",
            },
          ],
        }),
      ),
    );

    renderWithProviders(<AdminUserDetailPage userId={12} />, "ROLE_ADMIN");

    expect(await screen.findByText("Benitez, Clara")).toBeInTheDocument();
    expect(screen.getAllByText("Tomas Benitez")).toHaveLength(2);
    expect(screen.getByText("Viaje a Mendoza")).toBeInTheDocument();
    expect(screen.getAllByText(/Pago verificado/)).toHaveLength(2);
    expect(screen.getByRole("link", { name: "Ver comprobante adjunto" })).toBeInTheDocument();
  });
});
