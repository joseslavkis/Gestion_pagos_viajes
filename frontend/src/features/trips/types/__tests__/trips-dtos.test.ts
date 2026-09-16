import { describe, expect, it } from "vitest";

import {
  SpreadsheetDTOSchema,
  SpreadsheetRowInstallmentDTOSchema,
  TripDetailDTOSchema,
} from "@/features/trips/types/trips-dtos";

describe("trips-dtos schemas (rollout compatibility)", () => {
  describe("TripDetailDTOSchema", () => {
    const baseDetail = {
      id: 1,
      name: "Viaje test",
      totalAmount: 1000,
      firstInstallmentAmount: 100,
      currency: "ARS",
      installmentsCount: 10,
      dueDay: 5,
      yellowWarningDays: 3,
      retroactiveActive: false,
      firstDueDate: "2026-01-01",
      assignedUsersCount: 0,
      assignedParticipantsCount: 0,
    };

    it("parsea una respuesta válida sin campos de multa", () => {
      const parsed = TripDetailDTOSchema.parse(baseDetail);
      expect(parsed.id).toBe(1);
      expect(parsed.name).toBe("Viaje test");
    });

    it("acepta claves legacy (`fixedFineAmount`) sin incluirlas en el dominio parseado", () => {
      // Zod objects strip unknown keys by default. A backend that still echoes
      // the retired `fixedFineAmount` (e.g. the temporary compatibility shim)
      // must not break parsing nor leak the key into the parsed domain.
      const parsed = TripDetailDTOSchema.parse({
        ...baseDetail,
        fixedFineAmount: 0,
      });
      expect(parsed).not.toHaveProperty("fixedFineAmount");
    });

    it("acepta claves legacy con valores distintos sin incluirlas en el dominio parseado", () => {
      const parsed = TripDetailDTOSchema.parse({
        ...baseDetail,
        fixedFineAmount: 12345,
      });
      expect(parsed).not.toHaveProperty("fixedFineAmount");
    });
  });

  describe("SpreadsheetDTOSchema", () => {
    const baseInstallment = {
      id: 101,
      installmentNumber: 1,
      dueDate: "2026-01-01",
      capitalAmount: 100,
      retroactiveAmount: 0,
      totalDue: 100,
      paidAmount: 100,
      status: "GREEN" as const,
      uiStatusCode: "PAID" as const,
      uiStatusLabel: "Pagada",
      uiStatusTone: "green" as const,
    };

    const baseRow = {
      userId: 1,
      studentId: 10,
      name: "Juan",
      lastname: "García",
      phone: null,
      email: "juan@example.com",
      studentLastname: "Perez",
      studentName: "Luca",
      studentDni: null,
      userCompleted: false,
      installments: [baseInstallment],
    };

    const baseSpreadsheet = {
      tripName: "Viaje 1",
      installmentsCount: 2,
      page: 0,
      totalElements: 1,
      rows: [baseRow],
    };

    it("parsea un spreadsheet válido sin campos de multa", () => {
      const parsed = SpreadsheetDTOSchema.parse(baseSpreadsheet);
      expect(parsed.tripName).toBe("Viaje 1");
      expect(parsed.rows).toHaveLength(1);
    });

    it("acepta claves legacy (`fineAmount`) en cada installment sin incluirlas en el dominio parseado", () => {
      const parsed = SpreadsheetDTOSchema.parse({
        ...baseSpreadsheet,
        rows: [
          {
            ...baseRow,
            installments: [
              {
                ...baseInstallment,
                fineAmount: 0,
              },
            ],
          },
        ],
      });
      const installment = parsed.rows[0].installments[0] as unknown as Record<string, unknown>;
      expect(installment).not.toHaveProperty("fineAmount");
    });

    it("acepta claves legacy con valores distintos sin incluirlas en el dominio parseado", () => {
      const parsed = SpreadsheetDTOSchema.parse({
        ...baseSpreadsheet,
        rows: [
          {
            ...baseRow,
            installments: [
              {
                ...baseInstallment,
                fineAmount: 12.5,
              },
            ],
          },
        ],
      });
      const installment = parsed.rows[0].installments[0] as unknown as Record<string, unknown>;
      expect(installment).not.toHaveProperty("fineAmount");
    });
  });

  describe("SpreadsheetRowInstallmentDTOSchema", () => {
    const baseInstallment = {
      id: 1,
      installmentNumber: 1,
      dueDate: "2026-01-01",
      capitalAmount: 100,
      retroactiveAmount: 0,
      totalDue: 100,
      paidAmount: 0,
      status: "YELLOW" as const,
      uiStatusCode: "UP_TO_DATE" as const,
      uiStatusLabel: "Al día",
      uiStatusTone: "green" as const,
    };

    it("ignora claves legacy `fineAmount` incluso si la API las devuelve", () => {
      const parsed = SpreadsheetRowInstallmentDTOSchema.parse({
        ...baseInstallment,
        fineAmount: 0,
      }) as unknown as Record<string, unknown>;
      expect(parsed).not.toHaveProperty("fineAmount");
    });
  });
});
