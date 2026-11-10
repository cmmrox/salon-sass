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
 * The platform module's paths a support view's own session actions reach (F03-S04): its entry and its end. The
 * salon's account reads and changes are the platform module's own routes; the route serving the BFF hands this in, so
 * that neither module imports the other.
 */
export interface SalonAccount {
  enterSupport(signal: AbortSignal, code: string): Promise<Answer<SupportCredential>>;
  endSupport(call: Call): Promise<Answer>;
}

/** The salon account calls for one salon's hostname; null when the API is not configured. */
export type SalonAccountFor = (hostname: string) => SalonAccount | null;
