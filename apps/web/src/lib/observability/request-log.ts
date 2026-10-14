import { subscribe } from 'node:diagnostics_channel';
import type { IncomingMessage, Server, ServerResponse } from 'node:http';

import { writeLog, type LogSink } from './log';
import { contextFromTraceparent, enterRequest } from './request-context';
import { serverState } from './server-state';
import { clientAddress } from '@/lib/identity/network';

type RequestStart = { request: IncomingMessage; response: ServerResponse; server: Server };

const INSTALLED = Symbol.for('salon.web.request-log');

/**
 * Watches every request the server receives, through Node's own `http.server.request.start`
 * channel, so that it works the same under the production image's server and the local one, with
 * nothing in front of the framework's handler:
 *
 * - the request gets its trace: the caller's W3C `traceparent` is continued, or a new trace starts,
 *   and everything the request runs can read it (calls to the API send it on);
 * - when it is answered, it writes one JSON line (NFR-16) with its method, its path without the
 *   query string, its status, its duration in nanoseconds (`event.duration`), the salon's slug as
 *   `tenant.slug` when its hostname names a salon, and `trace.id`. Nothing else of the request is
 *   written: no query string, no cookie, no header.
 *
 * Installed once per process; a second call does nothing.
 */
export function installRequestLog(sink?: LogSink): void {
  const holder = globalThis as { [INSTALLED]?: boolean };
  if (holder[INSTALLED]) {
    return;
  }
  holder[INSTALLED] = true;
  subscribe('http.server.request.start', (message) => onRequest(message as RequestStart, sink));
}

function onRequest({ request, response, server }: RequestStart, sink: LogSink | undefined): void {
  const started = process.hrtime.bigint();
  const context = contextFromTraceparent(request.headers.traceparent);
  context.deadline = performance.now() + 3000;
  try { context.clientAddress = clientAddress(request.socket.remoteAddress, request.headers['x-forwarded-for']); } catch { /* BFF fails closed without an address. */ }
  enterRequest(context);

  const state = serverState();
  state.servers.add(server);
  state.inFlight += 1;

  let done = false;
  const finish = () => {
    if (done) {
      return;
    }
    done = true;
    state.inFlight -= 1;
    const duration = process.hrtime.bigint() - started;
    const method = request.method ?? 'GET';
    const path = pathOf(request.url);
    const status = response.statusCode;
    writeLog(
      'INFO',
      `${method} ${path} ${status} ${Math.round(Number(duration) / 1e6)} ms`,
      {
        'event.dataset': 'salon-web.request',
        'http.request.method': method,
        'url.path': path,
        'http.response.status_code': status,
        'event.duration': Number(duration),
        'tenant.slug': context.tenantSlug,
        'trace.id': context.traceId,
        'span.id': context.spanId,
      },
      sink,
    );
    state.onIdle?.();
  };
  response.once('finish', finish);
  response.once('close', finish);
}

/** The path a request asked for, without its query string or anything after it. */
export function pathOf(url: string | undefined): string {
  if (!url) {
    return '/';
  }
  if (url.startsWith('/')) {
    return url.split(/[?#]/, 1)[0] ?? '/';
  }
  // The absolute form a proxy may send: `GET http://host/path?query HTTP/1.1`.
  try {
    return new URL(url).pathname;
  } catch {
    return '/';
  }
}
