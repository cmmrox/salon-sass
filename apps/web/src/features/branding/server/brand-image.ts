import 'server-only';

import { createApiClient } from '@salon/api-contracts/branding';

import { createServerApiClient, requestSignal } from '@/lib/api-core/client';

/** A brand image as the API answered it, or that it answered none. */
export type BrandImageAnswer =
  { kind: 'image'; bytes: ArrayBuffer; mediaType: string } | { kind: 'none'; status: number };

const ID = /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/;
const MEDIA_TYPES = new Set(['image/png', 'image/jpeg']);

/**
 * A brand image of the salon with this hostname (F04-S02, decision 086), from the API, which decides on every
 * request whether the caller may see it. Asked first with no credential, as a published image is served to
 * anyone (and a stale session must not stop the sign-in page's logo); only when that is not found and the
 * person has a session is it asked again with it, for a draft image a settings user previews.
 */
export async function brandImage(hostname: string, id: string, access: string | undefined): Promise<BrandImageAnswer> {
  if (!ID.test(id)) return { kind: 'none', status: 404 };
  const client = createServerApiClient(createApiClient, { hostname });
  if (!client) return { kind: 'none', status: 503 };
  const ask = async (credential: string | undefined): Promise<BrandImageAnswer> => {
    const { data, response } = await client.GET('/api/v1/salon/brand/images/{id}', {
      params: { path: { id } },
      headers: credential ? { Authorization: `Bearer ${credential}` } : {},
      parseAs: 'arrayBuffer',
      signal: requestSignal(),
    });
    const mediaType = (response.headers.get('content-type') ?? '').split(';')[0]!.trim();
    return response.status === 200 && data && MEDIA_TYPES.has(mediaType)
      ? { kind: 'image', bytes: data as ArrayBuffer, mediaType }
      : { kind: 'none', status: response.status === 200 ? 502 : response.status };
  };
  try {
    const anyone = await ask(undefined);
    return anyone.kind === 'none' && anyone.status === 404 && access ? await ask(access) : anyone;
  } catch {
    return { kind: 'none', status: 503 };
  }
}
