import 'server-only';

import { createApiClient } from '@salon/api-contracts/catalogue';

import { createServerApiClient, requestSignal } from '@/lib/api-core/client';

/** A service photo as the API answered it, or that it answered none. */
export type ServicePhotoAnswer = { kind: 'image'; bytes: ArrayBuffer } | { kind: 'none'; status: number };

const ID = /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/;

/**
 * A service photo of the salon with this hostname (F05-S01 AC-10), from the API, which serves it to anyone: no
 * credential goes along. A replaced or removed photo is not found.
 */
export async function servicePhoto(hostname: string, id: string): Promise<ServicePhotoAnswer> {
  if (!ID.test(id)) return { kind: 'none', status: 404 };
  const client = createServerApiClient(createApiClient, { hostname });
  if (!client) return { kind: 'none', status: 503 };
  try {
    const { data, response } = await client.GET('/api/v1/catalogue/photos/{id}', {
      params: { path: { id } },
      parseAs: 'arrayBuffer',
      signal: requestSignal(),
    });
    const mediaType = (response.headers.get('content-type') ?? '').split(';')[0]!.trim();
    return response.status === 200 && data && mediaType === 'image/jpeg'
      ? { kind: 'image', bytes: data as ArrayBuffer }
      : { kind: 'none', status: response.status === 200 ? 502 : response.status };
  } catch {
    return { kind: 'none', status: 503 };
  }
}
