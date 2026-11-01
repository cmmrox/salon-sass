import { NextRequest, NextResponse } from 'next/server';
import { brandImage } from '@/features/branding/server';
import { cookieName, requestHost } from '@/features/identity/server';

/** Nothing about a salon is stored by a cache (docs/engineering/web.md, "Tenant routing"). */
const NOT_STORED = 'private, no-cache, no-store, max-age=0, must-revalidate';
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
  return new NextResponse(answer.bytes, { status: 200, headers: { ...HEADERS, 'Content-Type': answer.mediaType } });
}
