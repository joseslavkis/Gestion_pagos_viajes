export type ApiErrorBody = { errors: string[] } | { message: string; code?: string } | string;

export class ApiError extends Error {
  public status: number;
  public rawMessage: string;
  public fieldErrors: string[];
  public code?: string;

  constructor(status: number, message: string, rawMessage = "", fieldErrors: string[] = [], code?: string) {
    super(message);
    this.name = "ApiError";
    this.status = status;
    this.rawMessage = rawMessage;
    this.fieldErrors = fieldErrors;
    this.code = code;
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
    "Hay pagos pendientes de aprobación. Revisalos antes de realizar una nueva imputación.":
      "Hay pagos pendientes de aprobación. Revisalos antes de realizar una nueva imputación.",
    "La imputación debe comenzar desde la primera cuota pendiente de pago.":
      "La imputación debe comenzar desde la primera cuota pendiente de pago.",
    "La imputación debe comenzar desde la cuota #":
      "La imputación debe comenzar desde la cuota indicada, que es la primera cuota pendiente de pago.",
    "Esta cuota ya está completamente pagada.":
      "Esta cuota ya está completamente pagada.",
    "Este viaje no tiene saldo pendiente.":
      "Este viaje no tiene saldo pendiente.",
    "El monto a imputar debe ser mayor a cero.":
      "El monto a imputar debe ser mayor a cero.",
    "El monto debe tener como máximo dos decimales.":
      "El monto debe tener como máximo dos decimales.",
    "El monto supera el máximo permitido de $ 99.999.999,99.":
      "El monto supera el máximo permitido de $ 99.999.999,99.",
    "El monto ingresado supera el saldo pendiente del viaje.":
      "El monto ingresado supera el saldo pendiente del viaje.",
    "El saldo cambió desde la última previsualización. Actualizá la imputación e intentá nuevamente.":
      "El saldo cambió desde la última previsualización. Actualizá la imputación e intentá nuevamente.",
    "La fecha de pago no puede ser futura.":
      "La fecha de pago no puede ser futura.",
    "No se pudo obtener la cotización para la fecha seleccionada. Intentá nuevamente.":
      "No se pudo obtener la cotización para la fecha seleccionada. Intentá nuevamente.",
    "La previsualización venció. Volvé a calcular la imputación.":
      "La previsualización venció. Volvé a calcular la imputación.",
    "La previsualización no corresponde a los datos ingresados. Volvé a calcular la imputación.":
      "La previsualización no corresponde a los datos ingresados. Volvé a calcular la imputación.",
    "Solo se admite un comprobante opcional por imputación manual":
      "Solo se admite un comprobante opcional por imputación manual.",
    "El motivo no puede superar los 500 caracteres":
      "El motivo no puede superar los 500 caracteres.",
    "El monto informado es demasiado bajo para imputarse":
      "El monto informado es demasiado bajo para imputarse.",
    "No tiene permisos para realizar una imputación manual":
      "No tiene permisos para realizar una imputación manual.",
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
  let code: string | undefined;

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
          if (typeof json.code === "string" && json.code.length > 0) {
            code = json.code;
          }
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
  throw new ApiError(status, userFriendlyMessage, rawMessage, fieldErrors, code);
}
