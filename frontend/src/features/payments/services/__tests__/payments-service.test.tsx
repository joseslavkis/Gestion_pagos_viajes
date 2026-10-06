import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { act, renderHook, waitFor } from "@testing-library/react";
import { HttpResponse, http } from "msw";
import type { ReactNode } from "react";
import { afterEach, describe, expect, it, vi } from "vitest";

import {
  usePaymentCalculation,
  useRegisterPayment,
  useReviewPayment,
} from "@/features/payments/services/payments-service";
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
  anchorRemainingAmount: "0.01",
  totalPendingAmountInTripCurrency: "0.01",
  maxAllowedAmount: "15.23",
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

const pendingSubmission = {
  approvedCurrency: null, approvedExchangeRate: null, approvedQuoteRequestedDate: null,
  approvedQuoteEffectiveDate: null, approvedQuoteSource: null, approvedQuoteProvider: null,
  approvedQuoteProviderTimestamp: null, approvedCalculationVersion: null, rejectedCurrency: null,
  submissionId: 7,
  status: "PENDING",
  reportedAmount: "500.00",
  approvedAmount: "0.00",
  rejectedAmount: "0.00",
  paymentCurrency: "ARS",
  exchangeRate: null,
  amountInTripCurrency: "500.00",
  approvedAmountInTripCurrency: "0.00",
  reportedPaymentDate: "2026-09-18",
  paymentMethod: "BANK_TRANSFER",
  fileKey: "receipt",
  adminObservation: null,
  bankAccountId: 1,
  bankAccountDisplayName: "Account",
  bankAccountAlias: "ACCOUNT",
  tripId: 1,
  tripName: "Trip",
  tripCurrency: "ARS",
  studentId: null,
  studentName: null,
  studentDni: null,
  installments: [],
  source: "CUSTOMER_SUBMISSION",
};

describe("payments-service", () => {
  afterEach(() => {
    vi.restoreAllMocks();
  });

  it("sends the canonical amount and current preview token in multipart registration", async () => {
    let fields: FormData | null = null;
    server.use(
      http.post("http://localhost:30002/api/v1/payments", async ({ request }) => {
        fields = await request.formData();
        return HttpResponse.json(pendingSubmission, { status: 201 });
      }),
    );

    const { result } = renderHook(useRegisterPayment, { wrapper: createWrapper() });
    await act(async () => {
      await result.current.mutateAsync({
        anchorInstallmentId: 1,
        reportedAmount: "500.00",
        reportedPaymentDate: "2026-09-18",
        paymentCurrency: "ARS",
        paymentMethod: "BANK_TRANSFER",
        bankAccountId: 1,
        previewToken: "current-preview-token",
      });
    });

    expect(fields).not.toBeNull();
    expect((fields as FormData | null)?.get("reportedAmount")).toBe("500.00");
    expect((fields as FormData | null)?.get("previewToken")).toBe("current-preview-token");
    await waitFor(() => expect(result.current.data?.reportedAmount).toBe("500.00"));
  });

  it.each([1, 2, 5])("sends %i files once in selection order without changing payment fields", async (count) => {
    let fields: FormData | undefined;
    let request: RequestInit | undefined;
    const fetchSpy = vi.spyOn(globalThis, "fetch").mockImplementation(async (_url, init) => {
      request = init;
      fields = init?.body as FormData;
      return new Response(JSON.stringify({ ...pendingSubmission, fileKeys: ["first", "second"] }), {
        status: 201,
        headers: { "Content-Type": "application/json" },
      });
    });
    const files = [
      new File(["a"], "first.png", { type: "image/png" }),
      new File(["b"], "second.pdf", { type: "application/pdf" }),
      ...Array.from({ length: 3 }, (_, index) => new File([String(index)], `extra-${index}.png`, { type: "image/png" })),
    ].slice(0, count);
    const { result } = renderHook(useRegisterPayment, { wrapper: createWrapper() });
    await act(async () => {
      await result.current.mutateAsync({
        anchorInstallmentId: 1,
        reportedAmount: "500.00",
        reportedPaymentDate: "2026-09-18",
        paymentCurrency: "ARS",
        paymentMethod: "BANK_TRANSFER",
        bankAccountId: 1,
        previewToken: "current-preview-token",
        files,
      });
    });
    expect(fetchSpy).toHaveBeenCalledTimes(1);
    expect(fetchSpy).toHaveBeenCalledWith("http://localhost:30002/api/v1/payments", expect.anything());
    expect(request?.method).toBe("POST");
    expect(request?.headers).toEqual({ Authorization: "Bearer test-access-token" });
    expect(fields).toBeInstanceOf(FormData);
    expect(fields?.has("file")).toBe(false);
    expect(fields?.getAll("file")).toEqual([]);
    expect(fields?.getAll("files")).toHaveLength(count);
    files.forEach((file, index) => expect(fields?.getAll("files")[index]).toBe(file));
    expect(Array.from(fields!.entries()).filter(([, value]) => typeof value !== "string")).toHaveLength(count);
    expect(Object.fromEntries(Array.from(fields!.entries()).filter(([, value]) => typeof value === "string"))).toEqual({
      anchorInstallmentId: "1", reportedAmount: "500.00", reportedPaymentDate: "2026-09-18",
      paymentCurrency: "ARS", paymentMethod: "BANK_TRANSFER", bankAccountId: "1", previewToken: "current-preview-token",
    });
    await waitFor(() => expect(result.current.data?.fileKeys).toEqual(["first", "second"]));
  });

  it("sends the exact admin approval decimal without rounding or fallback", async () => {
    let capturedBody: unknown;
    server.use(
      http.patch("http://localhost:30002/api/v1/payments/7/review", async ({ request }) => {
        capturedBody = await request.json();
        return HttpResponse.json({ ...pendingSubmission, status: "PARTIALLY_APPROVED", approvedAmount: "150.00",
          approvedCurrency: "USD", approvedExchangeRate: "1.00", approvedQuoteRequestedDate: "2026-09-18",
          approvedQuoteEffectiveDate: "2026-09-18", approvedQuoteSource: "test", approvedQuoteProvider: "test-provider",
          approvedCalculationVersion: "2", approvedAmountInTripCurrency: "150.00", rejectedAmount: "350.00", rejectedCurrency: "ARS" });
      }),
    );

    const { result } = renderHook(useReviewPayment, { wrapper: createWrapper() });
    await act(async () => {
      await result.current.mutateAsync({
        id: 7,
        data: { approvedAmount: "150.00", approvedCurrency: "USD", adminObservation: "Confirmed USD credit" },
      });
    });

    expect(capturedBody).toEqual({ approvedAmount: "150.00", approvedCurrency: "USD", adminObservation: "Confirmed USD credit" });
    await waitFor(() => expect(result.current.data?.approvedAmount).toBe("150.00"));
    expect(result.current.data?.paymentCurrency).toBe("ARS");
    expect(result.current.data?.approvedCurrency).toBe("USD");
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
