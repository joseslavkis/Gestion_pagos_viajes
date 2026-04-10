import { screen } from "@testing-library/react";
import { describe, expect, it, vi } from "vitest";

import { PaymentDrawer } from "@/features/payments/components/PaymentDrawer";
import styles from "@/features/trips/pages/SpreadsheetPage.module.css";
import { renderWithProviders } from "@/test/test-utils";

vi.mock("@/features/payments/services/payments-service", () => ({
  useInstallmentReceipts: () => ({
    data: [],
    isLoading: false,
    error: null,
  }),
  useVoidPayment: () => ({
    mutateAsync: vi.fn(),
    isPending: false,
  }),
}));

describe("PaymentDrawer", () => {
  it("muestra alumno primero y usa color neutral para Al día", async () => {
    renderWithProviders(
      <PaymentDrawer
        onClose={vi.fn()}
        row={{
          userId: 10,
          studentId: 20,
          name: "Ana",
          lastname: "Parent",
          phone: "381123123",
          email: "ana@test.com",
          studentLastname: "Perez",
          studentName: "Luca",
          studentDni: "40111222",
          userCompleted: false,
          installments: [],
        }}
        installment={{
          id: 1,
          installmentNumber: 1,
          dueDate: "2026-05-10",
          capitalAmount: 1000,
          retroactiveAmount: 0,
          fineAmount: 0,
          totalDue: 1000,
          paidAmount: 0,
          status: "YELLOW",
          uiStatusCode: "UP_TO_DATE",
          uiStatusLabel: "Al día",
          uiStatusTone: "green",
        }}
      />,
      "ROLE_ADMIN",
    );

    expect(await screen.findByText("Perez, Luca")).toBeInTheDocument();
    expect(screen.getByText("Responsable: Parent, Ana")).toBeInTheDocument();
    expect(screen.getByText("ana@test.com")).toBeInTheDocument();

    const badge = screen.getByText("Al día").parentElement;
    expect(badge).toHaveClass(styles.statusNeutral);
  });
});
