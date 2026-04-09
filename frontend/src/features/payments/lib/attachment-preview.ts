const IMAGE_ATTACHMENT_PATTERN = /\.(jpg|jpeg|png|webp)$/i;

export function isImageAttachment(fileKey: string): boolean {
  const normalized = fileKey.trim();
  if (normalized.length === 0) {
    return false;
  }

  if (normalized.startsWith("data:image")) {
    return true;
  }

  try {
    const url = new URL(normalized);
    return IMAGE_ATTACHMENT_PATTERN.test(url.pathname);
  } catch {
    return IMAGE_ATTACHMENT_PATTERN.test(normalized);
  }
}
