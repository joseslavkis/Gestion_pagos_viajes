import { fireEvent, screen } from "@testing-library/react";
import { HttpResponse, http } from "msw";
import { describe, expect, it } from "vitest";

import { StudentsPage } from "@/features/users/pages/StudentsPage";
import { server } from "@/test/msw-server";
import { renderWithProviders } from "@/test/test-utils";

const STUDENTS_URL = "http://localhost:30002/api/v1/users/students";
const SCHOOLS_URL = "http://localhost:30002/api/v1/schools";

describe("StudentsPage", () => {
  it("muestra mensaje cuando no hay hijos reclamados", async () => {
    server.use(
      http.get(STUDENTS_URL, () => HttpResponse.json([])),
      http.get(SCHOOLS_URL, () => HttpResponse.json([])),
    );

    renderWithProviders(<StudentsPage />);

    expect(await screen.findByText("Todavía no reclamaste hijos en tu cuenta.")).toBeInTheDocument();
  });

  it("lista los alumnos existentes", async () => {
    server.use(
      http.get(STUDENTS_URL, () =>
        HttpResponse.json([
          { id: 501, name: "Martina Slavkis", dni: "45678901", schoolName: "Colegio Test", courseName: "5A" },
        ]),
      ),
      http.get(SCHOOLS_URL, () => HttpResponse.json([{ id: 10, name: "Colegio Test" }])),
    );

    renderWithProviders(<StudentsPage />);

    expect(await screen.findByText("Martina Slavkis")).toBeInTheDocument();
    expect(screen.getByText(/45678901/)).toBeInTheDocument();
  });

  it("muestra notificacion de exito al agregar un alumno", async () => {
    server.use(
      http.get(STUDENTS_URL, () => HttpResponse.json([])),
      http.get(SCHOOLS_URL, () =>
        HttpResponse.json([{ id: 10, name: "Colegio Test" }]),
      ),
      http.post(STUDENTS_URL, async ({ request }) => {
        const body = await request.json() as { name: string; dni: string; schoolName: string; courseName: string };
        return HttpResponse.json({ id: 999, ...body }, { status: 201 });
      }),
    );

    renderWithProviders(<StudentsPage />);

    fireEvent.change(await screen.findByLabelText("Nombre completo"), {
      target: { value: "Lucia Perez" },
    });
    fireEvent.change(screen.getByLabelText("DNI"), {
      target: { value: "40111222" },
    });
    fireEvent.change(screen.getByLabelText("Colegio"), {
      target: { value: "Colegio Test" },
    });
    fireEvent.change(screen.getByLabelText("Curso"), {
      target: { value: "5A" },
    });

    fireEvent.click(screen.getByRole("button", { name: "Agregar hijo" }));

    expect(await screen.findByText("El alumno Lucia Perez se agrego con exito.")).toBeInTheDocument();
  });
});
