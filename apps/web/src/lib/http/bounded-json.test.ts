// @vitest-environment node
import { describe, expect, it } from 'vitest';
import { readBoundedJson } from './bounded-json';

/** A request whose body is streamed in chunks with no Content-Length, or with the one given. */
function streamed(chunks: string[], length?: string): Request {
  let pulled = 0;
  const body = new ReadableStream<Uint8Array>({
    pull(controller) {
      if (pulled < chunks.length) controller.enqueue(new TextEncoder().encode(chunks[pulled++]));
      else controller.close();
    },
  });
  const headers: Record<string, string> = { 'Content-Type': 'application/json' };
  if (length !== undefined) headers['Content-Length'] = length;
  return new Request('http://localhost/x', { method: 'POST', headers, body, duplex: 'half' } as RequestInit);
}

describe('the bounded JSON body reader', () => {
  it('refuses an oversize chunked body without a Content-Length, without reading past the limit', async () => {
    const chunks = Array.from({ length: 100 }, () => 'x'.repeat(64));
    const request = streamed(['{"a":"', ...chunks, '"}']);
    expect(await readBoundedJson(request, 1024)).toEqual({ ok: false });
  });

  it('refuses a body larger than its lying Content-Length allows', async () => {
    expect(await readBoundedJson(streamed([`{"a":"${'x'.repeat(2000)}"}`], '10'), 1024)).toEqual({ ok: false });
  });

  it('refuses a declared Content-Length over the limit before reading, and a malformed one', async () => {
    expect(await readBoundedJson(streamed(['{}'], '1025'), 1024)).toEqual({ ok: false });
    expect(await readBoundedJson(streamed(['{}'], '-1'), 1024)).toEqual({ ok: false });
  });

  it('accepts a body of exactly the limit', async () => {
    const value = `{"a":"${'x'.repeat(1024 - 8)}"}`;
    expect(new TextEncoder().encode(value).byteLength).toBe(1024);
    expect(await readBoundedJson(streamed([value.slice(0, 500), value.slice(500)]), 1024)).toEqual({ ok: true, value: { a: 'x'.repeat(1016) } });
    expect(await readBoundedJson(streamed([value + ' ']), 1024)).toEqual({ ok: false });
  });

  it('reads invalid JSON, an array or nothing as an empty object', async () => {
    expect(await readBoundedJson(streamed(['{"a":']), 1024)).toEqual({ ok: true, value: {} });
    expect(await readBoundedJson(streamed(['[1,2]']), 1024)).toEqual({ ok: true, value: {} });
    expect(await readBoundedJson(new Request('http://localhost/x', { method: 'POST' }), 1024)).toEqual({ ok: true, value: {} });
  });
});
