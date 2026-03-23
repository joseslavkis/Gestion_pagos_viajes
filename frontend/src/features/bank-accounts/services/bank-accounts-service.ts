import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";

import type { BankAccountDTO, BankAccountFormDTO } from "@/features/bank-accounts/types/bank-accounts-dtos";
import { BankAccountDTOSchema } from "@/features/bank-accounts/types/bank-accounts-dtos";
import type { Currency } from "@/features/payments/types/payments-dtos";
import { ApiError } from "@/lib/api-error";
import { apiGet, apiPatch, apiPost, apiPut } from "@/lib/api-client";
import { useToken } from "@/lib/session";

function getAuthHeaders(tokenState: ReturnType<typeof useToken>[0]): Record<string, string> | undefined {
  if (tokenState.state !== "LOGGED_IN") {
    return undefined;
  }

  return { Authorization: `Bearer ${tokenState.accessToken}` };
}

export function useBankAccounts(currency?: Currency) {
  const [tokenState] = useToken();

  return useQuery<BankAccountDTO[], ApiError>({
    queryKey: ["bank-accounts", currency ?? "all"],
    queryFn: async () => {
      const endpoint = currency ? `/api/v1/bank-accounts?currency=${currency}` : "/api/v1/bank-accounts";
      return apiGet(endpoint, (json) => BankAccountDTOSchema.array().parse(json), {
        headers: getAuthHeaders(tokenState),
      });
    },
  });
}

export function useAdminBankAccounts() {
  const [tokenState] = useToken();

  return useQuery<BankAccountDTO[], ApiError>({
    queryKey: ["bank-accounts", "admin"],
    queryFn: async () =>
      apiGet("/api/v1/bank-accounts/admin", (json) => BankAccountDTOSchema.array().parse(json), {
        headers: getAuthHeaders(tokenState),
      }),
  });
}

export function useCreateBankAccount() {
  const [tokenState] = useToken();
  const queryClient = useQueryClient();

  return useMutation<BankAccountDTO, ApiError, BankAccountFormDTO>({
    mutationFn: async (payload) =>
      apiPost("/api/v1/bank-accounts", payload, (json) => BankAccountDTOSchema.parse(json), {
        headers: getAuthHeaders(tokenState),
      }),
    onSuccess: async () => {
      await Promise.all([
        queryClient.invalidateQueries({ queryKey: ["bank-accounts"] }),
        queryClient.invalidateQueries({ queryKey: ["bank-accounts", "admin"] }),
      ]);
    },
  });
}

export function useUpdateBankAccount() {
  const [tokenState] = useToken();
  const queryClient = useQueryClient();

  return useMutation<BankAccountDTO, ApiError, { id: number; data: BankAccountFormDTO }>({
    mutationFn: async ({ id, data }) =>
      apiPut(`/api/v1/bank-accounts/${id}`, data, (json) => BankAccountDTOSchema.parse(json), {
        headers: getAuthHeaders(tokenState),
      }),
    onSuccess: async () => {
      await Promise.all([
        queryClient.invalidateQueries({ queryKey: ["bank-accounts"] }),
        queryClient.invalidateQueries({ queryKey: ["bank-accounts", "admin"] }),
      ]);
    },
  });
}

export function useUpdateBankAccountActive() {
  const [tokenState] = useToken();
  const queryClient = useQueryClient();

  return useMutation<BankAccountDTO, ApiError, { id: number; active: boolean }>({
    mutationFn: async ({ id, active }) =>
      apiPatch(`/api/v1/bank-accounts/${id}/active`, { active }, (json) => BankAccountDTOSchema.parse(json), {
        headers: getAuthHeaders(tokenState),
      }),
    onSuccess: async () => {
      await Promise.all([
        queryClient.invalidateQueries({ queryKey: ["bank-accounts"] }),
        queryClient.invalidateQueries({ queryKey: ["bank-accounts", "admin"] }),
      ]);
    },
  });
}
