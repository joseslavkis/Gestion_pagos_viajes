import { handleApiResponse } from "@/lib/api-error";

export const BASE_API_URL: string =
  (window as { _env_?: { baseApiUrl?: string } })._env_?.baseApiUrl ?? import.meta.env.VITE_BASE_API_URL ?? "";

type JsonRecord = Record<string, unknown>;

type RequestInitJson = Omit<RequestInit, "body"> & {
  body?: JsonRecord;
};

type ApiJsonParse<T> = (json: unknown) => T;

async function requestJson<T>(endpoint: string, init: RequestInitJson, parse: ApiJsonParse<T>): Promise<T> {
  const response = await fetch(BASE_API_URL + endpoint, {
    ...init,
    headers: {
      Accept: "application/json",
      "Content-Type": "application/json",
      ...(init.headers ?? {}),
    },
    body: init.body ? JSON.stringify(init.body) : undefined,
  });

  if (response.ok) {
    const json: unknown = await response.json();
    return parse(json);
  }

  return handleApiResponse(response);
}

export async function apiPost<T>(
  endpoint: string,
  body: JsonRecord,
  parse: ApiJsonParse<T>,
  initOverrides?: Omit<RequestInitJson, "body">,
): Promise<T> {
  return requestJson<T>(
    endpoint,
    {
      method: "POST",
      body,
      ...(initOverrides ?? {}),
    },
    parse,
  );
}

export async function apiGet<T>(
  endpoint: string,
  parse: ApiJsonParse<T>,
  initOverrides?: Omit<RequestInitJson, "body">,
): Promise<T> {
  return requestJson<T>(
    endpoint,
    {
      method: "GET",
      ...(initOverrides ?? {}),
    },
    parse,
  );
}

export async function apiPatch<T>(
  endpoint: string,
  body: JsonRecord,
  parse: ApiJsonParse<T>,
  initOverrides?: Omit<RequestInitJson, "body">,
): Promise<T> {
  return requestJson<T>(
    endpoint,
    {
      method: "PATCH",
      body,
      ...(initOverrides ?? {}),
    },
    parse,
  );
}

export async function apiPut<T>(
  endpoint: string,
  body: JsonRecord,
  parse: ApiJsonParse<T>,
  initOverrides?: Omit<RequestInitJson, "body">,
): Promise<T> {
  return requestJson<T>(
    endpoint,
    {
      method: "PUT",
      body,
      ...(initOverrides ?? {}),
    },
    parse,
  );
}

export async function apiDelete<T>(
  endpoint: string,
  parse: ApiJsonParse<T>,
  initOverrides?: Omit<RequestInitJson, "body">,
): Promise<T> {
  return requestJson<T>(
    endpoint,
    {
      method: "DELETE",
      ...(initOverrides ?? {}),
    },
    parse,
  );
}

export async function apiDownload(
  endpoint: string,
  filename: string,
  initOverrides?: Omit<RequestInitJson, "body">,
): Promise<void> {
  const response = await fetch(BASE_API_URL + endpoint, {
    method: "GET",
    headers: {
      ...(initOverrides?.headers ?? {}),
    },
  });

  if (!response.ok) {
    return handleApiResponse(response);
  }

  const blob = await response.blob();
  const url = URL.createObjectURL(blob);
  const anchor = document.createElement("a");
  anchor.href = url;
  anchor.download = filename;
  document.body.appendChild(anchor);
  anchor.click();
  anchor.remove();
  URL.revokeObjectURL(url);
}
