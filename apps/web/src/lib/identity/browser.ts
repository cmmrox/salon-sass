'use client';
import type { Kind, Principal } from './server';
export class IdentityFailure extends Error {
  constructor(public code: string, public fields: string[] = [], public retryAfter = 0, public renewable = false) { super(code); }
}
export const supportsLocks = () => typeof navigator !== 'undefined' && Boolean(navigator.locks);
export async function identityRequest<T>(kind: Kind, action: string, body?: unknown): Promise<T> {
  let response: Response;
  try {
    response = await fetch(`/identity-web/${kind}/${action}`, { method: body === undefined ? 'GET' : 'POST', headers: body === undefined ? {} : { 'Content-Type': 'application/json' }, body: body === undefined ? undefined : JSON.stringify(body), cache: 'no-store' });
  } catch { throw new IdentityFailure('service_unavailable'); }
  const data = await response.json().catch(() => ({ code: 'service_unavailable' }));
  if (!response.ok) throw new IdentityFailure(data.code ?? 'service_unavailable', (data.errors ?? []).map((e: { field: string }) => e.field), data.retryAfter ?? 0, data.renewable ?? false);
  return data as T;
}
export async function withIdentityLock<T>(kind: Kind, work: () => Promise<T>): Promise<T> {
  if (!supportsLocks()) throw new IdentityFailure('browser_unsupported');
  return navigator.locks.request(`salon:identity:${kind}`, async () => {
    const result = await work();
    if (typeof BroadcastChannel !== 'undefined') { const channel = new BroadcastChannel('salon:identity'); channel.postMessage({ kind, event: 'changed' }); channel.close(); }
    return result;
  });
}
/** Called only while already holding the kind lock. */
export async function principalInsideLock(kind: Kind): Promise<Principal> {
  try { return await identityRequest<Principal>(kind, 'principal'); }
  catch (current) {
    if (!(current instanceof IdentityFailure) || current.code !== 'invalid_session') throw current;
    if (!current.renewable) { await identityRequest(kind, 'clear', {}); throw current; }
    try { await identityRequest(kind, 'refresh', {}); }
    catch {
      // The server may have committed rotation before the connection was lost.
      await identityRequest(kind, 'clear', {}).catch(() => undefined);
      // This family cannot safely renew again, even if clearing also lost its response.
      // Fresh sign-in is the recovery path; an ordinary principal outage stays an outage.
      throw new IdentityFailure('invalid_session');
    }
    return identityRequest<Principal>(kind, 'principal');
  }
}
/** Recheck after acquiring the cross-tab lock; never retry a spent or unknown refresh. */
export async function ensurePrincipal(kind: Kind): Promise<Principal> {
  try { return await identityRequest<Principal>(kind, 'principal'); }
  catch (failure) {
    if (!(failure instanceof IdentityFailure) || failure.code !== 'invalid_session') throw failure;
    return withIdentityLock(kind, () => principalInsideLock(kind));
  }
}
/**
 * Leaves a session the API no longer accepts: staff go to sign-in; a support view (F03-S04) goes to
 * its entry page, told it ended, with a full load so nothing of the support view stays on screen.
 */
export function leaveEndedSession(kind: Kind, router: { replace(href: string): void; refresh(): void }) {
  if (kind === 'support') { window.location.replace('/workspace/support#ended'); return; }
  router.replace('/workspace/sign-in'); router.refresh();
}
