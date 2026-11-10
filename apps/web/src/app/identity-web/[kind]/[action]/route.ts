import { NextRequest, NextResponse } from 'next/server';
import { createIdentityWeb } from '@/features/identity/server';
import { salonAccountFor } from '@/features/platform/server';
import { WORKSPACE_ROUTES } from '@/surfaces/workspace/routes';

const handleIdentity = createIdentityWeb(salonAccountFor, WORKSPACE_ROUTES);

export async function GET(request: NextRequest, context: { params: Promise<{ kind: string; action: string }> }) {
  const { kind, action } = await context.params;
  if (kind !== 'staff' && kind !== 'support')
    return NextResponse.json({ code: 'not_found' }, { status: 404, headers: { 'Cache-Control': 'private, no-store' } });
  return handleIdentity(request, kind, action);
}
export const POST = GET;
