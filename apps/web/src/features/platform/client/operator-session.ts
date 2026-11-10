'use client';
import {
  consoleSession,
  ensurePrincipal as ensureSessionPrincipal,
  principalInsideLock as principalInsideSessionLock,
  read,
  send,
  withSessionLock,
} from '@/lib/api-core/request';
import type { OperatorPrincipal } from '../types';

/**
 * The browser's side of the console session, over the one request module (`lib/api-core/request.ts`): a refused
 * console operation is its `RequestFailure`, with the stable code, the fields it names, any wait and the HTTP status.
 */
export { RequestFailure as OperatorFailure, supportsLocks } from '@/lib/api-core/request';

/** One call to the console BFF, without renewal: sign-in and the session's own actions. */
export function operatorRequest<T>(action: string, body?: unknown): Promise<T> {
  return send<T>(consoleSession, action, { body });
}

/** Runs a credential change while holding the operator lock; without Web Locks it is refused. */
export function withOperatorLock<T>(work: () => Promise<T>): Promise<T> {
  return withSessionLock(consoleSession, work);
}

/** Called only while holding the lock: one renewal at most, never a retry of a spent family. */
export function principalInsideLock(): Promise<OperatorPrincipal> {
  return principalInsideSessionLock<OperatorPrincipal>(consoleSession);
}

/**
 * The current operator: read first; on an expired session renewed under the cross-tab lock. A
 * browser without Web Locks keeps already-authenticated reads but cannot renew.
 */
export function ensurePrincipal(): Promise<OperatorPrincipal> {
  return ensureSessionPrincipal<OperatorPrincipal>(consoleSession);
}

/** A read made after the principal is known; one renewal under the lock if the access expired meanwhile. */
export function operatorRead<T>(action: string): Promise<T> {
  return read<T>(consoleSession, action);
}
