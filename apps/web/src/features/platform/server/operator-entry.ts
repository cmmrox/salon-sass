import 'server-only';
import { requestSignal } from '@/lib/api-core/client';
import type { OperatorPrincipal } from '../types';
import { platformClient } from './client';
import { operatorRenewal, type OperatorCredentials } from './operator-web';

/**
 * Where a request for a protected console page belongs, decided on the server before anything
 * renders: `signed-out` (no usable credential: sign in), `signed-in` (the API confirmed the operator),
 * `pending` (renewal is possible: render and let the browser renew under its cross-tab lock) or
 * `unavailable` (the API answered 5xx or did not answer within the request's budget: render the
 * console's unavailable state at once, BUG-F03-002, so the five-second outage deadline is not spent
 * twice, once here and again by the browser). Never renews here; shares the request's three-second
 * API budget.
 */
export type OperatorEntry =
  | { kind: 'signed-out' }
  | { kind: 'signed-in'; principal: OperatorPrincipal }
  | { kind: 'pending' }
  | { kind: 'unavailable' };

export function hasOperatorCredentials({ access, refresh }: OperatorCredentials): boolean {
  return Boolean(access || refresh);
}

/** Decides entry to the console served under this hostname; never throws. */
export async function operatorEntry(credentials: OperatorCredentials, hostname: string): Promise<OperatorEntry> {
  if (!hasOperatorCredentials(credentials)) return { kind: 'signed-out' };
  const client = platformClient(hostname);
  if (!client) return { kind: 'pending' };
  try {
    let status = 401;
    if (credentials.access) {
      const answer = await client.GET('/api/v1/operator/me', {
        headers: { Authorization: `Bearer ${credentials.access}` },
        signal: requestSignal(),
      });
      if (answer.data)
        return answer.data.kind === 'operator' ? { kind: 'signed-in', principal: answer.data } : { kind: 'signed-out' };
      status = answer.response.status;
      if (status >= 500) return { kind: 'unavailable' };
      if (status !== 401) return { kind: 'pending' };
    }
    const state = await operatorRenewal(hostname, credentials, status);
    return state === 'spent'
      ? { kind: 'signed-out' }
      : state === 'unknown'
        ? { kind: 'unavailable' }
        : { kind: 'pending' };
  } catch {
    // Aborted by the request's budget, refused or reset: the API could not say in time.
    return { kind: 'unavailable' };
  }
}
