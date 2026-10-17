import type { ApiClient } from '@salon/api-contracts';
import { requestSignal } from '@/lib/api/client';
import { renewal, type Credentials, type Kind, type Principal } from './server';

/**
 * Where a request for a protected workspace page belongs, decided on the server before anything
 * renders (CR-013), so a visitor without a usable session is redirected and never sees a frame:
 *
 * - `signed-out`: no credential, a revoked or wrong-kind one, or a spent refresh family: sign in;
 * - `signed-in`: the API confirmed the principal: render the workspace with it;
 * - `pending`: renewal is possible, or the API could not say (outage or slow): render the salon's
 *   workspace and let the browser renew under the cross-tab lock, or offer the recoverable state.
 *
 * Renewal itself is never performed here: it stays coordinated in the browser (Web Locks), so two
 * tabs cannot spend one refresh family. Every call shares the request's three-second API budget.
 */
export type Entry = { kind: 'signed-out' } | { kind: 'signed-in'; principal: Principal } | { kind: 'pending' };

/** Whether the request carries any credential at all; no API call. */
export function hasCredentials({ access, refresh }: Credentials): boolean {
  return Boolean(access || refresh);
}

/** Decides entry for a principal of `kind`; never throws. */
export async function workspaceEntry(kind: Kind, credentials: Credentials, client: ApiClient | null): Promise<Entry> {
  if (!hasCredentials(credentials)) return { kind: 'signed-out' };
  if (!client) return { kind: 'pending' };
  try {
    let status = 401;
    if (credentials.access) {
      const answer = await client.GET('/api/v1/identity/me', { headers: { Authorization: `Bearer ${credentials.access}` }, signal: requestSignal() });
      if (answer.data) return answer.data.kind === kind ? { kind: 'signed-in', principal: answer.data } : { kind: 'signed-out' };
      status = answer.response.status;
      // An outage is not a sign-out: the workspace offers its safe retry instead.
      if (status !== 401 && status !== 403) return { kind: 'pending' };
    }
    const state = await renewal(client, credentials, status);
    return state === 'spent' ? { kind: 'signed-out' } : { kind: 'pending' };
  } catch {
    return { kind: 'pending' };
  }
}
