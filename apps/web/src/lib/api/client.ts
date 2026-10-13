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
 * A `fetch` that sends the W3C `traceparent` of the request being served with every call, so that
 * the API's log lines and spans for the call share the web app's trace id (NFR-16). Outside a
 * request it sends none, and the API starts a trace of its own.
 */
export function withTraceparent(send: typeof fetch): typeof fetch {
  return (input, init) => {
    const context = currentRequest();
    if (!context) {
      return send(input, init);
    }
    const request = new Request(input, init);
    request.headers.set('traceparent', traceparentOf(context));
    return send(request);
  };
}

/** One original request budget, shared across proxy, metadata and account API calls. */
export function requestSignal(): AbortSignal {
  const remaining = (currentRequest()?.deadline ?? (performance.now() + API_TIMEOUT_MS)) - performance.now();
  return remaining <= 0 ? AbortSignal.abort(new DOMException("Request deadline exceeded", "TimeoutError")) : AbortSignal.timeout(Math.ceil(remaining));
}
