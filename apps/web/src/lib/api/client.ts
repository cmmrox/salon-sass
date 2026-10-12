import { createApiClient, type ApiClient } from '@salon/api-contracts';

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
    fetch: options.hostname === undefined ? undefined : fetchForHostname(options.hostname),
  });
}
