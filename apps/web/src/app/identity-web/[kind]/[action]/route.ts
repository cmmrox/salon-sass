import { NextRequest, NextResponse } from 'next/server';
import { handleIdentity } from '@/lib/identity/server';
export async function GET(request: NextRequest, context: { params: Promise<{ kind: string; action: string }> }) {
  const { kind, action } = await context.params;
  if (kind !== 'staff' && kind !== 'customer') return NextResponse.json({ code: 'not_found' }, { status: 404 });
  return handleIdentity(request, kind, action);
}
export const POST = GET;
