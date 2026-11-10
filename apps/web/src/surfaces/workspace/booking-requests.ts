'use client';
import { IdentityFailure, type Kind } from '@/features/identity';
import { change, read, workspaceSession } from '@/lib/api-core/request';

/**
 * A read of the Bookings page: the page stays open for a working day, longer than an access credential lasts, so a
 * read refused for an expired credential renews the session under its cross-tab lock and is asked once more.
 */
export function bookingRead<T>(kind: Kind, action: string): Promise<T> {
  return read<T>(workspaceSession(kind), action);
}

/** A change, renewed once the same way. */
export function bookingChange<T>(kind: Kind, action: string, body: unknown): Promise<T> {
  return change<T>(workspaceSession(kind), action, body);
}

/** A failure as the screens branch on it: its stable code and the fields it named. */
export const failureOf = (error: unknown) =>
  error instanceof IdentityFailure ? error : new IdentityFailure('service_unavailable');

/**
 * A hold released so that the request outlives the page (the sheet closed and the page left at once, reloaded or
 * closed): it is sent with `keepalive`, which navigation does not cancel. True when the API released it or it was gone
 * already; false when it could not say (the session needs renewing, or the API was not reached), for the caller to try
 * the ordinary way. A hold never released runs out by itself.
 */
export async function releaseKept(kind: Kind, id: string): Promise<boolean> {
  try {
    const response = await fetch(`/identity-web/${kind}/booking-release`, {
      method: 'POST',
      keepalive: true,
      cache: 'no-store',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ id }),
    });
    return response.ok || response.status === 404;
  } catch {
    return false;
  }
}
