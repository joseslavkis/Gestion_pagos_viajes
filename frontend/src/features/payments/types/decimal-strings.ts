import {
  DecimalStringSchema,
  type DecimalString,
} from "./payments-dtos";

export function normalizePaymentDecimalInput(value: string): DecimalString | null {
  const normalized = value.replace(",", ".").trim();
  const result = DecimalStringSchema.safeParse(normalized);
  if (!result.success || result.data.startsWith("-")) {
    return null;
  }
  return result.data;
}

export function compareNonNegativeDecimalStrings(
  left: DecimalString,
  right: DecimalString,
): -1 | 0 | 1 {
  const leftParts = decimalParts(left);
  const rightParts = decimalParts(right);

  if (leftParts.integer.length !== rightParts.integer.length) {
    return leftParts.integer.length < rightParts.integer.length ? -1 : 1;
  }
  if (leftParts.integer !== rightParts.integer) {
    return leftParts.integer < rightParts.integer ? -1 : 1;
  }

  const fractionLength = Math.max(leftParts.fraction.length, rightParts.fraction.length);
  const leftFraction = leftParts.fraction.padEnd(fractionLength, "0");
  const rightFraction = rightParts.fraction.padEnd(fractionLength, "0");
  if (leftFraction === rightFraction) {
    return 0;
  }
  return leftFraction < rightFraction ? -1 : 1;
}

function decimalParts(value: DecimalString): { integer: string; fraction: string } {
  const [integer = "0", fraction = ""] = value.split(".");
  return {
    integer: integer.replace(/^0+(?=\d)/, ""),
    fraction,
  };
}
