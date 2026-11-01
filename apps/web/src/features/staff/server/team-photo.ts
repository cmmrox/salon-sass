import 'server-only';

import { createApiClient } from '@salon/api-contracts/staff';

import { createServerApiClient, requestSignal } from '@/lib/api-core/client';

/** A team photo as the API answered it, or that it answered none. */
export type TeamPhotoAnswer = { kind: 'image'; bytes: ArrayBuffer } | { kind: 'none'; status: number };

const ID = /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/;

/**
 * A team member's photo of the salon with this hostname (F06-S01 AC-9), from the API, which serves it to anyone: no
 * credential goes along. A replaced or removed photo, and a removed member's, is not found.
 */
export async function teamPhoto(hostname: string, id: string): Promise<TeamPhotoAnswer> {
  if (!ID.test(id)) return { kind: 'none', status: 404 };
  const client = createServerApiClient(createApiClient, { hostname });
  if (!client) return { kind: 'none', status: 503 };
  try {
    const { data, response } = await client.GET('/api/v1/team/photos/{id}', {
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
