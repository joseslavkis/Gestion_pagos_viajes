// @vitest-environment jsdom
import { cleanup, fireEvent, render, screen } from "@testing-library/react";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { QueryClient, QueryClientProvider } from "@tanstack/react-query";

import { InitialLandingPage } from "../InitialLandingPage";

describe("InitialLandingPage", () => {
  let scrollIntoViewMock: ReturnType<typeof vi.fn>;
  let queryClient: QueryClient;

  beforeEach(() => {
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

  it("renders logo video with optimized playback attributes", () => {
    const { container } = renderPage();
    const video = container.querySelector("video");

    expect(video).not.toBeNull();
    expect(video?.getAttribute("preload")).toBe("metadata");
    expect(video?.hasAttribute("loop")).toBe(false);
    expect(video?.muted).toBe(true);
    expect(video?.hasAttribute("playsinline")).toBe(true);

    const sources = container.querySelectorAll("video source");
    expect(sources[0]?.getAttribute("src")).toBe("/logo-animado.webm");
    expect(sources[0]?.getAttribute("type")).toBe("video/webm");
    expect(sources[1]?.getAttribute("type")).toBe("video/quicktime");
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
  });
});
