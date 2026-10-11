import type { ApiClient, Meta } from '@salon/api-contracts';

import { API_TIMEOUT_MS, createServerApiClient } from './client';

export type MetaResult = { ok: true; meta: Meta } | { ok: false };

/**
 * Asks the API what it is. Never throws: when the API is not configured, cannot be reached, is
 * too slow, answers with an error or answers with something that is not the contract's shape, the
 * result is `{ ok: false }` and the reason is logged on the server.
 */
export async function getMeta(client: ApiClient | null = createServerApiClient()): Promise<MetaResult> {
  if (!client) {
    return unavailable('SALON_API_BASE_URL is not set');
  }
  try {
    const { data, response } = await client.GET('/api/v1/meta', {
      signal: AbortSignal.timeout(API_TIMEOUT_MS),
    });
    if (!data) {
      return unavailable(`the API answered ${response.status}`);
    }
    if (!isMeta(data)) {
      return unavailable('the API answered with something that is not meta');
    }
    return { ok: true, meta: data };
  } catch (error) {
    return unavailable(describe(error));
  }
}

/** The kind of failure, for the server log: "TimeoutError", "TypeError (ECONNREFUSED)". Never the message. */
function describe(error: unknown): string {
  if (!(error instanceof Error)) {
    return 'the request failed';
  }
  const code = (error.cause as { code?: unknown } | undefined)?.code;
  return typeof code === 'string' ? `${error.name} (${code})` : error.name;
}

function isMeta(value: unknown): value is Meta {
  if (typeof value !== 'object' || value === null) {
    return false;
  }
  const candidate = value as Record<string, unknown>;
  return typeof candidate.service === 'string' && typeof candidate.version === 'string';
}

function unavailable(reason: string): MetaResult {
  console.warn(`[api] meta is unavailable: ${reason}`);
  return { ok: false };
}
