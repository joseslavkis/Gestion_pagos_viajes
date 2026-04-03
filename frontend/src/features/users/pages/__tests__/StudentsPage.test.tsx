import { fireEvent, screen } from "@testing-library/react";
import { HttpResponse, http } from "msw";
import { describe, expect, it } from "vitest";

import { StudentsPage } from "@/features/users/pages/StudentsPage";
import { server } from "@/test/msw-server";
import { renderWithProviders } from "@/test/test-utils";

const STUDENTS_URL = "http://localhost:30002/api/v1/users/students";

describe("StudentsPage", () => {
  it("muestra mensaje cuando no hay hijos reclamados", async () => {
    server.use(http.get(STUDENTS_URL, () => HttpResponse.json([])));

    renderWithProviders(<StudentsPage />);

    expect(await screen.findByText("Todavía no reclamaste hijos en tu cuenta.")).toBeInTheDocument();
  });

  it("lista los alumnos existentes", async () => {
    server.use(
      http.get(STUDENTS_URL, () =>
        HttpResponse.json([
          { id: 501, name: "Martina Slavkis", dni: "45678901" },
        ]),
      ),
    );

    renderWithProviders(<StudentsPage />);

    expect(await screen.findByText("Martina Slavkis")).toBeInTheDocument();
    expect(screen.getByText(/45678901/)).toBeInTheDocument();
  });

  it("muestra notificacion de exito al agregar un alumno", async () => {
    server.use(
      http.get(STUDENTS_URL, () => HttpResponse.json([])),
      http.post(STUDENTS_URL, async ({ request }) => {
        const body = await request.json() as { name: string; dni: string };
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

    fireEvent.click(screen.getByRole("button", { name: "Agregar hijo" }));

    expect(await screen.findByText("El alumno Lucia Perez se agrego con exito.")).toBeInTheDocument();
  });

  it("normaliza DNI con puntos antes de enviarlo", async () => {
    let receivedDni = "";

    server.use(
      http.get(STUDENTS_URL, () => HttpResponse.json([])),
      http.post(STUDENTS_URL, async ({ request }) => {
        const body = await request.json() as { name: string; dni: string };
        receivedDni = body.dni;
        return HttpResponse.json({ id: 999, ...body }, { status: 201 });
      }),
    );

    renderWithProviders(<StudentsPage />);

    fireEvent.change(await screen.findByLabelText("Nombre completo"), {
      target: { value: "Lucia Perez" },
    });
    fireEvent.change(screen.getByLabelText("DNI"), {
      target: { value: "40.111.222" },
    });

    fireEvent.click(screen.getByRole("button", { name: "Agregar hijo" }));

    expect(await screen.findByText("El alumno Lucia Perez se agrego con exito.")).toBeInTheDocument();
    expect(receivedDni).toBe("40111222");
  });
});
