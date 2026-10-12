import { connection } from 'next/server';

import { getMeta } from '@/lib/api/meta';

import { StatusBody } from './Status';

/**
 * Asks the API on every request and shows what it said. It renders inside a Suspense boundary:
 * the loading state is served at once and this replaces it when the API has answered or failed.
 */
export async function LiveStatus({ retryHref }: { retryHref: string }) {
  // Wait for a real request: the API's address is read from the environment the server runs in,
  // not the one it was built in, and the answer must never be prerendered.
  await connection();
  const result = await getMeta();
  return (
    <StatusBody
      retryHref={retryHref}
      state={
        result.ok
          ? { kind: 'loaded', service: result.meta.service, version: result.meta.version }
          : { kind: 'unavailable' }
      }
    />
  );
}
