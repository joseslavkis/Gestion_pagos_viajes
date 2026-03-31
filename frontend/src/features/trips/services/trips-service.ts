import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";

import type {
  BulkAssignResultDTO,
  SpreadsheetDTO,
  SpreadsheetParams,
  StatusResponseDTO,
  TripCreateDTO,
  TripDetailDTO,
  TripStudentAdminDTO,
  TripSummaryDTO,
  TripUpdateDTO,
  UserAssignBulkDTO,
} from "@/features/trips/types/trips-dtos";
import {
  BulkAssignResultDTOSchema,
  SpreadsheetDTOSchema,
  StatusResponseDTOSchema,
  TripDetailDTOSchema,
  TripStudentAdminDTOSchema,
  TripSummaryDTOSchema,
} from "@/features/trips/types/trips-dtos";
import { ApiError } from "@/lib/api-error";
import { apiDelete, apiDownload, apiGet, apiPatch, apiPost } from "@/lib/api-client";
import { useToken } from "@/lib/session";

export function useTrips() {
  const [tokenState] = useToken();

  return useQuery<TripSummaryDTO[], ApiError>({
    queryKey: ["trips"],
    queryFn: async () =>
      apiGet("/api/v1/trips", (json) => TripSummaryDTOSchema.array().parse(json), {
        headers:
          tokenState.state === "LOGGED_IN"
            ? {
                Authorization: `Bearer ${tokenState.accessToken}`,
              }
            : undefined,
      }),
  });
}

export function useTrip(id: number | null) {
  const [tokenState] = useToken();

  return useQuery<TripDetailDTO, ApiError>({
    queryKey: ["trips", id],
    enabled: id != null,
    queryFn: async () =>
      apiGet(`/api/v1/trips/${id}`, (json) => TripDetailDTOSchema.parse(json), {
        headers:
          tokenState.state === "LOGGED_IN"
            ? {
                Authorization: `Bearer ${tokenState.accessToken}`,
              }
            : undefined,
      }),
  });
}

export function useCreateTrip() {
  const [tokenState] = useToken();
  const queryClient = useQueryClient();

  return useMutation<TripDetailDTO, ApiError, TripCreateDTO>({
    mutationFn: async (payload: TripCreateDTO) =>
      apiPost("/api/v1/trips", payload, (json) => TripDetailDTOSchema.parse(json), {
        headers:
          tokenState.state === "LOGGED_IN"
            ? {
                Authorization: `Bearer ${tokenState.accessToken}`,
              }
            : undefined,
      }),
    onSuccess: async () => {
      await queryClient.invalidateQueries({ queryKey: ["trips"] });
    },
  });
}

export function useUpdateTrip() {
  const [tokenState] = useToken();
  const queryClient = useQueryClient();

  return useMutation<TripDetailDTO, ApiError, { id: number; data: TripUpdateDTO }>({
    mutationFn: async ({ id, data }) =>
      apiPatch(`/api/v1/trips/${id}`, data, (json) => TripDetailDTOSchema.parse(json), {
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
        queryClient.invalidateQueries({ queryKey: ["trips", variables.id] }),
      ]);
    },
  });
}

export function useDeleteTrip() {
  const [tokenState] = useToken();
  const queryClient = useQueryClient();

  return useMutation<StatusResponseDTO, ApiError, { id: number }>({
    mutationFn: async ({ id }) =>
      apiDelete(`/api/v1/trips/${id}`, (json) => StatusResponseDTOSchema.parse(json), {
        headers:
          tokenState.state === "LOGGED_IN"
            ? {
                Authorization: `Bearer ${tokenState.accessToken}`,
              }
            : undefined,
      }),
    onSuccess: async () => {
      await queryClient.invalidateQueries({ queryKey: ["trips"] });
    },
  });
}

export function useAssignUsersBulk() {
  const [tokenState] = useToken();
  const queryClient = useQueryClient();

  return useMutation<BulkAssignResultDTO, ApiError, { id: number; data: UserAssignBulkDTO }>({
    mutationFn: async ({ id, data }) =>
      apiPost(`/api/v1/trips/${id}/users/bulk`, data, (json) => BulkAssignResultDTOSchema.parse(json), {
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
        queryClient.invalidateQueries({ queryKey: ["trips", variables.id] }),
        queryClient.invalidateQueries({ queryKey: ["trips", variables.id, "spreadsheet"] }),
      ]);
    },
  });
}

export function useTripStudentsAdmin(tripId: number | null) {
  const [tokenState] = useToken();

  return useQuery<TripStudentAdminDTO[], ApiError>({
    queryKey: ["trips", tripId, "students-admin"],
    enabled: tripId != null && tripId > 0,
    staleTime: 0,
    queryFn: async () =>
      apiGet(`/api/v1/trips/${tripId}/students`, (json) => TripStudentAdminDTOSchema.array().parse(json), {
        headers:
          tokenState.state === "LOGGED_IN"
            ? {
                Authorization: `Bearer ${tokenState.accessToken}`,
              }
            : undefined,
      }),
  });
}

export function useUnassignTripStudent() {
  const [tokenState] = useToken();
  const queryClient = useQueryClient();

  return useMutation<StatusResponseDTO, ApiError, { tripId: number; studentDni: string }>({
    mutationFn: async ({ tripId, studentDni }) =>
      apiDelete(`/api/v1/trips/${tripId}/students/${studentDni}`, (json) => StatusResponseDTOSchema.parse(json), {
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
        queryClient.invalidateQueries({ queryKey: ["trips", variables.tripId] }),
        queryClient.invalidateQueries({ queryKey: ["trips", variables.tripId, "spreadsheet"] }),
        queryClient.invalidateQueries({ queryKey: ["trips", variables.tripId, "students-admin"] }),
      ]);
    },
  });
}

export function useSpreadsheet(tripId: number, params: SpreadsheetParams) {
  const [tokenState] = useToken();

  return useQuery<SpreadsheetDTO, ApiError>({
    queryKey: [
      "trips",
      tripId,
      "spreadsheet",
      params.page,
      params.size,
      params.search ?? "",
      params.sortBy,
      params.order,
      params.status ?? "",
    ],
    enabled: tripId > 0,
    queryFn: async () => {
      const queryParams = new URLSearchParams();
      queryParams.set("page", String(params.page));
      queryParams.set("size", String(params.size));
      if (params.search && params.search.trim() !== "") {
        queryParams.set("search", params.search.trim());
      }
      if (params.sortBy) {
        queryParams.set("sortBy", params.sortBy);
      }
      if (params.order) {
        queryParams.set("order", params.order);
      }
      if (typeof params.status === "string" && params.status !== "") {
        queryParams.set("status", params.status);
      }

      const endpoint =
        queryParams.toString().length > 0
          ? `/api/v1/trips/${tripId}/spreadsheet?${queryParams.toString()}`
          : `/api/v1/trips/${tripId}/spreadsheet`;

      return apiGet(endpoint, (json) => SpreadsheetDTOSchema.parse(json), {
        headers:
          tokenState.state === "LOGGED_IN"
            ? {
                Authorization: `Bearer ${tokenState.accessToken}`,
              }
            : undefined,
      });
    },
  });
}

export async function downloadSpreadsheetExcel(
  tripId: number,
  tripName: string,
  accessToken: string,
): Promise<void> {
  const safeFilename = tripName
    .normalize("NFD")
    .replace(/[\u0300-\u036f]/g, "")
    .replace(/[^a-zA-Z0-9\s-]/g, "")
    .trim()
    .replace(/\s+/g, "-")
    .toLowerCase()
    .slice(0, 50);

  const filename = `planilla-${safeFilename || tripId}.xlsx`;

  await apiDownload(`/api/v1/trips/${tripId}/spreadsheet/export`, filename, {
    headers: { Authorization: `Bearer ${accessToken}` },
  });
}
