'use client';
import {
  ensurePrincipal,
  IdentityFailure,
  identityRequest,
  principalInsideLock,
  supportsLocks,
  withIdentityLock,
  type Kind,
} from '@/features/identity';

/**
 * A read of the Bookings page: the page stays open for a working day, longer than an access credential lasts, so a
 * read refused for an expired credential renews the session under its cross-tab lock and is asked once more.
 */
export async function bookingRead<T>(kind: Kind, action: string): Promise<T> {
  try {
    return await identityRequest<T>(kind, action);
  } catch (error) {
    if (!(error instanceof IdentityFailure) || error.code !== 'invalid_session' || !error.renewable) throw error;
    await ensurePrincipal(kind);
    return identityRequest<T>(kind, action);
  }
}

/** A change, made under the session's cross-tab lock as every credential-bearing change is. */
export async function bookingChange<T>(kind: Kind, action: string, body: unknown): Promise<T> {
  if (!supportsLocks()) throw new IdentityFailure('browser_unsupported');
  return withIdentityLock(kind, async () => {
    await principalInsideLock(kind);
    return identityRequest<T>(kind, action, body);
  });
}

/** A failure as the screens branch on it: its stable code and the fields it named. */
export const failureOf = (error: unknown) =>
  error instanceof IdentityFailure ? error : new IdentityFailure('service_unavailable');

/**
 * A hold released as the page goes away (closed, reloaded or left): the request outlives the page, and nothing waits
 * for it. The hold runs out by itself if it does not arrive.
 */
export function releaseOnLeaving(kind: Kind, id: string) {
  try {
    void fetch(`/identity-web/${kind}/booking-release`, {
      method: 'POST',
      keepalive: true,
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ id }),
    }).catch(() => undefined);
  } catch {
    // The page is going; the hold runs out by itself.
  }
}
