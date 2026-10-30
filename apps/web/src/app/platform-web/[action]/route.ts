import type { NextRequest } from 'next/server';
import { handlePlatform } from '@/features/platform/server';

/** The platform host's BFF: the Owner's export download (F03-S03). */
export async function POST(request: NextRequest, context: { params: Promise<{ action: string }> }) {
  const { action } = await context.params;
  return handlePlatform(request, action);
}
export const GET = POST;
