// @vitest-environment jsdom
import { cleanup, fireEvent, render, screen, waitFor } from "@testing-library/react";
import { afterEach, beforeEach, describe, expect, it } from "vitest";

import { TokenProvider, useToken } from "@/lib/session";

const TOKEN_STORAGE_KEY = "pagos-viajes-auth-tokens";

function base64UrlEncode(value: string): string {
  return btoa(value).replace(/\+/g, "-").replace(/\//g, "_").replace(/=+$/g, "");
}

function createJwt(payload: Record<string, unknown>): string {
  const header = base64UrlEncode(JSON.stringify({ alg: "HS256", typ: "JWT" }));
  const body = base64UrlEncode(JSON.stringify(payload));
  return `${header}.${body}.signature`;
}

function TokenProbe() {
  const [tokenState, setTokenState] = useToken();

  return (
    <>
      <div data-testid="state">{tokenState.state}</div>
      <button
        onClick={() =>
          setTokenState({
            state: "LOGGED_IN",
            accessToken: createJwt({ exp: Math.floor(Date.now() / 1000) + 3600, marker: "-_" }),
            refreshToken: "refresh-token",
          })
        }
      >
        set-logged-in
      </button>
      <button onClick={() => setTokenState({ state: "LOGGED_OUT" })}>set-logged-out</button>
    </>
  );
}

describe("session context", () => {
  beforeEach(() => {
    window.localStorage.clear();
  });

  afterEach(() => {
    cleanup();
  });

  it("loads LOGGED_IN from localStorage using a base64url JWT", () => {
    const accessToken = createJwt({ exp: Math.floor(Date.now() / 1000) + 3600, marker: "-_" });
    window.localStorage.setItem(
      TOKEN_STORAGE_KEY,
      JSON.stringify({
        accessToken,
        refreshToken: "refresh-token",
      }),
    );

    render(
      <TokenProvider>
        <TokenProbe />
      </TokenProvider>,
    );

    expect(screen.getByTestId("state").textContent).toBe("LOGGED_IN");
  });

  it("marks expired JWT as LOGGED_OUT and clears localStorage", () => {
    const expiredToken = createJwt({ exp: Math.floor(Date.now() / 1000) - 60 });
    window.localStorage.setItem(
      TOKEN_STORAGE_KEY,
      JSON.stringify({
        accessToken: expiredToken,
        refreshToken: "refresh-token",
      }),
    );

    render(
      <TokenProvider>
        <TokenProbe />
      </TokenProvider>,
    );

    expect(screen.getByTestId("state").textContent).toBe("LOGGED_OUT");
    expect(window.localStorage.getItem(TOKEN_STORAGE_KEY)).toBeNull();
  });

  it("persists login/logout state transitions to localStorage", async () => {
    render(
      <TokenProvider>
        <TokenProbe />
      </TokenProvider>,
    );

    fireEvent.click(screen.getByRole("button", { name: "set-logged-in" }));

    await waitFor(() => {
      const raw = window.localStorage.getItem(TOKEN_STORAGE_KEY);
      expect(raw).not.toBeNull();
      const parsed = JSON.parse(raw ?? "{}");
      expect(parsed.refreshToken).toBe("refresh-token");
      expect(typeof parsed.accessToken).toBe("string");
    });

    fireEvent.click(screen.getByRole("button", { name: "set-logged-out" }));

    await waitFor(() => {
      expect(window.localStorage.getItem(TOKEN_STORAGE_KEY)).toBeNull();
    });
  });
});
