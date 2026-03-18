import React, { Dispatch, useContext, useEffect, useState } from "react";

type TokenContextData =
  | {
      state: "LOGGED_OUT";
    }
  | {
      state: "LOGGED_IN";
      accessToken: string;
      refreshToken: string | null;
    };

const TokenContext = React.createContext<[TokenContextData, Dispatch<TokenContextData>] | null>(null);
const TOKEN_STORAGE_KEY = "pagos-viajes-auth-tokens";

export const TokenProvider = ({ children }: React.PropsWithChildren) => {
  const [state, setState] = useState<TokenContextData>(getInitialTokenState);

  useEffect(() => {
    if (typeof window === "undefined") {
      return;
    }

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

  return <TokenContext.Provider value={[state, setState]}>{children}</TokenContext.Provider>;
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

// eslint-disable-next-line react-refresh/only-export-components
export function useToken() {
  const context = useContext(TokenContext);
  if (context === null) {
    throw new Error("React tree should be wrapped in TokenProvider");
  }
  return context;
}
