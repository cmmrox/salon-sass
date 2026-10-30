'use client';
import type { OperatorPrincipal } from '../types';

/** A refused console operation: its stable code, the fields it names and any wait. */
export class OperatorFailure extends Error {
  constructor(
    public code: string,
    public fields: string[] = [],
    public retryAfter = 0,
    public renewable = false,
    public status = 0,
  ) {
    super(code);
  }
}

/** Credential changes are serialized across tabs with this Web Lock; nonsecret change events follow. */
export const OPERATOR_LOCK = 'salon:identity:operator';
export const OPERATOR_CHANNEL = 'salon:identity';
export const supportsLocks = () => typeof navigator !== 'undefined' && Boolean(navigator.locks);

export async function operatorRequest<T>(action: string, body?: unknown): Promise<T> {
  let response: Response;
  try {
    response = await fetch(`/operator-web/${action}`, {
      method: body === undefined ? 'GET' : 'POST',
      headers: body === undefined ? {} : { 'Content-Type': 'application/json' },
      body: body === undefined ? undefined : JSON.stringify(body),
      cache: 'no-store',
    });
  } catch {
    throw new OperatorFailure('service_unavailable');
  }
  const data = await response.json().catch(() => ({ code: 'service_unavailable' }));
  if (!response.ok)
    throw new OperatorFailure(
      data.code ?? 'service_unavailable',
      (data.errors ?? []).map((e: { field: string }) => e.field),
      data.retryAfter ?? 0,
      data.renewable ?? false,
      response.status,
    );
  return data as T;
}

/** Runs a credential change while holding the operator lock; without Web Locks it is refused. */
export async function withOperatorLock<T>(work: () => Promise<T>): Promise<T> {
  if (!supportsLocks()) throw new OperatorFailure('browser_unsupported');
  return navigator.locks.request(OPERATOR_LOCK, async () => {
    const result = await work();
    if (typeof BroadcastChannel !== 'undefined') {
      const channel = new BroadcastChannel(OPERATOR_CHANNEL);
      channel.postMessage({ kind: 'operator', event: 'changed' });
      channel.close();
    }
    return result;
  });
}

/** Called only while holding the lock: one renewal at most, never a retry of a spent family. */
export async function principalInsideLock(): Promise<OperatorPrincipal> {
  try {
    return await operatorRequest<OperatorPrincipal>('principal');
  } catch (current) {
    if (!(current instanceof OperatorFailure) || current.code !== 'invalid_session') throw current;
    if (!current.renewable) {
      await operatorRequest('clear', {}).catch(() => undefined);
      throw current;
    }
    try {
      await operatorRequest('refresh', {});
    } catch {
      await operatorRequest('clear', {}).catch(() => undefined);
      throw new OperatorFailure('invalid_session');
    }
    return operatorRequest<OperatorPrincipal>('principal');
  }
}

/**
 * The current operator: read first; on an expired session renewed under the cross-tab lock. A
 * browser without Web Locks keeps already-authenticated reads but cannot renew.
 */
export async function ensurePrincipal(): Promise<OperatorPrincipal> {
  try {
    return await operatorRequest<OperatorPrincipal>('principal');
  } catch (failure) {
    if (!(failure instanceof OperatorFailure) || failure.code !== 'invalid_session') throw failure;
    return withOperatorLock(() => principalInsideLock());
  }
}

/** A read made after the principal is known; one renewal under the lock if the access expired meanwhile. */
export async function operatorRead<T>(action: string): Promise<T> {
  try {
    return await operatorRequest<T>(action);
  } catch (failure) {
    if (!(failure instanceof OperatorFailure) || failure.code !== 'invalid_session') throw failure;
    await withOperatorLock(() => principalInsideLock());
    return operatorRequest<T>(action);
  }
}
