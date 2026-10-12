/**
 * The kind of a failed call, for the server log: "TimeoutError", "TypeError (ECONNREFUSED)".
 * Never the message, which may carry an address or a detail of the answer.
 */
export function describeFailure(error: unknown): string {
  if (!(error instanceof Error)) {
    return 'the request failed';
  }
  const code = (error.cause as { code?: unknown } | undefined)?.code ?? (error as { code?: unknown }).code;
  return typeof code === 'string' ? `${error.name} (${code})` : error.name;
}
