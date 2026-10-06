// @vitest-environment jsdom
import { cleanup, fireEvent, render, screen } from "@testing-library/react";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { QueryClient, QueryClientProvider } from "@tanstack/react-query";

import { InitialLandingPage } from "../InitialLandingPage";

const { mutateAsyncMock, resetMock } = vi.hoisted(() => ({
  mutateAsyncMock: vi.fn().mockResolvedValue({ status: "success", message: "ok" }),
  resetMock: vi.fn(),
}));

vi.mock("@/features/contact/services/contact-service", () => ({
  useSendContactMessage: () => ({
    mutateAsync: mutateAsyncMock,
    error: null,
    isPending: false,
    reset: resetMock,
  }),
}));

describe("InitialLandingPage", () => {
  let scrollIntoViewMock: ReturnType<typeof vi.fn>;
  let queryClient: QueryClient;

  beforeEach(() => {
    mutateAsyncMock.mockClear();
    resetMock.mockClear();

    queryClient = new QueryClient({
      defaultOptions: {
        queries: { retry: false },
        mutations: { retry: false },
      },
    });

    scrollIntoViewMock = vi.fn();
    Object.defineProperty(Element.prototype, "scrollIntoView", {
      configurable: true,
      writable: true,
      value: scrollIntoViewMock,
    });
  });

  afterEach(() => {
    cleanup();
    vi.restoreAllMocks();
    queryClient.clear();
  });

  const renderPage = () =>
    render(
      <QueryClientProvider client={queryClient}>
        <InitialLandingPage />
      </QueryClientProvider>,
    );

  it("renders the inline animated logo as a labelled SVG and no longer depends on a video", () => {
    const { container } = renderPage();

    const logo = screen.getByRole("img", { name: /Proyecto VA/i });
    expect(logo).not.toBeNull();
    expect(logo.tagName.toLowerCase()).toBe("svg");
    expect(logo.getAttribute("viewBox")).toBeTruthy();

    // The four animatable parts the GSAP timeline targets must all be present.
    for (const part of ["25", "proyecto", "va", "tagline"]) {
      expect(container.querySelector(`[data-logo-part="${part}"]`)).not.toBeNull();
    }

    // The "25" is a real stroke path (draw-on needs stroke geometry, not a raster).
    const strokes = container.querySelectorAll('[data-logo-part="25"] path');
    expect(strokes.length).toBeGreaterThan(0);
    expect(strokes[0]?.getAttribute("d")).toBeTruthy();
    expect(container.querySelector('[data-logo-part="25"]')?.getAttribute("fill")).toBe("none");

    // No video dependency remains.
    expect(container.querySelector("video")).toBeNull();
    expect(container.querySelector("video source")).toBeNull();
  });

  it("uses scrollIntoView smooth behavior when clicking Contacto", () => {
    renderPage();

    fireEvent.click(screen.getByRole("link", { name: "Contacto" }));

    expect(scrollIntoViewMock).toHaveBeenCalledTimes(1);
    expect(scrollIntoViewMock).toHaveBeenCalledWith({ behavior: "smooth", block: "start" });
  });

  it("prevents default submit behavior in contact form", () => {
    renderPage();

    const submitButton = screen.getByRole("button", { name: "Enviar consulta" });
    const form = submitButton.closest("form");
    expect(form).not.toBeNull();

    const submitEvent = new Event("submit", { bubbles: true, cancelable: true });
    fireEvent(form!, submitEvent);
    expect(submitEvent.defaultPrevented).toBe(true);
    expect(mutateAsyncMock).toHaveBeenCalledTimes(1);
  });
});
