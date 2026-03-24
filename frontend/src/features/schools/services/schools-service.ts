import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";

import { ApiError } from "@/lib/api-error";
import { apiGet, apiPost } from "@/lib/api-client";
import { useToken } from "@/lib/session";
import {
  SchoolDTOArraySchema,
  SchoolDTOSchema,
  type SchoolCreateDTO,
  type SchoolDTO,
} from "@/features/schools/types/schools-dtos";

function getAuthHeaders(tokenState: ReturnType<typeof useToken>[0]): Record<string, string> | undefined {
  if (tokenState.state !== "LOGGED_IN") {
    return undefined;
  }

  return { Authorization: `Bearer ${tokenState.accessToken}` };
}

export function useSchools() {
  const [tokenState] = useToken();

  return useQuery<SchoolDTO[], ApiError>({
    queryKey: ["schools"],
    queryFn: async () =>
      apiGet("/api/v1/schools", (json) => SchoolDTOArraySchema.parse(json), {
        headers: getAuthHeaders(tokenState),
      }),
  });
}

export function useAdminSchools() {
  const [tokenState] = useToken();

  return useQuery<SchoolDTO[], ApiError>({
    queryKey: ["schools", "admin"],
    queryFn: async () =>
      apiGet("/api/v1/admin/schools", (json) => SchoolDTOArraySchema.parse(json), {
        headers: getAuthHeaders(tokenState),
      }),
  });
}

export function useCreateSchool() {
  const [tokenState] = useToken();
  const queryClient = useQueryClient();

  return useMutation<SchoolDTO, ApiError, SchoolCreateDTO>({
    mutationFn: async (payload) =>
      apiPost("/api/v1/admin/schools", payload, (json) => SchoolDTOSchema.parse(json), {
        headers: getAuthHeaders(tokenState),
      }),
    onSuccess: async () => {
      await Promise.all([
        queryClient.invalidateQueries({ queryKey: ["schools"] }),
        queryClient.invalidateQueries({ queryKey: ["schools", "admin"] }),
      ]);
    },
  });
}
