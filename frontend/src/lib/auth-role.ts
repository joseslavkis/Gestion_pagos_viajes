export function getRoleFromToken(accessToken: string): "ADMIN" | "USER" | null {
  try {
    const payload = JSON.parse(
      atob(
        accessToken
          .split(".")[1]
          .replace(/-/g, "+")
          .replace(/_/g, "/"),
      ),
    ) as { role?: string };

    if (payload.role === "ROLE_ADMIN") return "ADMIN";
    if (payload.role === "ROLE_USER") return "USER";
    return null;
  } catch {
    return null;
  }
}
