import React, { Dispatch, useContext, useEffect, useReducer } from "react";

function isJwtExpired(token: string): boolean {
  try {
    const payloadPart = token.split(".")[1];
    if (!payloadPart) {
      return true;
    }

    // JWT payload is base64url, not standard base64.
    const base64 = payloadPart.replace(/-/g, "+").replace(/_/g, "/");
    const paddedBase64 = base64.padEnd(Math.ceil(base64.length / 4) * 4, "=");
    const payload = JSON.parse(atob(paddedBase64)) as { exp?: unknown };

    // exp es en segundos, Date.now() en ms
    return typeof payload.exp === "number" && payload.exp * 1000 < Date.now();
  } catch {
    return true; // si no se puede decodificar, tratar como expirado
  }
}

export type TokenContextData =
  | {
      state: "LOGGED_OUT";
    }
  | {
      state: "LOGGED_IN";
      accessToken: string;
      refreshToken: string | null;
    };

type TokenContextValue = readonly [TokenContextData, Dispatch<TokenContextData>];

const TokenContext = React.createContext<TokenContextValue | null>(null);
const TOKEN_STORAGE_KEY = "pagos-viajes-auth-tokens";

export const TokenProvider = ({ children }: React.PropsWithChildren) => {
  const [state, setState] = useReducer(
    (_prev: TokenContextData, next: TokenContextData) => next,
    undefined,
    getInitialTokenState,
  );

  useEffect(() => {
    // Persist only when state changes; on first render state is derived from localStorage.
    if (typeof window === "undefined") return;

    if (state.state === "LOGGED_IN") {
      window.localStorage.setItem(
        TOKEN_STORAGE_KEY,
        JSON.stringify({
          accessToken: state.accessToken,
          refreshToken: state.refreshToken,
        }),
      );
      return;
    }

    window.localStorage.removeItem(TOKEN_STORAGE_KEY);
  }, [state]);

  return <TokenContext.Provider value={[state, setState] as TokenContextValue}>{children}</TokenContext.Provider>;
};

function getInitialTokenState(): TokenContextData {
  if (typeof window === "undefined") {
    return { state: "LOGGED_OUT" };
  }

  try {
    const rawTokens = window.localStorage.getItem(TOKEN_STORAGE_KEY);
    if (!rawTokens) {
      return { state: "LOGGED_OUT" };
    }

    const parsed = JSON.parse(rawTokens) as { accessToken?: unknown; refreshToken?: unknown };
    if (typeof parsed.accessToken !== "string" || parsed.accessToken.length === 0) {
      return { state: "LOGGED_OUT" };
    }

    if (isJwtExpired(parsed.accessToken)) {
      window.localStorage.removeItem(TOKEN_STORAGE_KEY);
      return { state: "LOGGED_OUT" };
    }

    const refreshToken = parsed.refreshToken;
    if (refreshToken !== null && typeof refreshToken !== "string" && refreshToken !== undefined) {
      return { state: "LOGGED_OUT" };
    }

    return {
      state: "LOGGED_IN",
      accessToken: parsed.accessToken,
      refreshToken: typeof refreshToken === "string" ? refreshToken : null,
    };
  } catch {
    return { state: "LOGGED_OUT" };
  }
}

export function useToken(): TokenContextValue {
  const context = useContext(TokenContext);
  if (context === null) {
    throw new Error("React tree should be wrapped in TokenProvider");
  }
  return context;
}