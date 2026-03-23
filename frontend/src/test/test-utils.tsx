import type { ReactElement, ReactNode } from "react";

import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { render } from "@testing-library/react";

import { TokenProvider } from "@/lib/session";

const TOKEN_STORAGE_KEY = "pagos-viajes-auth-tokens";

function base64UrlEncode(value: string): string {
  return btoa(value).replace(/\+/g, "-").replace(/\//g, "_").replace(/=+$/g, "");
}

export function createJwt(role: "ROLE_ADMIN" | "ROLE_USER"): string {
  const header = base64UrlEncode(JSON.stringify({ alg: "HS256", typ: "JWT" }));
  const payload = base64UrlEncode(JSON.stringify({
    role,
    exp: Math.floor(Date.now() / 1000) + 3600,
  }));
  return `${header}.${payload}.signature`;
}

export function setLoggedInToken(role: "ROLE_ADMIN" | "ROLE_USER") {
  window.localStorage.setItem(
    TOKEN_STORAGE_KEY,
    JSON.stringify({
      accessToken: createJwt(role),
      refreshToken: "refresh-token",
    }),
  );
}

export function renderWithProviders(ui: ReactElement, role: "ROLE_ADMIN" | "ROLE_USER" = "ROLE_USER") {
  setLoggedInToken(role);

  const queryClient = new QueryClient({
    defaultOptions: {
      queries: { retry: false },
      mutations: { retry: false },
    },
  });

  const wrapper = ({ children }: { children: ReactNode }) => (
    <QueryClientProvider client={queryClient}>
      <TokenProvider>{children}</TokenProvider>
    </QueryClientProvider>
  );

  return {
    queryClient,
    ...render(ui, { wrapper }),
  };
}
