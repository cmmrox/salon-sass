import 'server-only';

/** What a call the workspace BFF hands on answers: its data or the API's problem, and the response. */
type Answer = {
  data?: unknown;
  error?: { code?: string; errors?: unknown };
  response: Response;
};

/** The caller's credential and the request's shared API budget, for one call. */
type Call = { headers: Record<string, string | undefined>; signal: AbortSignal };

/**
 * The booking module's settings paths the workspace BFF reaches for a settings user (F04-S04): the booking
 * rules, their save and their history. Identity checks the session and the permission first; booking makes the
 * calls and builds each request from the contract's own fields, and the route serving the BFF hands its
 * implementation in, so that neither module imports the other.
 */
export interface SalonBookingRules {
  rules(call: Call): Promise<Answer>;
  saveRules(call: Call, version: unknown, rules: unknown): Promise<Answer>;
  history(call: Call, page: number): Promise<Answer>;
}

/** The booking-rules calls for one salon's hostname; null when the API is not configured. */
export type SalonBookingRulesFor = (hostname: string) => SalonBookingRules | null;

/** The BFF's reads of the booking rules, each `settings.read`. */
export const RULES_READS = ['booking-rules', 'booking-rules-history'];

/** Its one change, `settings.manage`. */
const RULES_WRITES = ['booking-rules-save'];

/** The permission each action needs. */
export const RULES_PERMISSIONS: Record<string, string> = Object.fromEntries([
  ...RULES_READS.map((action) => [action, 'settings.read']),
  ...RULES_WRITES.map((action) => [action, 'settings.manage']),
]);

/** One read: the history names its page in the query (a whole number from 0), the rules nothing. */
export function readRules(rules: SalonBookingRules, action: string, call: Call, query: URLSearchParams) {
  if (action === 'booking-rules') return rules.rules(call);
  const page = Number(query.get('page') ?? 0);
  return rules.history(call, Number.isInteger(page) && page >= 0 ? page : 0);
}

/** The save, its values taken from the body by name; booking rebuilds the request from the contract. */
export function changeRules(rules: SalonBookingRules, call: Call, body: Record<string, unknown>) {
  return rules.saveRules(call, body.version, body.rules);
}
