export type ApiErrorBody = { errors: string[] } | { message: string } | string;

export class ApiError extends Error {
  public status: number;
  public rawMessage: string;
  public fieldErrors: string[];

  constructor(status: number, message: string, rawMessage: string = "", fieldErrors: string[] = []) {
    super(message);
    this.name = "ApiError";
    this.status = status;
    this.rawMessage = rawMessage;
    this.fieldErrors = fieldErrors;
  }
}

function translateBackendMessage(message: string): string {
  const translations: Record<string, string> = {
    "Cannot modify firstDueDate on a trip that already has assigned users.":
      "No se puede modificar la fecha de vencimiento de un viaje que ya tiene usuarios asignados.",
    "Cannot delete a trip with assigned users":
      "No se pudo eliminar el viaje.",
    "Esta cuota ya está pagada":
      "Esta cuota ya está pagada.",
    "Ya existe un comprobante pendiente de revisión para esta cuota":
      "Ya existe un comprobante pendiente de revisión para esta cuota.",
    "Este comprobante ya fue revisado":
      "Este comprobante ya fue revisado.",
    "Solo se puede anular un comprobante aprobado":
      "Solo se puede anular un comprobante aprobado.",
    "Se requiere una observación al rechazar un comprobante":
      "Se requiere una observación al rechazar un comprobante.",
    "No podés registrar un pago para una cuota que no es tuya":
      "No podés registrar un pago para una cuota que no es tuya.",
    "Debe seleccionar una cuenta bancaria para acreditar el pago":
      "Debes seleccionar la cuenta donde acreditaste el pago.",
    "La cuenta bancaria seleccionada no está activa":
      "La cuenta bancaria seleccionada no está activa.",
    "La cuenta bancaria seleccionada no coincide con la moneda del pago":
      "La cuenta bancaria seleccionada no coincide con la moneda del pago.",
    "BankAccount not found":
      "La cuenta bancaria no fue encontrada.",
    "No se puede eliminar el viaje porque hay usuarios con cuotas pendientes de pago.":
      "No se pudo eliminar el viaje.",
    "Trip not found":
      "El viaje no fue encontrado.",
    "User not found":
      "El usuario no fue encontrado.",
    "Installment not found":
      "La cuota no fue encontrada.",
  };

  // Buscar coincidencia exacta primero
  if (translations[message]) {
    return translations[message];
  }

  // Buscar coincidencia parcial (startsWith) para mensajes con IDs
  for (const [key, value] of Object.entries(translations)) {
    if (message.startsWith(key)) {
      return value;
    }
  }

  return message;
}

export async function handleApiResponse(response: Response): Promise<never> {
  const status = response.status;
  let rawMessage = "";
  let fieldErrors: string[] = [];

  try {
    const errorBody = await response.text();
    rawMessage = errorBody;

    try {
      const json = JSON.parse(errorBody) as ApiErrorBody;
      if (json && typeof json === "object") {
        if ("errors" in json && Array.isArray(json.errors)) {
          fieldErrors = json.errors.filter((entry): entry is string => typeof entry === "string");
          if (fieldErrors.length > 0) {
            rawMessage = fieldErrors.join(", ");
          }
        } else if ("message" in json && typeof json.message === "string") {
          rawMessage = json.message;
        }
      }
    } catch {
      // no es JSON, rawMessage ya tiene el texto plano
    }
  } catch {
    // Body could not be read; rawMessage stays empty, friendly message will be shown
  }

  rawMessage = translateBackendMessage(rawMessage);

  let userFriendlyMessage = "No se pudo completar la solicitud";

  switch (status) {
    case 400:
      userFriendlyMessage = rawMessage && rawMessage.trim().length > 0
        ? translateBackendMessage(rawMessage)
        : "Petición inválida. Verifique los datos ingresados.";
      break;
    case 401:
      userFriendlyMessage = "Credenciales inválidas o sesión expirada.";
      break;
    case 403:
      userFriendlyMessage = "No tiene permisos para realizar esta acción.";
      break;
    case 404:
      userFriendlyMessage = "El recurso solicitado no fue encontrado.";
      break;
    case 409:
      // Para 409 usar el mensaje del backend si está disponible,
      // ya que distintos endpoints pueden tener distintos motivos
      // de conflicto (email duplicado, viaje con usuarios, etc.)
      userFriendlyMessage = rawMessage && rawMessage.trim().length > 0
        ? translateBackendMessage(rawMessage)
        : "El recurso ya existe o hay un conflicto con el estado actual.";
      break;
    case 500:
    case 502:
    case 503:
    case 504:
      userFriendlyMessage = "Error interno del servidor. Intente nuevamente más tarde.";
      break;
  }

  // Si el backend nos da un mensaje legible, podríamos usarlo si es seguro,
  // pero generalmente para 409 o 401 devolvemos nuestro friendly message.
  throw new ApiError(status, userFriendlyMessage, rawMessage, fieldErrors);
}
