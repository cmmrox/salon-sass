import { createApiClient, type ApiClient, type components } from '@salon/api-contracts/shared';

import { strictInstant } from '@/lib/http/network';

import { API_TIMEOUT_MS, createServerApiClient, requestSignal } from './client';
import { describeFailure } from './failure';

/** What the service says about itself: `GET /api/v1/meta`. */
type Meta = components['schemas']['Meta'];

export type MetaResult = { ok: true; meta: Meta } | { ok: false };

/**
 * Asks the API what it is. Never throws: when the API is not configured, cannot be reached, is
 * too slow, answers with an error or answers with something that is not the contract's shape, the
 * result is `{ ok: false }` and the reason is logged on the server.
 */
export async function getMeta(client: ApiClient | null = createServerApiClient(createApiClient)): Promise<MetaResult> {
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
    return unavailable(describeFailure(error));
  }
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

/**
 * The API's clock: the instant `GET /api/v1/meta` answers with, asked within the request's shared
 * budget and with the hostname the caller's other calls use. Null when the API is not configured or
 * answers without a valid instant; a failed call throws, for the caller to treat as it treats its own.
 */
export async function apiTime(hostname?: string): Promise<string | null> {
  const client = createServerApiClient(createApiClient, { hostname });
  if (!client) return null;
  const meta = await client.GET('/api/v1/meta', { signal: requestSignal() });
  return meta.data && strictInstant(meta.data.time) ? meta.data.time : null;
}
