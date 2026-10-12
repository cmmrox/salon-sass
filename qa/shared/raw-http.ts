/**
 * Sends bytes to the API exactly as written. Playwright's request context, like every well-behaved
 * client, repairs a malformed request before it leaves; a test of how the product answers a
 * malformed request has to send one.
 */
import net from 'node:net';

import { apiUrl } from './env';

export type RawResponse = {
  /** The HTTP status, or 0 when the server closed the connection without answering. */
  status: number;
  headers: Record<string, string>;
  body: string;
  /** Everything that came back, for a failure message. */
  raw: string;
};

function dechunk(body: string): string {
  let rest = body;
  let out = '';
  for (;;) {
    const lineEnd = rest.indexOf('\r\n');
    if (lineEnd === -1) {
      return out;
    }
    const size = Number.parseInt(rest.slice(0, lineEnd).split(';')[0], 16);
    if (!Number.isFinite(size) || size === 0) {
      return out;
    }
    out += rest.slice(lineEnd + 2, lineEnd + 2 + size);
    rest = rest.slice(lineEnd + 2 + size + 2);
  }
}

export function parse(raw: string): RawResponse {
  const headerEnd = raw.indexOf('\r\n\r\n');
  const head = headerEnd === -1 ? raw : raw.slice(0, headerEnd);
  const [statusLine, ...headerLines] = head.split('\r\n');
  const status = Number(/^HTTP\/\d(?:\.\d)?\s+(\d{3})/.exec(statusLine ?? '')?.[1] ?? 0);
  const headers: Record<string, string> = {};
  for (const line of headerLines) {
    const colon = line.indexOf(':');
    if (colon > 0) {
      headers[line.slice(0, colon).trim().toLowerCase()] = line.slice(colon + 1).trim();
    }
  }
  let body = headerEnd === -1 ? '' : raw.slice(headerEnd + 4);
  if ((headers['transfer-encoding'] ?? '').toLowerCase().includes('chunked')) {
    body = dechunk(body);
  }
  return { status, headers, body, raw };
}

/**
 * Writes the payload to the API's port and returns what came back once the server closes the
 * connection, has sent a whole response, or the time runs out. A server that neither answers nor
 * closes within the time is reported by rejecting: that is a hang.
 */
export function sendRaw(payload: string | Buffer, timeoutMs = 10_000): Promise<RawResponse> {
  const { hostname, port } = new URL(apiUrl);
  return new Promise((resolve, reject) => {
    const chunks: Buffer[] = [];
    let settled = false;
    const finish = (): void => {
      if (!settled) {
        settled = true;
        socket.destroy();
        resolve(parse(Buffer.concat(chunks).toString('utf8')));
      }
    };
    const socket = net.connect({ host: hostname, port: Number(port) }, () => {
      socket.write(payload, () => undefined);
    });
    socket.setTimeout(timeoutMs, () => {
      if (settled) {
        return;
      }
      if (chunks.length > 0) {
        finish();
        return;
      }
      settled = true;
      socket.destroy();
      reject(new Error(`the API neither answered nor closed the connection within ${timeoutMs} ms`));
    });
    socket.on('data', (chunk) => {
      chunks.push(chunk);
      const soFar = parse(Buffer.concat(chunks).toString('utf8'));
      const length = soFar.headers['content-length'];
      const complete =
        (length !== undefined && Buffer.byteLength(soFar.body) >= Number(length)) ||
        Buffer.concat(chunks).toString('utf8').endsWith('0\r\n\r\n');
      if (soFar.status > 0 && complete) {
        finish();
      }
    });
    socket.on('end', finish);
    socket.on('close', finish);
    // A reset while we are still writing a deliberately oversized request is an answer too.
    socket.on('error', finish);
  });
}

/** The Host header value for the API. */
export function apiHost(): string {
  return new URL(apiUrl).host;
}
