export class ApiError extends Error {
  public status: number;
  public rawMessage: string;

  constructor(status: number, message: string, rawMessage: string = "") {
    super(message);
    this.name = "ApiError";
    this.status = status;
    this.rawMessage = rawMessage;
  }
}

export async function handleApiResponse(response: Response): Promise<never> {
  const status = response.status;
  let rawMessage = "";

  try {
    const errorBody = await response.text();
    rawMessage = errorBody;

    try {
      const json = JSON.parse(errorBody) as unknown;
      if (
        json &&
        typeof json === "object" &&
        "message" in json &&
        typeof (json as Record<string, unknown>).message === "string"
      ) {
        rawMessage = (json as { message: string }).message;
      }
    } catch {
      // no es JSON, rawMessage ya tiene el texto plano
    }
  } catch {
    // Body could not be read; rawMessage stays empty, friendly message will be shown
  }

  let userFriendlyMessage = "No se pudo completar la solicitud";

  switch (status) {
    case 400:
      userFriendlyMessage = "Petición inválida. Verifique los datos ingresados.";
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
      userFriendlyMessage = "El email o documento ingresado ya se encuentra registrado.";
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
  throw new ApiError(status, userFriendlyMessage, rawMessage);
}

