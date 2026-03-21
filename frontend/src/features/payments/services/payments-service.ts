import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";

import type {
  PaymentReceiptDTO,
  RegisterPaymentFormData,
  ReviewPaymentDTO,
  UserInstallmentDTO,
} from "@/features/payments/types/payments-dtos";
import {
  PaymentReceiptDTOSchema,
  UserInstallmentDTOSchema,
} from "@/features/payments/types/payments-dtos";
import { ApiError, handleApiResponse } from "@/lib/api-error";
import { BASE_API_URL, apiGet, apiPatch, apiPost } from "@/lib/api-client";
import { useToken } from "@/lib/session";

export function useRegisterPayment() {
  const [tokenState] = useToken();
  const queryClient = useQueryClient();

  return useMutation<PaymentReceiptDTO, ApiError, RegisterPaymentFormData>({
    mutationFn: async (payload) => {
      const formData = new FormData();
      formData.append("installmentId", String(payload.installmentId));
      formData.append("reportedAmount", String(payload.reportedAmount));
      formData.append("reportedPaymentDate", payload.reportedPaymentDate);
      formData.append("paymentCurrency", payload.paymentCurrency);
      formData.append("paymentMethod", payload.paymentMethod);
      if (payload.file) {
        formData.append("file", payload.file);
      }

      const headers: Record<string, string> = {};
      if (tokenState.state === "LOGGED_IN") {
        headers.Authorization = `Bearer ${tokenState.accessToken}`;
      }

      const response = await fetch(`${BASE_API_URL}/api/v1/payments`, {
        method: "POST",
        headers,
        body: formData,
      });

      if (response.ok) {
        const json: unknown = await response.json();
        return PaymentReceiptDTOSchema.parse(json);
      }

      return handleApiResponse(response);
    },
    onSuccess: async (_data, variables) => {
      await Promise.all([
        queryClient.invalidateQueries({ queryKey: ["trips"] }),
        queryClient.invalidateQueries({ queryKey: ["installments", variables.installmentId] }),
        queryClient.invalidateQueries({ queryKey: ["spreadsheet"] }),
        queryClient.invalidateQueries({ queryKey: ["payments", "my", "installments"] }),
      ]);
    },
  });
}

export function useReviewPayment() {
  const [tokenState] = useToken();
  const queryClient = useQueryClient();

  return useMutation<
    PaymentReceiptDTO,
    ApiError,
    { id: number; installmentId: number; data: ReviewPaymentDTO }
  >({
    mutationFn: async ({ id, data }) =>
      apiPatch(`/api/v1/payments/${id}/review`, data, (json) => PaymentReceiptDTOSchema.parse(json), {
        headers:
          tokenState.state === "LOGGED_IN"
            ? {
                Authorization: `Bearer ${tokenState.accessToken}`,
              }
            : undefined,
      }),
    onSuccess: async (_data, variables) => {
      await Promise.all([
        queryClient.invalidateQueries({ queryKey: ["trips"] }),
        queryClient.invalidateQueries({
          queryKey: ["payments", "installment", variables.installmentId],
        }),
      ]);
    },
  });
}

export function useVoidPayment() {
  const [tokenState] = useToken();
  const queryClient = useQueryClient();

  return useMutation<PaymentReceiptDTO, ApiError, { id: number; installmentId: number }>({
    mutationFn: async ({ id }) =>
      apiPost(`/api/v1/payments/${id}/void`, {}, (json) => PaymentReceiptDTOSchema.parse(json), {
        headers:
          tokenState.state === "LOGGED_IN"
            ? {
                Authorization: `Bearer ${tokenState.accessToken}`,
              }
            : undefined,
      }),
    onSuccess: async (_data, variables) => {
      await Promise.all([
        queryClient.invalidateQueries({ queryKey: ["trips"] }),
        queryClient.invalidateQueries({
          queryKey: ["payments", "installment", variables.installmentId],
        }),
      ]);
    },
  });
}

export function useInstallmentReceipts(installmentId: number | null) {
  const [tokenState] = useToken();

  return useQuery<PaymentReceiptDTO[], ApiError>({
    queryKey: ["payments", "installment", installmentId],
    staleTime: 0,
    enabled: installmentId != null && installmentId > 0,
    queryFn: async () =>
      apiGet(
        `/api/v1/payments/installment/${installmentId}`,
        (json) => PaymentReceiptDTOSchema.array().parse(json),
        {
          headers:
            tokenState.state === "LOGGED_IN"
              ? {
                  Authorization: `Bearer ${tokenState.accessToken}`,
                }
              : undefined,
        },
      ),
  });
}

export function useMyInstallments() {
  const [tokenState] = useToken();

  return useQuery<UserInstallmentDTO[], ApiError>({
    queryKey: ["payments", "my", "installments"],
    staleTime: 0,
    queryFn: async () =>
      apiGet(
        "/api/v1/payments/my/installments",
        (json) => UserInstallmentDTOSchema.array().parse(json),
        {
          headers:
            tokenState.state === "LOGGED_IN"
              ? {
                  Authorization: `Bearer ${tokenState.accessToken}`,
                }
              : undefined,
        },
      ),
  });
}
