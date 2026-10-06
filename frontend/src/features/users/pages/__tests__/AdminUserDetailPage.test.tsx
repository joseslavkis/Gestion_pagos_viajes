import { screen, within } from "@testing-library/react";
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
              name: "Tomas",
              lastname: "Benitez",
              dni: "44555666",
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
          payments: [
            {
              submissionId: 501,
              status: "APPROVED",
              reportedAmount: "15000.00",
              approvedAmount: "15000.00",
              approvedCurrency: "ARS", approvedExchangeRate: null, approvedQuoteRequestedDate: null,
              approvedQuoteEffectiveDate: null, approvedQuoteSource: null, approvedQuoteProvider: null,
              approvedQuoteProviderTimestamp: null, approvedCalculationVersion: "2", rejectedCurrency: null,
              rejectedAmount: "0.00",
              paymentCurrency: "ARS",
              exchangeRate: null,
              amountInTripCurrency: "15000.00",
              approvedAmountInTripCurrency: "15000.00",
              reportedPaymentDate: "2026-04-02",
              paymentMethod: "BANK_TRANSFER",
              fileKey: "https://example.com/comprobante.pdf",
              adminObservation: "Pago verificado",
              bankAccountId: 3,
              bankAccountDisplayName: "Banco Test - Cuenta corriente",
              bankAccountAlias: "agencia.test",
              tripId: 9,
              tripName: "Viaje a Mendoza",
              tripCurrency: "ARS",
              studentId: 88,
              studentName: "Tomas Benitez",
              studentDni: "44555666",
              installments: [
                {
                  receiptId: null,
                  installmentId: 100,
                  installmentNumber: 1,
                  dueDate: "2026-04-10",
                  totalDue: "40000.00",
                  paidAmount: "15000.00",
                  remainingAmount: "25000.00",
                  reportedAmount: "15000.00",
                  amountInTripCurrency: "15000.00",
                  status: "APPROVED",
                  allocationCurrency: "ARS",
                },
              ],
              source: "CUSTOMER_SUBMISSION",
              manualReason: null,
            },
            {
              submissionId: 502,
              status: "APPROVED",
              reportedAmount: "100.00",
              approvedAmount: "100.00",
              approvedCurrency: "ARS", approvedExchangeRate: null, approvedQuoteRequestedDate: null,
              approvedQuoteEffectiveDate: null, approvedQuoteSource: null, approvedQuoteProvider: null,
              approvedQuoteProviderTimestamp: null, approvedCalculationVersion: "2", rejectedCurrency: null,
              rejectedAmount: "0.00",
              paymentCurrency: "ARS",
              exchangeRate: null,
              amountInTripCurrency: "100.00",
              approvedAmountInTripCurrency: "100.00",
              reportedPaymentDate: "2026-04-03",
              paymentMethod: null,
              fileKey: "",
              adminObservation: "Efectivo",
              bankAccountId: null,
              bankAccountDisplayName: null,
              bankAccountAlias: null,
              tripId: 9,
              tripName: "Viaje a Mendoza",
              tripCurrency: "ARS",
              studentId: 88,
              studentName: "Tomas Benitez",
              studentDni: "44555666",
              installments: [],
              source: "ADMIN_MANUAL",
              manualReason: "Efectivo",
            },
          ],
        }),
      ),
    );

    renderWithProviders(<AdminUserDetailPage userId={12} />, "ROLE_ADMIN");

    expect(await screen.findByText("Benitez, Clara")).toBeInTheDocument();
    expect(screen.getByText("Benitez, Tomas")).toBeInTheDocument();
    expect(screen.getAllByText("Tomas Benitez")).toHaveLength(1);
    expect(screen.getByText("Viaje a Mendoza")).toBeInTheDocument();
    expect(screen.getByText("Cuota 1 · vence 10/04/2026")).toBeInTheDocument();
    expect(screen.getByText("02/04/2026 · #1")).toBeInTheDocument();
    expect(screen.getAllByText(/Pago verificado/)).toHaveLength(2);
    expect(screen.getByText("Pago #501")).toBeInTheDocument();
    expect(screen.getByRole("link", { name: "Ver comprobante adjunto 1" })).toBeInTheDocument();
    // Método con etiqueta legible, nunca el enum crudo.
    expect(screen.getByText("Transferencia bancaria")).toBeInTheDocument();
  });

  it("muestra la imputación manual sin duplicar motivo ni fingir cuenta", async () => {
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
          students: [],
          installments: [],
          payments: [
            {
              submissionId: 502,
              status: "APPROVED",
              reportedAmount: "100.00",
              approvedAmount: "100.00",
              approvedCurrency: "ARS", approvedExchangeRate: null, approvedQuoteRequestedDate: null,
              approvedQuoteEffectiveDate: null, approvedQuoteSource: null, approvedQuoteProvider: null,
              approvedQuoteProviderTimestamp: null, approvedCalculationVersion: "2", rejectedCurrency: null,
              rejectedAmount: "0.00",
              paymentCurrency: "ARS",
              exchangeRate: null,
              amountInTripCurrency: "100.00",
              approvedAmountInTripCurrency: "100.00",
              reportedPaymentDate: "2026-04-03",
              paymentMethod: null,
              fileKey: "",
              adminObservation: "Efectivo",
              bankAccountId: null,
              bankAccountDisplayName: null,
              bankAccountAlias: null,
              tripId: 9,
              tripName: "Viaje a Mendoza",
              tripCurrency: "ARS",
              studentId: 88,
              studentName: "Tomas Benitez",
              studentDni: "44555666",
              installments: [],
              source: "ADMIN_MANUAL",
              manualReason: "Efectivo",
            },
          ],
        }),
      ),
    );

    renderWithProviders(<AdminUserDetailPage userId={12} />, "ROLE_ADMIN");

    expect(await screen.findByText("Pago #502")).toBeInTheDocument();
    expect(screen.getByText("Imputación manual")).toBeInTheDocument();
    // Motivo una sola vez aunque adminObservation traiga el mismo texto.
    expect(screen.getAllByText(/Efectivo/)).toHaveLength(1);
    // Sin fila de cuenta para manuales.
    expect(screen.queryByText("Cuenta acreditada")).not.toBeInTheDocument();
    expect(screen.queryByText("Observación:")).not.toBeInTheDocument();
  });

  it("preserves the approved quote calendar date independently of the original quote and payment dates", async () => {
    server.use(http.get("http://localhost:30002/api/v1/users/admin/12/detail", () => HttpResponse.json({
      id: 12, email: "clara@test.com", name: "Clara", lastname: "Benitez", dni: "33444555",
      phone: "1133344455", role: "USER", students: [], installments: [], payments: [{
        submissionId: 504, status: "APPROVED", reportedAmount: "153000.00", approvedAmount: "154000.00",
        rejectedAmount: "0.00", paymentCurrency: "ARS", exchangeRate: "1530.00000000",
        amountInTripCurrency: "100.00", approvedAmountInTripCurrency: "100.00", reportedPaymentDate: "2026-09-03",
        quoteRequestedDate: "2026-09-03", quoteEffectiveDate: "2026-09-01",
        approvedCurrency: "ARS", approvedExchangeRate: "1540.00000000", approvedQuoteRequestedDate: "2026-09-03",
        approvedQuoteEffectiveDate: "2026-09-02", approvedQuoteSource: null, approvedQuoteProvider: null,
        approvedQuoteProviderTimestamp: "2026-09-02T15:00:00Z", approvedCalculationVersion: "2", rejectedCurrency: null,
        paymentMethod: "CASH", fileKey: "", adminObservation: null, bankAccountId: null,
        bankAccountDisplayName: null, bankAccountAlias: null, tripId: 9, tripName: "Trip", tripCurrency: "USD",
        studentId: null, studentName: null, studentDni: null, installments: [], source: "CUSTOMER_SUBMISSION",
      }],
    })));

    renderWithProviders(<AdminUserDetailPage userId={12} />, "ROLE_ADMIN");
    const paymentHeading = await screen.findByText("Pago #504");
    const paymentCard = within(paymentHeading.closest("article")!);

    expect(paymentCard.getByText(/Cotización de la aprobación:/)).toHaveTextContent(
      "Cotización de la aprobación: 1540.00000000 ARS por USD · 02/09/2026",
    );
    expect(paymentCard.getByText("03/09/2026 · Sin imputación visible")).toBeInTheDocument();
  });

  it("labels original ARS and administrative USD amounts independently", async () => {
    server.use(http.get("http://localhost:30002/api/v1/users/admin/12/detail", () => HttpResponse.json({
      id: 12, email: "clara@test.com", name: "Clara", lastname: "Benitez", dni: "33444555",
      phone: "1133344455", role: "USER", students: [], installments: [], payments: [{
        submissionId: 503, status: "PARTIALLY_APPROVED", reportedAmount: "306000.00", approvedAmount: "150.00",
        rejectedAmount: "76500.00", paymentCurrency: "ARS", exchangeRate: "1530.00000000",
        amountInTripCurrency: "200.00", approvedAmountInTripCurrency: "150.00", reportedPaymentDate: "2026-09-03",
        approvedCurrency: "USD", approvedExchangeRate: null, approvedQuoteRequestedDate: null,
        approvedQuoteEffectiveDate: null, approvedQuoteSource: null, approvedQuoteProvider: null,
        approvedQuoteProviderTimestamp: null, approvedCalculationVersion: "2", rejectedCurrency: "ARS",
        paymentMethod: "CASH", fileKey: "", adminObservation: "Confirmed credit", bankAccountId: null,
        bankAccountDisplayName: null, bankAccountAlias: null, tripId: 9, tripName: "Trip", tripCurrency: "USD",
        studentId: null, studentName: null, studentDni: null, installments: [], source: "CUSTOMER_SUBMISSION",
      }],
    })));
    renderWithProviders(<AdminUserDetailPage userId={12} />, "ROLE_ADMIN");
    await screen.findByText("Pago #503");
    expect(screen.getByText("Monto informado").nextElementSibling).toHaveTextContent("306.000,00");
    expect(screen.getByText("Monto informado").nextElementSibling).not.toHaveTextContent("US$");
    expect(screen.getByText("Aprobado", { selector: "span" }).nextElementSibling).toHaveTextContent("US$");
    expect(screen.getByText("Imputado al viaje").nextElementSibling).toHaveTextContent("US$");
    expect(screen.getByText(/Rechazado:/)).toHaveTextContent("76.500,00");
    expect(screen.queryByText(/Cotización de la aprobación:/)).not.toBeInTheDocument();
  });
});
