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
  /** The salon's current rules as customers see them, or JSON null before the first save (F05-S02). */
  publicRules(call: Call): Promise<Answer>;
}

/** The booking-rules calls for one salon's hostname; null when the API is not configured. */
export type SalonBookingRulesFor = (hostname: string) => SalonBookingRules | null;

/**
 * The public rules, read on the Services page to say a service's "Salon rule" deposit in words (F05-S02). Public
 * data, so any signed-in session may read it; it needs no permission.
 */
const RULES_PUBLIC = 'booking-rules-public';

/** The BFF's reads of the booking rules: each `settings.read`, but the public rules. */
export const RULES_READS = ['booking-rules', 'booking-rules-history', RULES_PUBLIC];

/** Its one change, `settings.manage`. */
const RULES_WRITES = ['booking-rules-save'];

/** The permission each action needs. */
export const RULES_PERMISSIONS: Record<string, string> = Object.fromEntries([
  ...RULES_READS.filter((action) => action !== RULES_PUBLIC).map((action) => [action, 'settings.read']),
  ...RULES_WRITES.map((action) => [action, 'settings.manage']),
]);

/**
 * One read: the history names its page in the query (a whole number from 0), the rules nothing. The public rules
 * are answered as `{current}`, null before the first save, so that a JSON null is not taken for a failure.
 */
export async function readRules(
  rules: SalonBookingRules,
  action: string,
  call: Call,
  query: URLSearchParams,
): Promise<Answer> {
  if (action === 'booking-rules') return rules.rules(call);
  if (action === RULES_PUBLIC) {
    const answer = await rules.publicRules(call);
    return answer.response.ok ? { data: { current: answer.data ?? null }, response: answer.response } : answer;
  }
  const page = Number(query.get('page') ?? 0);
  return rules.history(call, Number.isInteger(page) && page >= 0 ? page : 0);
}

/** The save, its values taken from the body by name; booking rebuilds the request from the contract. */
export function changeRules(rules: SalonBookingRules, call: Call, body: Record<string, unknown>) {
  return rules.saveRules(call, body.version, body.rules);
}
