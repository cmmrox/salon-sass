import { NextRequest, NextResponse } from 'next/server';
import { salonSettingsFor } from '@/features/branding/server';
import { createIdentityWeb } from '@/features/identity/server';
import { salonAccountFor } from '@/features/platform/server';

const handleIdentity = createIdentityWeb(salonAccountFor, salonSettingsFor);

export async function GET(request: NextRequest, context: { params: Promise<{ kind: string; action: string }> }) {
  const { kind, action } = await context.params;
  if (kind !== 'staff' && kind !== 'support')
    return NextResponse.json({ code: 'not_found' }, { status: 404, headers: { 'Cache-Control': 'private, no-store' } });
  return handleIdentity(request, kind, action);
}
export const POST = GET;
