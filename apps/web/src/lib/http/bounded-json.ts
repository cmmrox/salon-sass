/**
 * A BFF request body read with a hard byte limit, failing closed: a declared `Content-Length` above
 * the limit is refused before anything is read, and the body itself is streamed and counted, so a
 * chunked body without a length, or one whose declared length is false, is never read past the limit.
 * Every BFF reads its JSON body through this (identity, console and the platform host).
 *
 * A body within the limit that is not a JSON object (invalid JSON, an array, a number) reads as an
 * empty object, as before: the API then names the missing fields.
 */
export type BoundedJson = { ok: true; value: Record<string, unknown> } | { ok: false };

export async function readBoundedJson(request: Request, limit: number): Promise<BoundedJson> {
  const declared = request.headers.get('content-length');
  if (declared !== null && (!/^\d+$/.test(declared.trim()) || Number(declared) > limit)) return { ok: false };
  if (!request.body) return { ok: true, value: {} };
  const reader = request.body.getReader();
  const chunks: Uint8Array[] = [];
  let size = 0;
  try {
    for (;;) {
      const { done, value } = await reader.read();
      if (done) break;
      size += value.byteLength;
      if (size > limit) {
        await reader.cancel().catch(() => undefined);
        return { ok: false };
      }
      chunks.push(value);
    }
  } catch {
    return { ok: false };
  }
  const bytes = new Uint8Array(size);
  let offset = 0;
  for (const chunk of chunks) { bytes.set(chunk, offset); offset += chunk.byteLength; }
  try {
    const parsed: unknown = JSON.parse(new TextDecoder('utf-8', { fatal: true }).decode(bytes));
    return { ok: true, value: parsed !== null && typeof parsed === 'object' && !Array.isArray(parsed) ? (parsed as Record<string, unknown>) : {} };
  } catch {
    return { ok: true, value: {} };
  }
}
