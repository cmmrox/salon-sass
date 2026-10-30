import { AsyncLocalStorage } from 'node:async_hooks';
import { randomBytes } from 'node:crypto';

/**
 * What the web app knows about the request it is serving, on the server: the trace it belongs to,
 * and the salon its hostname names once the API has said so. Available to everything the request
 * runs (the request proxy, a page, a call to the API) without being passed along.
 */
export type RequestContext = {
  /** The W3C trace id: 32 lowercase hex characters, never all zeros. */
  traceId: string;
  /** This request's own span id in that trace: 16 lowercase hex characters. */
  spanId: string;
  /** Whether the trace is sampled; the API samples every request whatever this says. */
  sampled: boolean;
  /** The salon's slug, set once the API has said the hostname names a salon. */
  tenantSlug?: string;
  clientAddress?: string;
  deadline?: number;
};

// One store for the whole process. The request proxy, the pages and the instrumentation hook are
// bundled separately and each gets its own copy of this module; they meet on the global object.
const STORE = Symbol.for('salon.web.request-context');

function storage(): AsyncLocalStorage<RequestContext> {
  const holder = globalThis as { [STORE]?: AsyncLocalStorage<RequestContext> };
  holder[STORE] ??= new AsyncLocalStorage<RequestContext>();
  return holder[STORE];
}

/** The request being served, or undefined outside one. */
export function currentRequest(): RequestContext | undefined {
  return storage().getStore();
}

/**
 * Makes this the request for the rest of the current execution and everything it starts. Used
 * where the server receives a request, before its handler runs.
 */
export function enterRequest(context: RequestContext): void {
  storage().enterWith(context);
}

/** Runs `work` as part of this request. */
export function runInRequest<T>(context: RequestContext, work: () => T): T {
  return storage().run(context, work);
}

/** Records that the request's hostname names this salon. Does nothing outside a request. */
export function markTenant(slug: string): void {
  const context = currentRequest();
  if (context) {
    context.tenantSlug = slug;
  }
}

const TRACEPARENT = /^([0-9a-f]{2})-([0-9a-f]{32})-([0-9a-f]{16})-([0-9a-f]{2})(-.*)?$/;

/**
 * The context of a request that arrived with this `traceparent` header: its trace is continued
 * when the header is a valid W3C trace context, and a new one is started otherwise. Either way the
 * request gets a span id of its own.
 */
export function contextFromTraceparent(header: string | string[] | undefined): RequestContext {
  const value = Array.isArray(header) ? header[0] : header;
  const match = value === undefined ? null : TRACEPARENT.exec(value.trim());
  if (match) {
    const [, version, traceId, parentId, flags, rest] = match as unknown as [
      string,
      string,
      string,
      string,
      string,
      string?,
    ];
    const valid =
      version !== 'ff' && (version !== '00' || rest === undefined) && !/^0+$/.test(traceId) && !/^0+$/.test(parentId);
    if (valid) {
      return { traceId, spanId: newSpanId(), sampled: (parseInt(flags, 16) & 1) === 1 };
    }
  }
  return { traceId: newTraceId(), spanId: newSpanId(), sampled: true };
}

/** The `traceparent` header a call made for this request sends: this request's span is its parent. */
export function traceparentOf(context: RequestContext): string {
  return `00-${context.traceId}-${context.spanId}-${context.sampled ? '01' : '00'}`;
}

function newTraceId(): string {
  return nonZeroHex(16);
}

function newSpanId(): string {
  return nonZeroHex(8);
}

function nonZeroHex(bytes: number): string {
  for (;;) {
    const value = randomBytes(bytes).toString('hex');
    if (!/^0+$/.test(value)) {
      return value;
    }
  }
}
