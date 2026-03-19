import { z, type ZodErrorMap, ZodIssueCode } from "zod";

function toSpanishTypeName(typeName: string): string {
  switch (typeName) {
    case "string":
      return "texto";
    case "number":
      return "numero";
    case "boolean":
      return "booleano";
    case "array":
      return "lista";
    case "object":
      return "objeto";
    case "date":
      return "fecha";
    case "undefined":
      return "vacio";
    case "null":
      return "nulo";
    default:
      return typeName;
  }
}

const zodEsErrorMap: ZodErrorMap = (issue, _ctx) => {
  switch (issue.code) {
    case ZodIssueCode.invalid_type: {
      if (issue.received === "undefined") {
        return { message: "Este campo es obligatorio." };
      }
      return {
        message: `Tipo invalido: se esperaba ${toSpanishTypeName(issue.expected)} y se recibio ${toSpanishTypeName(issue.received)}.`,
      };
    }
    case ZodIssueCode.invalid_string: {
      if (issue.validation === "email") {
        return { message: "El email no tiene un formato valido." };
      }
      return { message: "Formato de texto invalido." };
    }
    case ZodIssueCode.too_small: {
      if (issue.type === "string") {
        return {
          message: `Debe tener al menos ${issue.minimum} caracteres.`,
        };
      }
      if (issue.type === "number") {
        return {
          message: `Debe ser mayor o igual a ${issue.minimum}.`,
        };
      }
      if (issue.type === "array") {
        return {
          message: `Debe contener al menos ${issue.minimum} elementos.`,
        };
      }
      return { message: "El valor ingresado es demasiado chico." };
    }
    case ZodIssueCode.too_big: {
      if (issue.type === "string") {
        return {
          message: `Debe tener como maximo ${issue.maximum} caracteres.`,
        };
      }
      if (issue.type === "number") {
        return {
          message: `Debe ser menor o igual a ${issue.maximum}.`,
        };
      }
      if (issue.type === "array") {
        return {
          message: `Debe contener como maximo ${issue.maximum} elementos.`,
        };
      }
      return { message: "El valor ingresado es demasiado grande." };
    }
    case ZodIssueCode.invalid_enum_value:
      return { message: "Valor invalido para este campo." };
    case ZodIssueCode.invalid_date:
      return { message: "Fecha invalida." };
    case ZodIssueCode.unrecognized_keys:
      return { message: "Se enviaron campos no permitidos." };
    case ZodIssueCode.custom:
      return { message: issue.message || "El valor ingresado no es valido." };
    default:
      return { message: issue.message || "El valor ingresado no es valido." };
  }
};

export function configureZodSpanishErrors() {
  z.setErrorMap(zodEsErrorMap);
}
