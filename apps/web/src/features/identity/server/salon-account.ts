import 'server-only';

/** What a call the workspace BFF hands on answers: its data or the API's problem, and the response. */
type Answer<Data = unknown> = {
  data?: Data;
  error?: { code?: string; errors?: unknown };
  response: Response;
};

/** The caller's credential and the request's shared API budget, for one call. */
type Call = { headers: Record<string, string | undefined>; signal: AbortSignal };

/** The credential a support view's single-use entry code is exchanged for (F03-S04). */
export type SupportCredential = { accessToken: string; sessionId: string; issuedAt: string; expiresAt: string };

/**
 * The platform module's paths the workspace BFF reaches for a salon user: the salon's entitlements
 * and invoices, its support sessions, and a support view's entry and end. Identity checks the session
 * and the permission first; the platform module makes the calls, and the route serving the BFF hands
 * its implementation in, so that neither module imports the other.
 */
export interface SalonAccount {
  entitlements(call: Call): Promise<Answer>;
  invoices(call: Call, query: { page: number; size: number }): Promise<Answer>;
  supportSessions(call: Call, query: { page: number; size: number }): Promise<Answer>;
  revokeSupportSession(call: Call, id: string): Promise<Answer>;
  enterSupport(signal: AbortSignal, code: string): Promise<Answer<SupportCredential>>;
  endSupport(call: Call): Promise<Answer>;
}

/** The salon account calls for one salon's hostname; null when the API is not configured. */
export type SalonAccountFor = (hostname: string) => SalonAccount | null;
