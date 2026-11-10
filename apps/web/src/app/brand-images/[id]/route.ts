import { NextRequest, NextResponse } from 'next/server';
import { brandImage } from '@/features/branding/server';
import { cookieName, requestHost } from '@/features/identity/server';

import { BRAND_IMAGE_CACHE, NOT_STORED } from '@/lib/http/photo-cache';

/** Every answer but a published image is stored by no cache (`lib/http/photo-cache.ts`). */
const HEADERS = { 'Cache-Control': NOT_STORED, 'X-Content-Type-Options': 'nosniff' };

/**
 * A salon's brand image on its own hostname (F04-S02, decision 086): `/brand-images/<id>`, passed through from
 * the API, which decides whether the caller may see it. The person's session, a staff or a support one, goes
 * along only for an image that is not published.
 */
export async function GET(request: NextRequest, context: { params: Promise<{ id: string }> }) {
  const host = requestHost(request.headers.get('host'));
  if (!host) return new NextResponse(null, { status: 404, headers: HEADERS });
  const { id } = await context.params;
  const access =
    request.cookies.get(cookieName('support', 'access'))?.value ??
    request.cookies.get(cookieName('staff', 'access'))?.value;
  const answer = await brandImage(host.hostname, id, access);
  if (answer.kind === 'none') return new NextResponse(null, { status: answer.status, headers: HEADERS });
  // A published image, which the API gives anyone, may be kept by the browser; a draft fetched with the session never.
  const cache = answer.published ? BRAND_IMAGE_CACHE : NOT_STORED;
  return new NextResponse(answer.bytes, {
    status: 200,
    headers: { ...HEADERS, 'Cache-Control': cache, 'Content-Type': answer.mediaType },
  });
}
