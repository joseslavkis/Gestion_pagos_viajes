import { fireEvent, screen } from "@testing-library/react";
import { describe, expect, it } from "vitest";

import { CommonLayout } from "@/components/CommonLayout/CommonLayout";
import { renderWithProviders } from "@/test/test-utils";

describe("CommonLayout", () => {
  it("muestra opciones de admin en el menu hamburguesa", () => {
    renderWithProviders(
      <CommonLayout>
        <div>contenido</div>
      </CommonLayout>,
      "ROLE_ADMIN",
    );

    fireEvent.click(screen.getByRole("button", { name: "Abrir menú" }));

    expect(screen.getByRole("link", { name: "Viajes" })).toBeInTheDocument();
    expect(screen.getByRole("link", { name: "Pendientes de revisión" })).toBeInTheDocument();
    expect(screen.getByRole("link", { name: "Cuentas bancarias" })).toBeInTheDocument();
    expect(screen.getByRole("button", { name: "Cerrar sesión" })).toBeInTheDocument();
  });

  it("oculta opciones administrativas para usuario normal", () => {
    renderWithProviders(
      <CommonLayout>
        <div>contenido</div>
      </CommonLayout>,
      "ROLE_USER",
    );

    fireEvent.click(screen.getByRole("button", { name: "Abrir menú" }));

    expect(screen.getByRole("link", { name: "Inicio" })).toBeInTheDocument();
    expect(screen.queryByRole("link", { name: "Pendientes de revisión" })).not.toBeInTheDocument();
    expect(screen.queryByRole("link", { name: "Cuentas bancarias" })).not.toBeInTheDocument();
  });
});
