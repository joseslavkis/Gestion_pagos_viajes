import "@testing-library/jest-dom/vitest";
import { cleanup } from "@testing-library/react";
import { afterAll, afterEach, beforeAll, vi } from "vitest";

import { server } from "@/test/msw-server";

beforeAll(() => {
  server.listen({ onUnhandledRequest: "error" });
  if (!Element.prototype.scrollIntoView) {
    Object.defineProperty(Element.prototype, "scrollIntoView", {
      configurable: true,
      writable: true,
      value: vi.fn(),
    });
  }
  window.URL.createObjectURL = vi.fn(() => "blob:test-url");
  window.URL.revokeObjectURL = vi.fn();
});

afterEach(() => {
  cleanup();
  server.resetHandlers();
  window.localStorage.clear();
  vi.restoreAllMocks();
});

afterAll(() => {
  server.close();
});
