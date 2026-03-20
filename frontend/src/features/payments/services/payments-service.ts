import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";

import type {
  PaymentReceiptDTO,
  RegisterPaymentDTO,
  ReviewPaymentDTO,
} from "@/features/payments/types/payments-dtos";
import { PaymentReceiptDTOSchema } from "@/features/payments/types/payments-dtos";
import { ApiError } from "@/lib/api-error";
import { apiGet, apiPatch, apiPost } from "@/lib/api-client";
import { useToken } from "@/lib/session";

export function useRegisterPayment() {
  const [tokenState] = useToken();
  const queryClient = useQueryClient();

  return useMutation<PaymentReceiptDTO, ApiError, RegisterPaymentDTO>({
    mutationFn: async (payload) =>
      apiPost("/api/v1/payments", payload, (json) => PaymentReceiptDTOSchema.parse(json), {
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
        queryClient.invalidateQueries({ queryKey: ["installments", variables.installmentId] }),
        queryClient.invalidateQueries({ queryKey: ["spreadsheet"] }),
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
