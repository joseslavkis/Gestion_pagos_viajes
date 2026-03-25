// @vitest-environment jsdom
import { describe, expect, it, vi, beforeEach, afterEach } from "vitest";
import { renderHook, waitFor } from "@testing-library/react";
import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import type { ReactNode } from "react";

import {
  useAssignUsersBulk,
  useCreateTrip,
  useDeleteTrip,
  useSpreadsheet,
  useTrips,
  useUpdateTrip,
} from "@/features/trips/services/trips-service";
import type { SpreadsheetParams, TripCreateDTO } from "@/features/trips/types/trips-dtos";
import { ApiError } from "@/lib/api-error";

vi.mock("@/lib/session", () => ({
  useToken: () => [
    {
      state: "LOGGED_IN" as const,
      accessToken: "test-access-token",
      refreshToken: null,
    },
    vi.fn(),
  ],
}));

const queryClient = new QueryClient({
  defaultOptions: {
    queries: { retry: false },
    mutations: { retry: false },
  },
});

const wrapper = ({ children }: { children: ReactNode }) => (
  <QueryClientProvider client={queryClient}>{children}</QueryClientProvider>
);

describe("trips-service hooks", () => {
  let originalFetch: typeof globalThis.fetch;

  beforeEach(() => {
    originalFetch = globalThis.fetch;
  });

  afterEach(() => {
    globalThis.fetch = originalFetch;
    vi.restoreAllMocks();
    queryClient.clear();
  });

  describe("useTrips", () => {
    it("devuelve lista de viajes cuando la API responde 200", async () => {
      const trips = [
        {
          id: 1,
          name: "Viaje 1",
          totalAmount: 1000,
          currency: "ARS",
          installmentsCount: 10,
          assignedUsersCount: 0,
          assignedParticipantsCount: 0,
        },
      ];

      globalThis.fetch = vi.fn().mockResolvedValue(
        new Response(JSON.stringify(trips), {
          status: 200,
          headers: { "Content-Type": "application/json" },
        }),
      );

      const { result } = renderHook(() => useTrips(), { wrapper });

      await waitFor(() => expect(result.current.isSuccess).toBe(true));
      expect(result.current.data).toHaveLength(1);
      expect(result.current.data?.[0].name).toBe("Viaje 1");
    });

    it("retorna error ApiError cuando la API responde 401", async () => {
      globalThis.fetch = vi.fn().mockResolvedValue(
        new Response(JSON.stringify({ message: "Unauthorized" }), {
          status: 401,
          headers: { "Content-Type": "application/json" },
        }),
      );

      const { result } = renderHook(() => useTrips(), { wrapper });

      await waitFor(() => expect(result.current.isError).toBe(true));
      expect(result.current.error).toBeInstanceOf(ApiError);
      expect((result.current.error as ApiError).status).toBe(401);
    });

    it("retorna error ApiError cuando la API responde 500", async () => {
      globalThis.fetch = vi.fn().mockResolvedValue(
        new Response(JSON.stringify({ message: "Server error" }), {
          status: 500,
          headers: { "Content-Type": "application/json" },
        }),
      );

      const { result } = renderHook(() => useTrips(), { wrapper });

      await waitFor(() => expect(result.current.isError).toBe(true));
      expect(result.current.error).toBeInstanceOf(ApiError);
      expect((result.current.error as ApiError).status).toBe(500);
    });
  });

  describe("useCreateTrip", () => {
    const baseTrip: TripCreateDTO = {
      name: "Viaje test",
      totalAmount: 1000,
      installmentsCount: 10,
      dueDay: 5,
      yellowWarningDays: 3,
      fixedFineAmount: 0,
      retroactiveActive: false,
      firstDueDate: "2026-01-01",
    };

    it("en onSuccess invalida el queryKey ['trips']", async () => {
      const invalidateSpy = vi.spyOn(queryClient, "invalidateQueries");

      globalThis.fetch = vi.fn().mockResolvedValue(
        new Response(
          JSON.stringify({
            ...baseTrip,
            currency: "ARS",
            id: 1,
            assignedUsersCount: 0,
            assignedParticipantsCount: 0,
          }),
          {
            status: 201,
            headers: { "Content-Type": "application/json" },
          },
        ),
      );

      const { result } = renderHook(() => useCreateTrip(), { wrapper });

      result.current.mutate(baseTrip);

      await waitFor(() => expect(result.current.isSuccess).toBe(true));
      expect(invalidateSpy).toHaveBeenCalledWith({ queryKey: ["trips"] });
    });

    it("en caso de 409 lanza ApiError con status 409", async () => {
      globalThis.fetch = vi.fn().mockResolvedValue(
        new Response(JSON.stringify({ message: "Conflict" }), {
          status: 409,
          headers: { "Content-Type": "application/json" },
        }),
      );

      const { result } = renderHook(() => useCreateTrip(), { wrapper });

      result.current.mutate(baseTrip);

      await waitFor(() => expect(result.current.isError).toBe(true));
      const error = result.current.error as ApiError;
      expect(error).toBeInstanceOf(ApiError);
      expect(error.status).toBe(409);
    });

    it("en caso de 400 lanza ApiError con status 400 y el mensaje de validación", async () => {
      globalThis.fetch = vi.fn().mockResolvedValue(
        new Response(JSON.stringify({ errors: ["name: size must be between 2 and 100"] }), {
          status: 400,
          headers: { "Content-Type": "application/json" },
        }),
      );

      const { result } = renderHook(() => useCreateTrip(), { wrapper });

      result.current.mutate(baseTrip);

      await waitFor(() => expect(result.current.isError).toBe(true));
      const error = result.current.error as ApiError;
      expect(error).toBeInstanceOf(ApiError);
      expect(error.status).toBe(400);
      expect(error.message).toBe("name: size must be between 2 and 100");
    });
  });

  describe("useUpdateTrip", () => {
    it("en onSuccess invalida queryKey ['trips'] y ['trips', id]", async () => {
      const invalidateSpy = vi.spyOn(queryClient, "invalidateQueries");

      globalThis.fetch = vi.fn().mockResolvedValue(
        new Response(
          JSON.stringify({
            id: 3,
            name: "Nuevo nombre",
            totalAmount: 500,
            currency: "ARS",
            installmentsCount: 5,
            dueDay: 10,
            yellowWarningDays: 3,
            fixedFineAmount: 100,
            retroactiveActive: false,
            firstDueDate: "2027-01-01",
            assignedUsersCount: 2,
            assignedParticipantsCount: 2,
          }),
          {
            status: 200,
            headers: { "Content-Type": "application/json" },
          },
        ),
      );

      const { result } = renderHook(() => useUpdateTrip(), { wrapper });

      result.current.mutate({ id: 3, data: { name: "Nuevo nombre" } });

      await waitFor(() => expect(result.current.isSuccess).toBe(true));
      expect(invalidateSpy).toHaveBeenCalledWith({ queryKey: ["trips"] });
      expect(invalidateSpy).toHaveBeenCalledWith({ queryKey: ["trips", 3] });
    });

    it("en caso de 409 lanza ApiError con status 409", async () => {
      globalThis.fetch = vi.fn().mockResolvedValue(
        new Response(JSON.stringify({ message: "Conflict" }), {
          status: 409,
          headers: { "Content-Type": "application/json" },
        }),
      );

      const { result } = renderHook(() => useUpdateTrip(), { wrapper });

      result.current.mutate({ id: 3, data: { name: "x" } });

      await waitFor(() => expect(result.current.isError).toBe(true));
      expect(result.current.error).toBeInstanceOf(ApiError);
      expect((result.current.error as ApiError).status).toBe(409);
    });

    it("en caso de 200 devuelve el TripDetailDTO actualizado", async () => {
      globalThis.fetch = vi.fn().mockResolvedValue(
        new Response(
          JSON.stringify({
            id: 3,
            name: "Nombre actualizado",
            totalAmount: 500,
            currency: "ARS",
            installmentsCount: 5,
            dueDay: 10,
            yellowWarningDays: 3,
            fixedFineAmount: 100,
            retroactiveActive: false,
            firstDueDate: "2027-01-01",
            assignedUsersCount: 2,
            assignedParticipantsCount: 2,
          }),
          {
            status: 200,
            headers: { "Content-Type": "application/json" },
          },
        ),
      );

      const { result } = renderHook(() => useUpdateTrip(), { wrapper });

      result.current.mutate({ id: 3, data: { name: "Nombre actualizado" } });

      await waitFor(() => expect(result.current.isSuccess).toBe(true));
      expect(result.current.data?.name).toBe("Nombre actualizado");
      expect(result.current.data?.id).toBe(3);
    });
  });

  describe("useDeleteTrip", () => {
    it("en onSuccess invalida el queryKey ['trips']", async () => {
      const invalidateSpy = vi.spyOn(queryClient, "invalidateQueries");

      globalThis.fetch = vi.fn().mockResolvedValue(
        new Response(JSON.stringify({ status: "OK", message: "Deleted" }), {
          status: 200,
          headers: { "Content-Type": "application/json" },
        }),
      );

      const { result } = renderHook(() => useDeleteTrip(), { wrapper });

      result.current.mutate({ id: 1 });

      await waitFor(() => expect(result.current.isSuccess).toBe(true));
      expect(invalidateSpy).toHaveBeenCalledWith({ queryKey: ["trips"] });
    });

    it("en caso de 409 (viaje con usuarios) lanza ApiError con status 409", async () => {
      globalThis.fetch = vi.fn().mockResolvedValue(
        new Response(JSON.stringify({ message: "Trip has users" }), {
          status: 409,
          headers: { "Content-Type": "application/json" },
        }),
      );

      const { result } = renderHook(() => useDeleteTrip(), { wrapper });

      result.current.mutate({ id: 1 });

      await waitFor(() => expect(result.current.isError).toBe(true));
      const error = result.current.error as ApiError;
      expect(error).toBeInstanceOf(ApiError);
      expect(error.status).toBe(409);
    });
  });

  describe("useAssignUsersBulk", () => {
    it("llama al endpoint correcto con el body { studentDnis }", async () => {
      const fetchSpy = vi
        .spyOn(globalThis, "fetch")
        .mockResolvedValue(
          new Response(JSON.stringify({ status: "OK", message: "assigned", assignedCount: 2 }), {
            status: 200,
            headers: { "Content-Type": "application/json" },
          }),
        );

      const { result } = renderHook(() => useAssignUsersBulk(), { wrapper });

      result.current.mutate({ id: 5, data: { studentDnis: ["45678901", "45678902"] } });

      await waitFor(() => expect(result.current.isSuccess).toBe(true));

      expect(fetchSpy).toHaveBeenCalledTimes(1);
      const [url, init] = fetchSpy.mock.calls[0] as [string, RequestInit];
      expect(url).toContain("/api/v1/trips/5/users/bulk");
      expect(init.method).toBe("POST");
      expect(init.body).toBeDefined();
      const parsedBody = JSON.parse(init.body as string) as { studentDnis: string[] };
      expect(parsedBody.studentDnis).toEqual(["45678901", "45678902"]);
    });

    it("en onSuccess invalida el queryKey ['trips', id]", async () => {
      const invalidateSpy = vi.spyOn(queryClient, "invalidateQueries");

      globalThis.fetch = vi.fn().mockResolvedValue(
        new Response(JSON.stringify({ status: "OK", message: "assigned", assignedCount: 2 }), {
          status: 200,
          headers: { "Content-Type": "application/json" },
        }),
      );

      const { result } = renderHook(() => useAssignUsersBulk(), { wrapper });

      result.current.mutate({ id: 7, data: { studentDnis: ["45678901", "45678902"] } });

      await waitFor(() => expect(result.current.isSuccess).toBe(true));
      expect(invalidateSpy).toHaveBeenCalledWith({ queryKey: ["trips", 7] });
    });

    it("maneja correctamente el caso donde assignedCount === 0", async () => {
      globalThis.fetch = vi.fn().mockResolvedValue(
        new Response(JSON.stringify({ status: "OK", message: "no users assigned", assignedCount: 0 }), {
          status: 200,
          headers: { "Content-Type": "application/json" },
        }),
      );

      const { result } = renderHook(() => useAssignUsersBulk(), { wrapper });

      result.current.mutate({ id: 3, data: { studentDnis: ["45678901"] } });

      await waitFor(() => expect(result.current.isSuccess).toBe(true));
      expect(result.current.data?.assignedCount).toBe(0);
    });
  });

  describe("useSpreadsheet", () => {
    const baseParams: SpreadsheetParams = {
      page: 2,
      size: 50,
      search: "garcia",
      sortBy: "lastname",
      order: "asc",
      status: "RED",
    };

    it("construye la URL con los params correctos (page, size, search, status)", async () => {
      const fetchSpy = vi
        .spyOn(globalThis, "fetch")
        .mockResolvedValue(
          new Response(
            JSON.stringify({
              tripName: "Viaje 1",
              installmentsCount: 3,
              page: 2,
              totalElements: 0,
              rows: [],
            }),
            {
              status: 200,
              headers: { "Content-Type": "application/json" },
            },
          ),
        );

      const { result } = renderHook(() => useSpreadsheet(10, baseParams), { wrapper });

      await waitFor(() => expect(result.current.isSuccess).toBe(true));

      expect(fetchSpy).toHaveBeenCalledTimes(1);
      const [url] = fetchSpy.mock.calls[0] as [string, RequestInit];
      expect(url).toContain("/api/v1/trips/10/spreadsheet");
      expect(url).toContain("page=2");
      expect(url).toContain("size=50");
      expect(url).toContain("search=garcia");
      expect(url).toContain("status=RED");
    });

    it("omite params undefined o vacíos de la query string", async () => {
      const fetchSpy = vi
        .spyOn(globalThis, "fetch")
        .mockResolvedValue(
          new Response(
            JSON.stringify({
              tripName: "Viaje 1",
              installmentsCount: 1,
              page: 0,
              totalElements: 0,
              rows: [],
            }),
            {
              status: 200,
              headers: { "Content-Type": "application/json" },
            },
          ),
        );

      const paramsWithoutOptional: SpreadsheetParams = {
        page: 0,
        size: 20,
        search: undefined,
        sortBy: "lastname",
        order: "asc",
        status: "",
      };

      const { result } = renderHook(() => useSpreadsheet(5, paramsWithoutOptional), { wrapper });

      await waitFor(() => expect(result.current.isSuccess).toBe(true));

      const [url] = fetchSpy.mock.calls[0] as [string, RequestInit];
      expect(url).toContain("page=0");
      expect(url).toContain("size=20");
      expect(url).not.toContain("search=");
      expect(url).not.toContain("status=");
    });

    it("retorna SpreadsheetDTO correctamente parseado cuando la API responde 200", async () => {
      const payload = {
        tripName: "Viaje 1",
        installmentsCount: 2,
        page: 0,
        totalElements: 1,
        rows: [
          {
            userId: 1,
            studentId: 10,
            name: "Juan",
            lastname: "García",
            phone: null,
            email: "juan@example.com",
            studentName: null,
            studentDni: null,
            schoolName: null,
            courseName: null,
            userCompleted: false,
            installments: [
              {
                id: 101,
                installmentNumber: 1,
                dueDate: "2026-01-01",
                capitalAmount: 100,
                retroactiveAmount: 0,
                fineAmount: 0,
                totalDue: 100,
                paidAmount: 100,
                status: "GREEN",
                uiStatusCode: "PAID",
                uiStatusLabel: "Pagada",
                uiStatusTone: "green",
              },
            ],
          },
        ],
      };

      globalThis.fetch = vi.fn().mockResolvedValue(
        new Response(JSON.stringify(payload), {
          status: 200,
          headers: { "Content-Type": "application/json" },
        }),
      );

      const { result } = renderHook(() => useSpreadsheet(1, baseParams), { wrapper });

      await waitFor(() => expect(result.current.isSuccess).toBe(true));
      expect(result.current.data?.tripName).toBe("Viaje 1");
      expect(result.current.data?.rows[0].lastname).toBe("García");
      expect(result.current.data?.rows[0].installments[0].status).toBe("GREEN");
    });

    it("retorna ApiError cuando la API responde 401", async () => {
      globalThis.fetch = vi.fn().mockResolvedValue(
        new Response(JSON.stringify({ message: "Unauthorized" }), {
          status: 401,
          headers: { "Content-Type": "application/json" },
        }),
      );

      const { result } = renderHook(() => useSpreadsheet(1, baseParams), { wrapper });

      await waitFor(() => expect(result.current.isError).toBe(true));
      expect(result.current.error).toBeInstanceOf(ApiError);
      expect((result.current.error as ApiError).status).toBe(401);
    });

    it("no ejecuta la query si tripId no está disponible (enabled: false)", async () => {
      const fetchSpy = vi.spyOn(globalThis, "fetch");

      const { result } = renderHook(() => useSpreadsheet(0, baseParams), { wrapper });

      expect(result.current.fetchStatus).toBe("idle");
      expect(result.current.isLoading).toBe(false);
      expect(fetchSpy).not.toHaveBeenCalled();
    });

    it("incluye el header Authorization en el request", async () => {
      const fetchSpy = vi
        .spyOn(globalThis, "fetch")
        .mockResolvedValue(
          new Response(
            JSON.stringify({
              tripName: "Viaje 1",
              installmentsCount: 1,
              page: 0,
              totalElements: 0,
              rows: [],
            }),
            {
              status: 200,
              headers: { "Content-Type": "application/json" },
            },
          ),
        );

      const { result } = renderHook(() => useSpreadsheet(3, baseParams), { wrapper });

      await waitFor(() => expect(result.current.isSuccess).toBe(true));

      const [, init] = fetchSpy.mock.calls[0] as [string, RequestInit];
      expect((init.headers as Record<string, string>).Authorization).toBe("Bearer test-access-token");
    });
  });
});
