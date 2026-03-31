import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";

import type { StudentCreateDTO } from "@/features/auth/types/auth-dtos";
import {
  AdminUserDetailDTOSchema,
  AdminUserSearchResultDTOSchema,
  StudentDTOSchema,
  type AdminUserDetailDTO,
  type AdminUserSearchResultDTO,
  type StudentDTO,
} from "@/features/users/types/users-dtos";
import { ApiError } from "@/lib/api-error";
import { StatusResponseDTOSchema, type StatusResponseDTO } from "@/lib/backend-dtos";
import { apiDelete, apiGet, apiPost } from "@/lib/api-client";
import { useToken } from "@/lib/session";

export function useStudents() {
  const [tokenState] = useToken();

  return useQuery<StudentDTO[], ApiError>({
    queryKey: ["students"],
    queryFn: async () =>
      apiGet("/api/v1/users/students", (json) => StudentDTOSchema.array().parse(json), {
        headers:
          tokenState.state === "LOGGED_IN"
            ? { Authorization: `Bearer ${tokenState.accessToken}` }
            : undefined,
      }),
  });
}

export function useAdminUserSearch(query: string) {
  const [tokenState] = useToken();
  const normalizedQuery = query.trim();

  return useQuery<AdminUserSearchResultDTO[], ApiError>({
    queryKey: ["users", "admin", "search", normalizedQuery],
    enabled: normalizedQuery.length >= 2,
    staleTime: 0,
    queryFn: async () =>
      apiGet(
        `/api/v1/users/admin/search?q=${encodeURIComponent(normalizedQuery)}`,
        (json) => AdminUserSearchResultDTOSchema.array().parse(json),
        {
          headers:
            tokenState.state === "LOGGED_IN"
              ? { Authorization: `Bearer ${tokenState.accessToken}` }
              : undefined,
        },
      ),
  });
}

export function useAdminUserDetail(userId: number | null) {
  const [tokenState] = useToken();

  return useQuery<AdminUserDetailDTO, ApiError>({
    queryKey: ["users", "admin", "detail", userId],
    enabled: userId != null && userId > 0,
    staleTime: 0,
    queryFn: async () =>
      apiGet(
        `/api/v1/users/admin/${userId}/detail`,
        (json) => AdminUserDetailDTOSchema.parse(json),
        {
          headers:
            tokenState.state === "LOGGED_IN"
              ? { Authorization: `Bearer ${tokenState.accessToken}` }
              : undefined,
        },
      ),
  });
}

export function useAddStudent() {
  const [tokenState] = useToken();
  const queryClient = useQueryClient();

  return useMutation<StudentDTO, ApiError, StudentCreateDTO>({
    mutationFn: async (payload) =>
      apiPost("/api/v1/users/students", payload, (json) => StudentDTOSchema.parse(json), {
        headers:
          tokenState.state === "LOGGED_IN"
            ? { Authorization: `Bearer ${tokenState.accessToken}` }
            : undefined,
      }),
    onSuccess: async () => {
      await Promise.all([
        queryClient.invalidateQueries({ queryKey: ["students"] }),
        queryClient.invalidateQueries({ queryKey: ["payments", "my", "installments"] }),
      ]);
    },
  });
}

export function useDeleteStudent() {
  const [tokenState] = useToken();
  const queryClient = useQueryClient();

  return useMutation<StatusResponseDTO, ApiError, number>({
    mutationFn: async (studentId) =>
      apiDelete(`/api/v1/users/students/${studentId}`, (json) => StatusResponseDTOSchema.parse(json), {
        headers:
          tokenState.state === "LOGGED_IN"
            ? { Authorization: `Bearer ${tokenState.accessToken}` }
            : undefined,
      }),
    onSuccess: async () => {
      await Promise.all([
        queryClient.invalidateQueries({ queryKey: ["students"] }),
        queryClient.invalidateQueries({ queryKey: ["payments", "my", "installments"] }),
      ]);
    },
  });
}
