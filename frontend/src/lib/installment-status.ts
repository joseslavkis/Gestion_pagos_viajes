const MILLISECONDS_PER_DAY = 1000 * 60 * 60 * 24;

export type InstallmentStatusCode = "GREEN" | "YELLOW" | "RED" | "RETROACTIVE";

export type InstallmentBaseDisplay = {
  tone: "green" | "yellow" | "red" | "retro";
  label: string;
};

function parseLocalDate(isoDate: string): Date | null {
  const parsedDate = new Date(`${isoDate}T00:00:00`);
  if (Number.isNaN(parsedDate.getTime())) {
    return null;
  }

  parsedDate.setHours(0, 0, 0, 0);
  return parsedDate;
}

export function isInstallmentDueSoon(
  dueDate: string,
  yellowWarningDays: number,
  today = new Date(),
): boolean {
  const normalizedToday = new Date(today);
  normalizedToday.setHours(0, 0, 0, 0);

  const normalizedDueDate = parseLocalDate(dueDate);
  if (!normalizedDueDate) {
    return false;
  }

  const diffDays = Math.ceil(
    (normalizedDueDate.getTime() - normalizedToday.getTime()) / MILLISECONDS_PER_DAY,
  );

  return diffDays <= Math.max(0, yellowWarningDays);
}

export function resolveInstallmentBaseDisplay(
  status: InstallmentStatusCode,
  dueDate: string,
  yellowWarningDays: number,
  today = new Date(),
): InstallmentBaseDisplay {
  switch (status) {
    case "GREEN":
      return { tone: "green", label: "Pagada" };
    case "YELLOW":
      if (isInstallmentDueSoon(dueDate, yellowWarningDays, today)) {
        return { tone: "yellow", label: "Vence pronto" };
      }
      return { tone: "green", label: "Al día" };
    case "RED":
      return { tone: "red", label: "Vencida" };
    case "RETROACTIVE":
      return { tone: "retro", label: "Deuda retroactiva" };
    default:
      return { tone: "green", label: "Al día" };
  }
}
