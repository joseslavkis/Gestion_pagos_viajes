import { fireEvent, screen } from "@testing-library/react";
import { HttpResponse, http } from "msw";
import { describe, expect, it } from "vitest";

import { SchoolsPage } from "@/features/schools/pages/SchoolsPage";
import { server } from "@/test/msw-server";
import { renderWithProviders } from "@/test/test-utils";

describe("SchoolsPage", () => {
  it("crea un colegio nuevo y refresca el listado", async () => {
    let schools = [
      { id: 1, name: "Colegio Ward" },
    ];

    server.use(
      http.get("http://localhost:30002/api/v1/admin/schools", () => HttpResponse.json(schools)),
      http.post("http://localhost:30002/api/v1/admin/schools", async ({ request }) => {
        const body = await request.json() as Record<string, unknown>;
        const created = { id: 2, name: String(body.name) };
        schools = [...schools, created];
        return HttpResponse.json(created, { status: 201 });
      }),
    );

    renderWithProviders(<SchoolsPage />, "ROLE_ADMIN");

    expect(await screen.findByText("Colegio Ward")).toBeInTheDocument();

    fireEvent.change(screen.getByLabelText("Nombre"), { target: { value: "Colegio San Jose" } });
    fireEvent.click(screen.getByRole("button", { name: "Agregar colegio" }));

    expect(await screen.findByText("Colegio San Jose")).toBeInTheDocument();
  });
});
