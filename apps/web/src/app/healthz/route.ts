import { connection } from 'next/server';

import { isReady } from '@/lib/observability/server-state';

/**
 * The web app's readiness, for the edge and the container's health check: 200 `UP` while it takes
 * traffic, 503 `OUT_OF_SERVICE` from the moment it is told to stop. It says nothing about any salon
 * and never calls the API, so it answers the same on every hostname; the request proxy does not see it.
 */
export async function GET(): Promise<Response> {
  await connection();
  const ready = isReady();
  return Response.json(
    { status: ready ? 'UP' : 'OUT_OF_SERVICE' },
    { status: ready ? 200 : 503, headers: { 'Cache-Control': 'no-store' } },
  );
}
