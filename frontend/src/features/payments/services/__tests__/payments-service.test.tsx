import type { ReactNode } from "react";

import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { renderHook, waitFor } from "@testing-library/react";
import { HttpResponse, http } from "msw";
import { afterEach, describe, expect, it, vi } from "vitest";

import { usePaymentCalculation } from "@/features/payments/services/payments-service";
import { server } from "@/test/msw-server";

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

const CALCULATION_URL = "http://localhost:30002/api/v1/payments/calculation";

function createWrapper() {
  const queryClient = new QueryClient({
    defaultOptions: {
      queries: { retry: false },
      mutations: { retry: false },
    },
  });

  return ({ children }: { children: ReactNode }) => (
    <QueryClientProvider client={queryClient}>{children}</QueryClientProvider>
  );
}

const readyCalculation = {
  status: "READY",
  intent: "REMAINING",
  anchorInstallmentId: 1,
  tripCurrency: "USD",
  paymentCurrency: "ARS",
  reportedAmount: "10.16",
  amountInTripCurrency: "0.01",
  remainingAmount: "0.01",
  maxAllowedAmount: "10.16",
  tripCurrencyResidual: "0.00",
  exchangeRate: "1015.50",
  reportedPaymentDate: "2026-09-18",
  quoteRequestedDate: "2026-09-18",
  quoteEffectiveDate: "2026-09-18",
  quoteSource: "official",
  quoteProvider: "provider-a",
  quoteProviderTimestamp: "2026-09-18T12:00:00Z",
  calculationVersion: "2",
  previewToken: "token",
  installments: [],
  message: null,
} as const;

describe("payments-service", () => {
  afterEach(() => {
    vi.restoreAllMocks();
  });

  it("posts remaining intent and preserves authoritative decimal strings", async () => {
    let capturedBody: unknown;
    let capturedAuthorization: string | null = null;
    server.use(
      http.post(CALCULATION_URL, async ({ request }) => {
        capturedBody = await request.json();
        capturedAuthorization = request.headers.get("Authorization");
        return HttpResponse.json(readyCalculation);
      }),
    );

    const { result } = renderHook(
      () =>
        usePaymentCalculation({
          anchorInstallmentId: 1,
          paymentCurrency: "ARS",
          reportedPaymentDate: "2026-09-18",
          intent: "REMAINING",
        }),
      { wrapper: createWrapper() },
    );

    await waitFor(() => expect(result.current.isSuccess).toBe(true));
    expect(capturedAuthorization).toBe("Bearer test-access-token");
    expect(capturedBody).toEqual({
      anchorInstallmentId: 1,
      paymentCurrency: "ARS",
      reportedPaymentDate: "2026-09-18",
      intent: "REMAINING",
    });
    expect(result.current.data?.reportedAmount).toBe("10.16");
    expect(result.current.data?.exchangeRate).toBe("1015.50");
    expect(result.current.data?.quoteProvider).toBe("provider-a");
  });

  it("posts manual decimal intent unchanged and parses an explicit unpayable state", async () => {
    let capturedBody: unknown;
    server.use(
      http.post(CALCULATION_URL, async ({ request }) => {
        capturedBody = await request.json();
        return HttpResponse.json({
          ...readyCalculation,
          status: "UNPAYABLE",
          intent: "MANUAL",
          paymentCurrency: "USD",
          reportedAmount: null,
          amountInTripCurrency: null,
          maxAllowedAmount: "0.00",
          tripCurrencyResidual: null,
          previewToken: null,
          message: "The balance cannot be represented in the selected currency.",
        });
      }),
    );

    const { result } = renderHook(
      () =>
        usePaymentCalculation({
          anchorInstallmentId: 1,
          paymentCurrency: "USD",
          reportedPaymentDate: "2026-09-18",
          intent: "MANUAL",
          reportedAmount: "99.29",
        }),
      { wrapper: createWrapper() },
    );

    await waitFor(() => expect(result.current.isSuccess).toBe(true));
    expect(capturedBody).toEqual({
      anchorInstallmentId: 1,
      paymentCurrency: "USD",
      reportedPaymentDate: "2026-09-18",
      intent: "MANUAL",
      reportedAmount: "99.29",
    });
    expect(result.current.data?.status).toBe("UNPAYABLE");
    expect(result.current.data?.reportedAmount).toBeNull();
    expect(result.current.data?.maxAllowedAmount).toBe("0.00");
  });
});
