import { screen } from "@testing-library/react";
import { beforeEach, describe, expect, it, vi } from "vitest";

import { SpreadsheetPage } from "@/features/trips/pages/SpreadsheetPage";
import styles from "@/features/trips/pages/SpreadsheetPage.module.css";
import { renderWithProviders } from "@/test/test-utils";

const useSpreadsheetMock = vi.fn();
const useTripMock = vi.fn();

vi.mock("wouter", async (importOriginal) => {
  const actual = await importOriginal<typeof import("wouter")>();
  return {
    ...actual,
    useLocation: () => ["/trips/1/spreadsheet", vi.fn()],
  };
});

vi.mock("@/features/trips/services/trips-service", () => ({
  downloadSpreadsheetExcel: vi.fn(),
  useSpreadsheet: (...args: unknown[]) => useSpreadsheetMock(...args),
  useTrip: (...args: unknown[]) => useTripMock(...args),
}));

vi.mock("@/features/payments/components/PaymentDrawer", () => ({
  PaymentDrawer: () => null,
}));

describe("SpreadsheetPage", () => {
  beforeEach(() => {
    useTripMock.mockReturnValue({
      data: { currency: "ARS" },
    });

    useSpreadsheetMock.mockReturnValue({
      data: {
        tripName: "Bariloche",
        installmentsCount: 1,
        page: 0,
        totalElements: 1,
        rows: [
          {
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
            installments: [
              {
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
              },
            ],
          },
        ],
      },
      isLoading: false,
      error: null,
    });
  });

  it("prioriza al alumno en Participante y pinta Al día en gris", async () => {
    renderWithProviders(<SpreadsheetPage tripId={1} />, "ROLE_ADMIN");

    expect(await screen.findByText("Perez, Luca")).toHaveClass(styles.userMain);
    expect(screen.getByText("Responsable: Parent, Ana")).toBeInTheDocument();
    expect(screen.getByText("ana@test.com")).toBeInTheDocument();
    expect(screen.getByText("DNI alumno: 40111222")).toBeInTheDocument();

    const badge = screen.getByText("Al día").parentElement;
    expect(badge).toHaveClass(styles.statusNeutral);
  });
});
