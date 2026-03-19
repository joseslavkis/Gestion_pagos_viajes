import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";

import type {
  BulkAssignResultDTO,
  SpreadsheetDTO,
  SpreadsheetParams,
  StatusResponseDTO,
  TripCreateDTO,
  TripDetailDTO,
  TripSummaryDTO,
  TripUpdateDTO,
  UserAssignBulkDTO,
} from "@/features/trips/types/trips-dtos";
import {
  BulkAssignResultDTOSchema,
  SpreadsheetDTOSchema,
  StatusResponseDTOSchema,
  TripDetailDTOSchema,
  TripSummaryDTOSchema,
} from "@/features/trips/types/trips-dtos";
import { ApiError } from "@/lib/api-error";
import { apiDelete, apiGet, apiPatch, apiPost } from "@/lib/api-client";
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
      await queryClient.invalidateQueries({ queryKey: ["trips", variables.id] });
    },
  });
}

export function useSpreadsheet(tripId: number, params: SpreadsheetParams) {
  const [tokenState] = useToken();

  return useQuery<SpreadsheetDTO, ApiError>({
    queryKey: ["trips", tripId, "spreadsheet", params],
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


