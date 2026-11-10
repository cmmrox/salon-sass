'use client';
import {
  ensurePrincipal as ensureSessionPrincipal,
  principalInsideLock as principalInsideSessionLock,
  send,
  withSessionLock,
  workspaceSession,
} from '@/lib/api-core/request';
import type { Kind, Principal } from '../types';

/**
 * The browser's side of the workspace session: sign-in, the principal, renewal and sign-out, over the one request
 * module (`lib/api-core/request.ts`), which every other read and change also uses.
 */
export { RequestFailure as IdentityFailure, supportsLocks } from '@/lib/api-core/request';

/** One call to the workspace BFF, without renewal: sign-in and the session's own actions. */
export function identityRequest<T>(kind: Kind, action: string, body?: unknown): Promise<T> {
  return send<T>(workspaceSession(kind), action, { body });
}
export function withIdentityLock<T>(kind: Kind, work: () => Promise<T>): Promise<T> {
  return withSessionLock(workspaceSession(kind), work);
}
/** Called only while already holding the kind lock. */
export function principalInsideLock(kind: Kind): Promise<Principal> {
  return principalInsideSessionLock<Principal>(workspaceSession(kind));
}
/** Recheck after acquiring the cross-tab lock; never retry a spent or unknown refresh. */
export function ensurePrincipal(kind: Kind): Promise<Principal> {
  return ensureSessionPrincipal<Principal>(workspaceSession(kind));
}
/**
 * Leaves a session the API no longer accepts: staff go to sign-in; a support view (F03-S04) goes to
 * its entry page, told it ended, with a full load so nothing of the support view stays on screen.
 */
export function leaveEndedSession(kind: Kind, router: { replace(href: string): void; refresh(): void }) {
  if (kind === 'support') {
    window.location.replace('/workspace/support#ended');
    return;
  }
  router.replace('/workspace/sign-in');
  router.refresh();
}
