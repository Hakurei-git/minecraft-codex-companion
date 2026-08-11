export interface HttpClientError {
  statusCode: number;
  message: string;
}

export function asHttpClientError(error: unknown): HttpClientError | null {
  if (error === null || typeof error !== "object") return null;
  const candidate = error as { statusCode?: unknown; message?: unknown };
  if (typeof candidate.statusCode !== "number" || candidate.statusCode < 400 || candidate.statusCode >= 500) return null;
  return {
    statusCode: candidate.statusCode,
    message: typeof candidate.message === "string" && candidate.message.length > 0
      ? candidate.message
      : "Invalid request",
  };
}
