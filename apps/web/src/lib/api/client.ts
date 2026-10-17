import { createApiClient, type ApiClient } from '@salon/api-contracts';

import { currentRequest, traceparentOf } from '@/lib/observability/request-context';

import { fetchForHostname } from './forward-hostname';

/** How long the web app waits for the API before it treats the service as unavailable. */
export const API_TIMEOUT_MS = 3000;

/**
 * A client for calls made on the server. The address comes from the environment at request time,
 * so one build runs in every environment, and it is the only address the web app ever connects to:
 * never one taken from a request. Null when it is not configured.
 *
 * @param options.hostname for a salon's calls: the salon's hostname, sent to the API as `Host`
 *   (see {@link fetchForHostname}). Without it, the API sees the configured address's own host.
 */
export function createServerApiClient(options: { hostname?: string } = {}): ApiClient | null {
  const baseUrl = process.env.SALON_API_BASE_URL?.replace(/\/+$/, '');
  if (!baseUrl) {
    return null;
  }
  return createApiClient({
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
export function withTraceparent(send: typeof fetch): typeof fetch {
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
export function remainingBudget(): number {
  return (currentRequest()?.deadline ?? (performance.now() + API_TIMEOUT_MS)) - performance.now();
}

/** One original request budget, shared across proxy, metadata and account API calls. */
export function requestSignal(): AbortSignal {
  const remaining = remainingBudget();
  return remaining <= 0 ? AbortSignal.abort(new DOMException("Request deadline exceeded", "TimeoutError")) : AbortSignal.timeout(Math.ceil(remaining));
}
