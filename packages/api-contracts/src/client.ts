import createClient, { type Client } from 'openapi-fetch';

import type { paths } from './generated/schema';

/** A client for the API, typed by the generated contract: all of it, or one module's paths. */
export type ApiClient<Paths extends object = paths> = Client<Paths>;

export interface ApiClientOptions {
  /** Where the API is, without a trailing slash: `http://localhost:8090`. */
  baseUrl: string;
  /** The `fetch` to use. Defaults to the platform's. */
  fetch?: typeof globalThis.fetch;
  /** Headers sent with every request. */
  headers?: Record<string, string>;
}

/**
 * Creates a client for the API. Every path, parameter and response is typed from
 * `docs/api/openapi.json`; a call that the contract does not describe does not compile.
 */
export function createApiClient<Paths extends object = paths>(options: ApiClientOptions): ApiClient<Paths> {
  return createClient<Paths>({
    baseUrl: options.baseUrl,
    fetch: options.fetch,
    headers: options.headers,
  });
}
