import type {
  SpreadsheetRowDTO,
  SpreadsheetRowInstallmentDTO,
} from "@/features/trips/types/trips-dtos";

export type SpreadsheetStatusVariant = "green" | "neutral" | "yellow" | "red";

export function formatPersonName(lastname: string | null | undefined, name: string | null | undefined) {
  const safeLastname = normalizeText(lastname);
  const safeName = normalizeText(name);

  if (safeLastname && safeName) {
    return `${safeLastname}, ${safeName}`;
  }

  return safeLastname ?? safeName ?? "";
}

export function getSpreadsheetParticipantPrimaryLabel(row: SpreadsheetRowDTO) {
  if (hasStudentIdentity(row)) {
    return formatPersonName(row.studentLastname, row.studentName);
  }

  return formatPersonName(row.lastname, row.name);
}

export function getSpreadsheetParticipantParentLabel(row: SpreadsheetRowDTO) {
  if (!hasStudentIdentity(row)) {
    return null;
  }

  const responsible = formatPersonName(row.lastname, row.name);
  return responsible ? `Responsable: ${responsible}` : null;
}

export function getSpreadsheetStatusVariant(
  installment: Pick<SpreadsheetRowInstallmentDTO, "uiStatusCode">,
): SpreadsheetStatusVariant {
  switch (installment.uiStatusCode) {
    case "PAID":
      return "green";
    case "UP_TO_DATE":
      return "neutral";
    case "UNDER_REVIEW":
    case "DUE_SOON":
      return "yellow";
    case "OVERDUE":
    case "RECEIPT_REJECTED":
    case "RETROACTIVE_DEBT":
      return "red";
    default:
      return "red";
  }
}

function hasStudentIdentity(row: SpreadsheetRowDTO) {
  return Boolean(normalizeText(row.studentName) || normalizeText(row.studentLastname) || row.studentId != null);
}

function normalizeText(value: string | null | undefined) {
  if (value == null) {
    return null;
  }

  const trimmed = value.trim();
  return trimmed.length > 0 ? trimmed : null;
}
