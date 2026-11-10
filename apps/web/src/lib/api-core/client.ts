import type { ApiClientOptions } from '@salon/api-contracts/shared';

import { currentRequest, traceparentOf } from '@/lib/observability/request-context';

import { fetchForHostname } from './forward-hostname';

/** How long the web app waits for the API before it treats the service as unavailable. */
export const API_TIMEOUT_MS = 3000;

/**
 * A module's client for calls made on the server. The address comes from the environment at request
 * time, so one build runs in every environment, and it is the only address the web app ever connects
 * to: never one taken from a request. Null when it is not configured.
 *
 * @param create the module's client factory, `createApiClient` from `@salon/api-contracts/<module>`,
 *   so that a call can only name that module's paths
 * @param options.hostname for a salon's calls: the salon's hostname, sent to the API as `Host`
 *   (see {@link fetchForHostname}). Without it, the API sees the configured address's own host.
 */
export function createServerApiClient<Client>(
  create: (options: ApiClientOptions) => Client,
  options: { hostname?: string } = {},
): Client | null {
  const baseUrl = process.env.SALON_API_BASE_URL?.replace(/\/+$/, '');
  if (!baseUrl) {
    return null;
  }
  return create({
    baseUrl,
    fetch: withTraceparent(options.hostname === undefined ? globalThis.fetch : fetchForHostname(options.hostname)),
  });
}

/**
 * A `fetch` for a server call made while a request is served:
 *
 * - it sends the request's W3C `traceparent`, so that the API's log lines and spans for the call
 *   share the web app's trace id (NFR-16). Outside a request it sends none, and the API starts a
 *   trace of its own;
 * - it ends the call when the request's shared API budget runs out (BUG-F02-012). The caller's
 *   signal reaches the transport only through copies of its `Request`, whose signals follow their
 *   parent weakly: once an intermediate copy is collected, an abort is silently lost and a call to
 *   an unresponsive API waits until it resumes. So the budget is enforced here by a timer, which
 *   holds its controller strongly, and its signal is handed to the transport directly. An abort of
 *   the caller's own signal is forwarded too, while it still arrives.
 */
function withTraceparent(send: typeof fetch): typeof fetch {
  return (input, init) => {
    const request = new Request(input, init);
    const context = currentRequest();
    if (context) {
      request.headers.set('traceparent', traceparentOf(context));
    }
    const controller = new AbortController();
    const expire = () => controller.abort(new DOMException('Request deadline exceeded', 'TimeoutError'));
    const remaining = remainingBudget();
    if (remaining <= 0) expire();
    // Not cleared when the answer starts: its body may still be read, and must end in budget too.
    else setTimeout(expire, Math.ceil(remaining)).unref();
    // The caller's signal as it was given, not this copy's follower of it, which may be collected.
    const caller = init?.signal ?? (input instanceof Request ? input.signal : null);
    if (caller?.aborted) controller.abort(caller.reason);
    else caller?.addEventListener('abort', () => controller.abort(caller.reason), { once: true });
    return send(request, { signal: controller.signal });
  };
}

/** What is left, in milliseconds, of the request's shared API budget; a whole budget outside one. */
function remainingBudget(): number {
  return (currentRequest()?.deadline ?? performance.now() + API_TIMEOUT_MS) - performance.now();
}

/**
 * How long an upload's own call to the API may take (BUG-F04-001): the file, up to the 10 MB upload limit, goes on to
 * the API, which may wait up to two seconds for a decode permit and then decodes it.
 */
export const UPLOAD_API_TIMEOUT_MS = 10_000;

/**
 * How long the operator's holiday calendar import may take at the API (F04-S03), counted from when its file has been
 * read: the API reads and checks every line of a year's CSV (up to 128 KB) and stores the year's days at once.
 */
export const HOLIDAY_IMPORT_API_TIMEOUT_MS = 10_000;

/**
 * How long a salon's export archive may take to arrive from the API (F03-S03), counted from when the request has been
 * read: the archive is read whole, up to its last byte, before it is passed on (`forward-hostname.ts`).
 */
export const EXPORT_DOWNLOAD_API_TIMEOUT_MS = 30_000;

/**
 * Starts the request's shared API budget again, from now, for `length` milliseconds (docs/engineering/web.md,
 * "Structure"): an upload's handler calls it with {@link UPLOAD_API_TIMEOUT_MS} once it has read the form, so that
 * the time a slow connection took to send the file is not taken from the API's budget (BUG-F04-001). Signals made by
 * {@link requestSignal} before it keep their old deadline. Does nothing outside a request.
 */
export function restartRequestBudget(length: number): void {
  const context = currentRequest();
  if (context) context.deadline = performance.now() + length;
}

/** One original request budget, shared across proxy, metadata and account API calls. */
export function requestSignal(): AbortSignal {
  const remaining = remainingBudget();
  return remaining <= 0
    ? AbortSignal.abort(new DOMException('Request deadline exceeded', 'TimeoutError'))
    : AbortSignal.timeout(Math.ceil(remaining));
}
