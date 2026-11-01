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
 * The staff module's settings paths the workspace BFF reaches for a settings user (F04-S03): the weekly
 * opening hours, the published holidays and the salon's choices, and special closures. Identity checks the
 * session and the permission first; staff makes the calls and builds each request from the contract's own
 * fields, and the route serving the BFF hands its implementation in, so that neither module imports the other.
 */
export interface SalonHours {
  hours(call: Call): Promise<Answer>;
  saveHours(call: Call, version: unknown, weekly: unknown): Promise<Answer>;
  holidays(call: Call): Promise<Answer>;
  acceptHolidays(call: Call, year: unknown, version: unknown, choices: unknown): Promise<Answer>;
  chooseHoliday(call: Call, year: unknown, date: unknown, choice: unknown): Promise<Answer>;
  closures(call: Call): Promise<Answer>;
  closureImpact(call: Call, date: string): Promise<Answer>;
  addClosure(call: Call, date: unknown, reason: unknown): Promise<Answer>;
  reopenClosure(call: Call, id: unknown): Promise<Answer>;
}

/** The hours calls for one salon's hostname; null when the API is not configured. */
export type SalonHoursFor = (hostname: string) => SalonHours | null;

/** The BFF's reads of hours, holidays and closures, each `settings.read`. */
export const HOURS_READS = ['hours', 'holidays', 'closures', 'closure-impact'];

/** Its changes, each `settings.manage`. */
export const HOURS_WRITES = ['hours-save', 'holidays-accept', 'holiday-choose', 'closure-add', 'closure-reopen'];

/** The permission each action needs. */
export const HOURS_PERMISSIONS: Record<string, string> = Object.fromEntries([
  ...HOURS_READS.map((action) => [action, 'settings.read']),
  ...HOURS_WRITES.map((action) => [action, 'settings.manage']),
]);

/** One read: the closure impact names its date in the query, every other read nothing. */
export function readHours(hours: SalonHours, action: string, call: Call, query: URLSearchParams): Promise<Answer> {
  switch (action) {
    case 'hours':
      return hours.hours(call);
    case 'holidays':
      return hours.holidays(call);
    case 'closures':
      return hours.closures(call);
    default:
      return hours.closureImpact(call, query.get('date') ?? '');
  }
}

/** One change, its values taken from the body by name; staff rebuilds the request from the contract. */
export function changeHours(
  hours: SalonHours,
  action: string,
  call: Call,
  body: Record<string, unknown>,
): Promise<Answer> {
  switch (action) {
    case 'hours-save':
      return hours.saveHours(call, body.version, body.weekly);
    case 'holidays-accept':
      return hours.acceptHolidays(call, body.year, body.version, body.choices);
    case 'holiday-choose':
      return hours.chooseHoliday(call, body.year, body.date, body);
    case 'closure-add':
      return hours.addClosure(call, body.date, body.reason);
    default:
      return hours.reopenClosure(call, body.id);
  }
}
