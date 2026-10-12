import { request as httpRequest, type IncomingMessage } from 'node:http';
import { request as httpsRequest } from 'node:https';

/**
 * A `fetch` for the generated client that tells the API which salon a call is for by the hostname
 * it sends: the request goes to the address it was made for (the API's one configured address),
 * with `Host` set to the salon's hostname. The API reads the salon from `Host` and nothing else
 * (docs/architecture/multi-tenancy.md), so no parameter or custom header names the salon.
 *
 * The platform's `fetch` cannot do this: it always sends the address's own host as `Host`. This
 * sends the request with Node's HTTP client instead, which sends the header it is given.
 *
 * Only what the client put on the request is sent: nothing of the browser's request reaches the API.
 *
 * @param hostname the salon's hostname, `<slug>.<platform domain>`, without a port
 */
export function fetchForHostname(hostname: string): typeof fetch {
  return async (input, init) => {
    const request = new Request(input, init);
    const url = new URL(request.url);
    const send = url.protocol === 'https:' ? httpsRequest : httpRequest;
    const headers: Record<string, string> = {};
    request.headers.forEach((value, name) => {
      headers[name] = value;
    });
    headers.host = hostname;
    const body = request.body ? Buffer.from(await request.arrayBuffer()) : undefined;
    request.signal.throwIfAborted();

    return new Promise<Response>((resolve, reject) => {
      const outgoing = send(url, { method: request.method, headers, signal: request.signal }, (incoming) => {
        readBody(incoming).then(
          (content) => resolve(toResponse(incoming, content)),
          (error: unknown) => reject(request.signal.aborted ? request.signal.reason : error),
        );
      });
      outgoing.on('error', (error) => reject(request.signal.aborted ? request.signal.reason : error));
      outgoing.end(body);
    });
  };
}

function readBody(incoming: IncomingMessage): Promise<Buffer> {
  return new Promise((resolve, reject) => {
    const chunks: Buffer[] = [];
    incoming.on('data', (chunk: Buffer) => chunks.push(chunk));
    incoming.on('end', () => resolve(Buffer.concat(chunks)));
    incoming.on('error', reject);
    incoming.on('aborted', () => reject(new Error('the response was cut short')));
  });
}

function toResponse(incoming: IncomingMessage, content: Buffer): Response {
  const headers = new Headers();
  for (const [name, value] of Object.entries(incoming.headers)) {
    for (const item of Array.isArray(value) ? value : value === undefined ? [] : [value]) {
      headers.append(name, item);
    }
  }
  const status = incoming.statusCode ?? 500;
  // A status that carries no body is answered without one, as the platform's fetch does.
  const empty = status === 204 || status === 205 || status === 304;
  return new Response(empty ? null : new Uint8Array(content), {
    status,
    statusText: incoming.statusMessage,
    headers,
  });
}
