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

/** The API's port, as a Host header carries it. */
export function apiPort(): string {
  const { port, protocol } = new URL(apiUrl);
  return port || (protocol === 'https:' ? '443' : '80');
}

/**
 * Several requests written to one connection, and every answer that came back, in order. With
 * `pipelined` the requests are written at once, before any answer; otherwise each waits for the
 * answer to the one before. A HEAD is answered with headers only, which this understands.
 */
export async function sendOnOneConnection(
  requests: string[],
  options: { pipelined?: boolean; timeoutMs?: number } = {},
): Promise<RawResponse[]> {
  const { hostname, port } = new URL(apiUrl);
  const timeoutMs = options.timeoutMs ?? 10_000;
  const methods = requests.map((request) => request.slice(0, request.indexOf(' ')).toUpperCase());
  return new Promise((resolve, reject) => {
    let buffer = Buffer.alloc(0);
    const answers: RawResponse[] = [];
    let sent = 0;
    let settled = false;
    const done = (error?: Error): void => {
      if (settled) {
        return;
      }
      settled = true;
      socket.destroy();
      if (error) {
        reject(error);
      } else {
        resolve(answers);
      }
    };
    const socket = net.connect({ host: hostname, port: Number(port) }, () => {
      if (options.pipelined) {
        socket.write(requests.join(''));
        sent = requests.length;
      } else {
        socket.write(requests[0]);
        sent = 1;
      }
    });
    /** Takes one whole answer off the front of the buffer, or returns false when it is not all there yet. */
    const takeOne = (): boolean => {
      const text = buffer.toString('latin1');
      const headerEnd = text.indexOf('\r\n\r\n');
      if (headerEnd === -1) {
        return false;
      }
      const head = parse(text.slice(0, headerEnd + 4));
      const method = methods[answers.length];
      let bodyLength: number;
      if (method === 'HEAD' || head.status === 204 || head.status === 304) {
        bodyLength = 0;
      } else if ((head.headers['transfer-encoding'] ?? '').toLowerCase().includes('chunked')) {
        const end = text.indexOf('0\r\n\r\n', headerEnd + 4);
        if (end === -1) {
          return false;
        }
        bodyLength = end + 5 - (headerEnd + 4);
      } else {
        bodyLength = Number(head.headers['content-length'] ?? 0);
      }
      const total = headerEnd + 4 + bodyLength;
      if (buffer.length < total) {
        return false;
      }
      answers.push(parse(buffer.subarray(0, total).toString('utf8')));
      buffer = buffer.subarray(total);
      return true;
    };
    socket.setTimeout(timeoutMs, () =>
      done(new Error(`only ${answers.length} of ${requests.length} requests on one connection were answered within ${timeoutMs} ms`)),
    );
    socket.on('data', (chunk) => {
      buffer = Buffer.concat([buffer, chunk]);
      while (answers.length < requests.length && takeOne()) {
        if (answers.length === requests.length) {
          done();
          return;
        }
        if (!options.pipelined && sent === answers.length) {
          socket.write(requests[sent]);
          sent += 1;
        }
      }
    });
    // The server closing the connection early is an answer too: the caller sees how many it got.
    socket.on('end', () => done());
    socket.on('close', () => done());
    socket.on('error', () => done());
  });
}
