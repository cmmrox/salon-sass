import { NextRequest, NextResponse } from 'next/server';
import { servicePhoto } from '@/features/catalogue/server';
import { requestHost } from '@/features/identity/server';

/** Nothing about a salon is stored by a cache (docs/engineering/web.md, "Tenant routing"). */
const NOT_STORED = 'private, no-cache, no-store, max-age=0, must-revalidate';
const HEADERS = { 'Cache-Control': NOT_STORED, 'X-Content-Type-Options': 'nosniff' };

/**
 * A service photo on the salon's own hostname (F05-S01 AC-10, decision 093): `/service-photos/<id>`, passed
 * through from the API, which serves it to anyone. No session goes along.
 */
export async function GET(request: NextRequest, context: { params: Promise<{ id: string }> }) {
  const host = requestHost(request.headers.get('host'));
  if (!host) return new NextResponse(null, { status: 404, headers: HEADERS });
  const { id } = await context.params;
  const answer = await servicePhoto(host.hostname, id);
  if (answer.kind === 'none') return new NextResponse(null, { status: answer.status, headers: HEADERS });
  return new NextResponse(answer.bytes, { status: 200, headers: { ...HEADERS, 'Content-Type': 'image/jpeg' } });
}
