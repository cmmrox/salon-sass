import { NextRequest, NextResponse } from 'next/server';
import { requestHost } from '@/features/identity/server';
import { teamPhoto } from '@/features/staff/server';

/** Nothing about a salon is stored by a cache (docs/engineering/web.md, "Tenant routing"). */
const NOT_STORED = 'private, no-cache, no-store, max-age=0, must-revalidate';
const HEADERS = { 'Cache-Control': NOT_STORED, 'X-Content-Type-Options': 'nosniff' };

/**
 * A team member's photo on the salon's own hostname (F06-S01 AC-9): `/team-photos/<id>`, passed through from the API,
 * which serves it to anyone. No session goes along.
 */
export async function GET(request: NextRequest, context: { params: Promise<{ id: string }> }) {
  const host = requestHost(request.headers.get('host'));
  if (!host) return new NextResponse(null, { status: 404, headers: HEADERS });
  const { id } = await context.params;
  const answer = await teamPhoto(host.hostname, id);
  if (answer.kind === 'none') return new NextResponse(null, { status: answer.status, headers: HEADERS });
  return new NextResponse(answer.bytes, { status: 200, headers: { ...HEADERS, 'Content-Type': 'image/jpeg' } });
}
