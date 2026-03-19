import { QueryClient } from "@tanstack/react-query";
import { ApiError } from "@/lib/api-error";

export const appQueryClient = new QueryClient({
  defaultOptions: {
    queries: {
      retry: (failureCount, error) => {
        // No reintentar en errores del cliente (4xx)
        if (error instanceof ApiError && error.status >= 400 && error.status < 500) {
          return false;
        }
        return failureCount < 2; // solo reintentar errores de red o 5xx
      },
      staleTime: 1000 * 60, // 1 minuto antes de refetch automático
    },
    mutations: {
      retry: false,
    },
  },
});
