import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";

import type { ManualImputationContextDTO } from "@/features/payments/types/manual-imputation-dtos";
import {
  ManualImputationContextDTOSchema,
  ManualImputationResponseDTOSchema,
} from "@/features/payments/types/manual-imputation-dtos";
import type {
  Currency,
  PaymentSubmissionDTO,
} from "@/features/payments/types/payments-dtos";
import { BASE_API_URL, apiGet } from "@/lib/api-client";
import { ApiError, handleApiResponse } from "@/lib/api-error";
import { useToken } from "@/lib/session";
import { PaymentSubmissionDTOSchema } from "@/features/payments/types/payments-dtos";

function authHeaders(tokenState: ReturnType<typeof useToken>[0]): Record<string, string> | undefined {
  return tokenState.state === "LOGGED_IN"
    ? { Authorization: `Bearer ${tokenState.accessToken}` }
    : undefined;
}

export function useManualImputationContext(installmentId: number | null) {
  const [tokenState] = useToken();

  return useQuery<ManualImputationContextDTO, ApiError>({
    queryKey: ["payments", "manual-imputation", "context", installmentId],
    enabled: installmentId != null && installmentId > 0,
    staleTime: 0,
    queryFn: async () => {
      if (installmentId == null) throw new Error("Manual imputation context requires an installment.");
      return apiGet(
        `/api/v1/payments/manual-imputations/context?installmentId=${installmentId}`,
        (json) => ManualImputationContextDTOSchema.parse(json),
        { headers: authHeaders(tokenState) },
      );
    },
  });
}

export type ManualImputationPayload = {
  anchorInstallmentId: number;
  reportedAmount: string;
  paymentCurrency: Currency;
  reportedPaymentDate: string;
  previewToken: string;
  reason?: string;
  file?: File | null;
};

export function useManualImputation() {
  const [tokenState] = useToken();
  const queryClient = useQueryClient();

  return useMutation<PaymentSubmissionDTO, ApiError, ManualImputationPayload>({
    // Errores con UI inline propia en ManualImputationForm: sin toast global.
    meta: { silentErrorToast: true },
    mutationFn: async (payload) => {
      const formData = new FormData();
      formData.append("anchorInstallmentId", String(payload.anchorInstallmentId));
      formData.append("reportedAmount", payload.reportedAmount);
      formData.append("reportedPaymentDate", payload.reportedPaymentDate);
      formData.append("paymentCurrency", payload.paymentCurrency);
      formData.append("previewToken", payload.previewToken);
      if (payload.reason?.trim()) {
        formData.append("reason", payload.reason.trim());
      }
      if (payload.file) {
        formData.append("file", payload.file);
      }

      const headers: Record<string, string> = {};
      if (tokenState.state === "LOGGED_IN") {
        headers.Authorization = `Bearer ${tokenState.accessToken}`;
      }

      const response = await fetch(`${BASE_API_URL}/api/v1/payments/manual-imputations`, {
        method: "POST",
        headers,
        body: formData,
      });

      if (response.ok) {
        const json: unknown = await response.json();
        return ManualImputationResponseDTOSchema.parse(json);
      }
      return handleApiResponse(response);
    },
    onSuccess: async () => {
      await Promise.all([
        queryClient.invalidateQueries({ queryKey: ["trips"] }),
        queryClient.invalidateQueries({ queryKey: ["spreadsheet"] }),
        queryClient.invalidateQueries({ queryKey: ["payments", "installment"] }),
        queryClient.invalidateQueries({ queryKey: ["payments", "manual-imputation"] }),
        queryClient.invalidateQueries({ queryKey: ["payments", "pending-review"] }),
        queryClient.invalidateQueries({ queryKey: ["payments", "my"] }),
        queryClient.invalidateQueries({ queryKey: ["payments", "my", "installments"] }),
      ]);
    },
  });
}

export function parseManualImputationResponse(json: unknown): PaymentSubmissionDTO {
  return PaymentSubmissionDTOSchema.parse(json);
}
