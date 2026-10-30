import 'server-only';
import { requestSignal } from '@/lib/api-core/client';
import type { Entitlements } from '../types';
import { platformClient } from './client';

type Call = { headers: Record<string, string | undefined>; signal: AbortSignal };
type Page = { page: number; size: number };

/**
 * The platform paths the workspace BFF reaches for a salon user (F03), under the salon's hostname:
 * the salon's entitlements and invoices, its support sessions, and a support view's entry and end.
 * The workspace BFF checks the session and the permission before any of them. Null when the API is
 * not configured.
 */
export function salonAccountFor(hostname: string) {
  const client = platformClient(hostname);
  if (!client) return null;
  return {
    entitlements: (call: Call) => client.GET('/api/v1/salon/entitlements', call),
    invoices: (call: Call, query: Page) => client.GET('/api/v1/salon/invoices', { ...call, params: { query } }),
    supportSessions: (call: Call, query: Page) =>
      client.GET('/api/v1/salon/support-sessions', { ...call, params: { query } }),
    revokeSupportSession: (call: Call, id: string) =>
      client.DELETE('/api/v1/salon/support-sessions/{id}', { ...call, params: { path: { id } } }),
    enterSupport: (signal: AbortSignal, code: string) =>
      client.POST('/api/v1/support/entry', { signal, body: { code } }),
    endSupport: (call: Call) => client.DELETE('/api/v1/support/session', call),
  };
}

/** The salon's entitlements for the billing banner, read with the user's access; null when the API cannot say. */
export async function readEntitlements(hostname: string, access: string): Promise<Entitlements | null> {
  const client = platformClient(hostname);
  if (!client) return null;
  try {
    const answer = await client.GET('/api/v1/salon/entitlements', {
      headers: { Authorization: `Bearer ${access}` },
      signal: requestSignal(),
    });
    return answer.data ?? null;
  } catch {
    return null;
  }
}
