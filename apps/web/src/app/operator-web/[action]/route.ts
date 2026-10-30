import type { NextRequest } from 'next/server';
import { handleOperator } from '@/features/platform/server';

/** The console BFF: an allowlist of operations on the exact console host (docs/architecture/operator-console.md). */
export async function GET(request: NextRequest, context: { params: Promise<{ action: string }> }) {
  const { action } = await context.params;
  return handleOperator(request, action);
}
export const POST = GET;
