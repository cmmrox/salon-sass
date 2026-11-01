/**
 * F04-S03 fixtures: the salon's opening hours, holiday choices and closures (salon host), the
 * operator's holiday calendar (console host), week and CSV builders, a fresh calendar year per
 * test, and read-only database observations of the four staff tables and the audit trails.
 *
 * Routes, bodies, codes and shapes come from the F04-S03 spec ("API and data changes", its
 * corrections at close) and the generated contract (docs/api/openapi.json). Table names are the
 * ones the stage's migrations create (handoff note 5), observed only, never written.
 *
 * The holiday calendar is platform data shared by every salon of the database and a published year
 * cannot be withdrawn, so a run must not use up years:
 * - A test that only needs some published days to choose from uses the shared year (2099, `SHARED`):
 *   published once per database with fixed days and never changed by any test. It chooses with a
 *   salon the operator creates in the test (or reads only), so earlier runs' choices never matter,
 *   and sets the clock to `SHARED.today` so the days are still to come.
 * - A test that imports, edits or publishes takes a year no earlier test has touched (`freshYear`,
 *   2028 to 2098) and starts it at once, which reserves it; about seven a run, so a database holds
 *   ten runs before it stops with `ENVIRONMENT:` (run the pass on a fresh database).
 * - 2100 is never started, so a salon whose clock stands in it has no published year from its own
 *   (the holidays card's empty state).
 * Dates in these years are far ahead, so a test that needs "today" inside one sets the test clock.
 */
import { randomBytes, randomInt } from 'node:crypto';
import { request, type APIRequestContext, type APIResponse } from '@playwright/test';

import type { components } from '@contracts/schema';

import { answerOf, expectProblem } from './answers';
import { apiUrl } from './env';
import { expect, json, type Identity, type Tokens } from './identity';
import { identitySql } from './identity-storage';
import { CONSOLE_HOST, onSalonHost, sqlLiteral, type OperatorActor } from './operator';
import type { Salon } from './tenants';

export const OPENING_HOURS = '/api/v1/salon/opening-hours';
export const HOURS_SETTINGS = '/api/v1/salon/opening-hours/settings';
export const HOLIDAYS = '/api/v1/salon/holidays';
export const CLOSURES = '/api/v1/salon/closures';
export const CALENDARS = '/api/v1/operator/holiday-calendars';

export type Weekday = components['schemas']['OpeningWeekdayInput'];
export type HoursSettings = components['schemas']['OpeningHoursSettings'];
export type PublicHours = components['schemas']['SalonOpeningHours'];
export type SalonHolidays = components['schemas']['SalonHolidays'];
export type SalonHolidayYear = components['schemas']['SalonHolidayYear'];
export type ChoiceInput = components['schemas']['SalonHolidayChoiceInput'];
export type Closure = components['schemas']['SalonClosure'];
export type ClosureImpact = components['schemas']['ClosureImpact'];
export type YearDetail = components['schemas']['HolidayYearDetail'];
export type YearList = components['schemas']['HolidayYearList'];
export type Kind = 'poya' | 'public' | 'bank' | 'mercantile';
export type Day = { date: string; name: string; kind: Kind };

export const DAYS = ['monday', 'tuesday', 'wednesday', 'thursday', 'friday', 'saturday', 'sunday'] as const;
export type DayName = typeof DAYS[number];

/** The four tenant tables this stage adds (handoff note 5). */
export const HOURS_TABLES = ['staff_opening_hours', 'staff_holiday_review', 'staff_holiday_choice', 'staff_closure'] as const;

/** A year no test starts (see above). */
export const UNPUBLISHED_YEAR = 2100;

/** The shared year (see above): its fixed days, and the local date the clock is set to when using it. */
export const SHARED = {
  year: 2099,
  today: '2099-01-10',
  past: { date: '2099-01-05', name: 'QA shared past holiday', kind: 'public' } as Day,
  days: [
    { date: '2099-01-20', name: 'Duruthu Full Moon Poya Day', kind: 'poya' },
    { date: '2099-01-29', name: 'Tamil Thai Pongal Day', kind: 'public' },
    { date: '2099-02-07', name: 'Independence Day', kind: 'bank' },
    { date: '2099-02-16', name: 'Sinhala and Tamil New Year Day', kind: 'mercantile' },
  ] as Day[],
};

export const unique = (prefix = 'QA') => `${prefix} ${randomBytes(4).toString('hex')}`;

/** A week: every day open `opens`-`closes` except those named closed. */
export function week(opens = '09:00', closes = '18:00', closed: DayName[] = ['sunday'], overrides: Partial<Record<DayName, [string, string]>> = {}): Weekday[] {
  return DAYS.map(day => {
    if (closed.includes(day)) return { day, open: false, opens: null, closes: null };
    const [o, c] = overrides[day] ?? [opens, closes];
    return { day, open: true, opens: o, closes: c };
  });
}

/** The suggested week of AC-1, which the form shows before the first save. */
export const SUGGESTED_WEEK = week('09:00', '18:00', ['sunday'], { saturday: ['09:00', '19:00'] });

/** What a stored week reads back as (closed days carry null times). */
export const stored = (input: Weekday[]) => input.map(d => ({ day: d.day, open: d.open, opens: d.open ? d.opens : null, closes: d.open ? d.closes : null }));

// ---- Dates (local dates are plain YYYY-MM-DD strings, worked in UTC so no zone leaks in) ----

export const plusDays = (date: string, days: number) => new Date(Date.parse(`${date}T00:00:00Z`) + days * 86_400_000).toISOString().slice(0, 10);
export const weekdayOf = (date: string): DayName => DAYS[(new Date(`${date}T00:00:00Z`).getUTCDay() + 6) % 7];
/** The first date on or after `date` falling on `day`. */
export function nextWeekday(date: string, day: DayName): string {
  let d = date;
  while (weekdayOf(d) !== day) d = plusDays(d, 1);
  return d;
}
/** The same day a year later (29 February becomes 28 February). */
export function plusYear(date: string): string {
  const [y, m, d] = date.split('-').map(Number);
  const last = new Date(Date.UTC(y + 1, m, 0)).getUTCDate();
  return `${y + 1}-${String(m).padStart(2, '0')}-${String(Math.min(d, last)).padStart(2, '0')}`;
}
/** The local date in a time zone at an instant. */
export const localDate = (instant: string, timeZone: string) =>
  new Intl.DateTimeFormat('en-CA', { timeZone, year: 'numeric', month: '2-digit', day: '2-digit' }).format(new Date(instant));

/** A CSV body for the import. */
export const csv = (days: Day[], header = true) => `${header ? 'date,name,kind\n' : ''}${days.map(d => `${d.date},${d.name},${d.kind}`).join('\n')}\n`;

// ---- Salon side ----

export class Hours {
  constructor(readonly identity: Identity) {}

  call(salon: Salon, method: string, route: string, tokens?: Tokens, data?: unknown): Promise<APIResponse> {
    return onSalonHost(this.identity, salon, method, route, tokens, data);
  }

  async settings(salon: Salon, tokens: Tokens): Promise<HoursSettings> {
    return json<HoursSettings>(await this.call(salon, 'GET', HOURS_SETTINGS, tokens), 'OpeningHoursSettings');
  }
  saveRaw(salon: Salon, tokens: Tokens | undefined, version: number, weekly: unknown) {
    return this.call(salon, 'PUT', HOURS_SETTINGS, tokens, { version, weekly });
  }
  async save(salon: Salon, tokens: Tokens, weekly: Weekday[], version?: number): Promise<HoursSettings> {
    const from = version ?? (await this.settings(salon, tokens)).version;
    return json<HoursSettings>(await this.saveRaw(salon, tokens, from, weekly), 'OpeningHoursSettings');
  }
  /** The public opening information, on the salon's hostname with no credential. */
  async published(salon: Salon): Promise<PublicHours> {
    return json<PublicHours>(await this.identity.api.getOn(salon.hostname, OPENING_HOURS), 'SalonOpeningHours');
  }

  async holidays(salon: Salon, tokens: Tokens): Promise<SalonHolidays> {
    return json<SalonHolidays>(await this.call(salon, 'GET', HOLIDAYS, tokens), 'SalonHolidays');
  }
  async year(salon: Salon, tokens: Tokens, year: number): Promise<SalonHolidayYear> {
    const found = (await this.holidays(salon, tokens)).years.find(y => y.year === year);
    expect(found, `the salon sees the published year ${year}`).toBeTruthy();
    return found!;
  }
  acceptRaw(salon: Salon, tokens: Tokens | undefined, year: number, version: number, choices: ChoiceInput[]) {
    return this.call(salon, 'POST', `${HOLIDAYS}/${year}/acceptance`, tokens, { version, choices });
  }
  async accept(salon: Salon, tokens: Tokens, year: number, choices: ChoiceInput[], version?: number): Promise<SalonHolidayYear> {
    const from = version ?? (await this.year(salon, tokens, year)).version;
    return json<SalonHolidayYear>(await this.acceptRaw(salon, tokens, year, from, choices), 'SalonHolidayYear');
  }
  chooseRaw(salon: Salon, tokens: Tokens | undefined, year: number, date: string, choice: Omit<ChoiceInput, 'date'>) {
    return this.call(salon, 'PUT', `${HOLIDAYS}/${year}/days/${date}`, tokens, choice);
  }
  async choose(salon: Salon, tokens: Tokens, year: number, date: string, choice: Omit<ChoiceInput, 'date'>): Promise<SalonHolidayYear> {
    return json<SalonHolidayYear>(await this.chooseRaw(salon, tokens, year, date, choice), 'SalonHolidayYear');
  }

  async closures(salon: Salon, tokens: Tokens): Promise<Closure[]> {
    const r = await this.call(salon, 'GET', CLOSURES, tokens);
    expect(r.status(), `closures (${(await r.text()).slice(0, 200)})`).toBe(200);
    const body = await r.json() as Closure[];
    expect(Array.isArray(body), 'the closures list is an array').toBe(true);
    return body;
  }
  addRaw(salon: Salon, tokens: Tokens | undefined, date: string, reason: string | null = null) {
    return this.call(salon, 'POST', CLOSURES, tokens, { date, reason });
  }
  async add(salon: Salon, tokens: Tokens, date: string, reason: string | null = null): Promise<Closure> {
    return json<Closure>(await this.addRaw(salon, tokens, date, reason), 'SalonClosure', 201);
  }
  reopenRaw(salon: Salon, tokens: Tokens | undefined, id: string) { return this.call(salon, 'DELETE', `${CLOSURES}/${id}`, tokens); }
  async impact(salon: Salon, tokens: Tokens, date: string): Promise<ClosureImpact> {
    return json<ClosureImpact>(await this.call(salon, 'GET', `${CLOSURES}/impact?date=${date}`, tokens), 'ClosureImpact');
  }
}

// ---- Operator side (console host) ----

export class Calendar {
  private uploads?: APIRequestContext;
  constructor(readonly identity: Identity) {}

  call(actor: OperatorActor | Tokens | null, method: string, route: string, data?: unknown, host = CONSOLE_HOST): Promise<APIResponse> {
    const tokens = actor && 'tokens' in actor ? actor.tokens : actor;
    return this.identity.api.send(method, `${CALENDARS}${route}`, {
      headers: { Host: host, ...(tokens ? { Authorization: `Bearer ${tokens.accessToken}` } : {}), ...(data === undefined ? {} : { 'Content-Type': 'application/json' }) },
      data: data === undefined ? undefined : JSON.stringify(data),
    });
  }

  async list(actor: OperatorActor): Promise<YearList> { return json<YearList>(await this.call(actor, 'GET', ''), 'HolidayYearList'); }
  async year(actor: OperatorActor, year: number): Promise<YearDetail> { return json<YearDetail>(await this.call(actor, 'GET', `/${year}`), 'HolidayYearDetail'); }

  async importRaw(actor: OperatorActor | Tokens | null, year: number, body: string | Buffer, host = CONSOLE_HOST): Promise<APIResponse> {
    this.uploads ??= await request.newContext({ baseURL: apiUrl, timeout: 15_000, maxRedirects: 0 });
    const tokens = actor && 'tokens' in actor ? actor.tokens : actor;
    return this.uploads.fetch(`${CALENDARS}/${year}/import`, {
      method: 'POST',
      headers: { Host: host, ...(tokens ? { Authorization: `Bearer ${tokens.accessToken}` } : {}) },
      multipart: { file: { name: `holidays-${year}.csv`, mimeType: 'text/csv', buffer: Buffer.isBuffer(body) ? body : Buffer.from(body, 'utf8') } },
    });
  }
  async import(actor: OperatorActor, year: number, days: Day[]): Promise<YearDetail> {
    return json<YearDetail>(await this.importRaw(actor, year, csv(days)), 'HolidayYearDetail');
  }
  addRaw(actor: OperatorActor | Tokens | null, year: number, day: Day) { return this.call(actor, 'POST', `/${year}/days`, day); }
  async add(actor: OperatorActor, year: number, day: Day): Promise<YearDetail> { return json<YearDetail>(await this.addRaw(actor, year, day), 'HolidayYearDetail'); }
  changeRaw(actor: OperatorActor | Tokens | null, year: number, date: string, change: { name: string; kind: Kind }) { return this.call(actor, 'PUT', `/${year}/days/${date}`, change); }
  async change(actor: OperatorActor, year: number, date: string, change: { name: string; kind: Kind }): Promise<YearDetail> {
    return json<YearDetail>(await this.changeRaw(actor, year, date, change), 'HolidayYearDetail');
  }
  removeRaw(actor: OperatorActor | Tokens | null, year: number, date: string) { return this.call(actor, 'DELETE', `/${year}/days/${date}`); }
  async remove(actor: OperatorActor, year: number, date: string): Promise<YearDetail> { return json<YearDetail>(await this.removeRaw(actor, year, date), 'HolidayYearDetail'); }
  publishRaw(actor: OperatorActor | Tokens | null, year: number, version: number) { return this.call(actor, 'POST', `/${year}/publication`, { version }); }
  async publish(actor: OperatorActor, year: number, version?: number): Promise<YearDetail> {
    const from = version ?? (await this.year(actor, year)).version;
    return json<YearDetail>(await this.publishRaw(actor, year, from), 'HolidayYearDetail');
  }

  /**
   * A year no earlier test has touched, started at once with one placeholder day so that no other
   * test takes it; the placeholder is replaced by the test's own import.
   */
  async freshYear(admin: OperatorActor): Promise<number> {
    const taken = new Set((await this.list(admin)).items.map(y => y.year));
    const free = Array.from({ length: 2098 - 2028 + 1 }, (_, n) => 2028 + n).filter(y => !taken.has(y));
    if (free.length === 0) throw new Error('ENVIRONMENT: every holiday calendar year 2028-2098 of this database is used; run on a fresh database');
    const year = free[randomInt(free.length)];
    await this.add(admin, year, { date: `${year}-12-31`, name: 'QA placeholder', kind: 'public' });
    return year;
  }

  /**
   * The shared year, published with its fixed days: made on first use in a database (or finished, if
   * an earlier run stopped between import and publication), then only read.
   */
  async shared(admin: OperatorActor): Promise<typeof SHARED> {
    const all = [SHARED.past, ...SHARED.days];
    const listed = (await this.list(admin)).items.find(y => y.year === SHARED.year);
    if (!listed || listed.state !== 'published') {
      if (!listed || listed.revision === 0) await this.import(admin, SHARED.year, all);
      await this.publish(admin, SHARED.year);
    }
    const detail = await this.year(admin, SHARED.year);
    if (detail.state !== 'published' || JSON.stringify(detail.days.map(d => ({ date: d.date, name: d.name, kind: d.kind }))) !== JSON.stringify(all)) {
      throw new Error(`ENVIRONMENT: the shared holiday year ${SHARED.year} of this database is not the fixture's; run on a fresh database`);
    }
    return SHARED;
  }

  /** Imports the days into a fresh year and publishes it; returns the year as published. */
  async published(admin: OperatorActor, days: (year: number) => Day[]): Promise<{ year: number; detail: YearDetail; days: Day[] }> {
    const year = await this.freshYear(admin);
    const list = days(year);
    await this.import(admin, year, list);
    const detail = await this.publish(admin, year);
    return { year, detail, days: list };
  }

  async dispose() { await this.uploads?.dispose(); }
}

// ---- Answers ----

/** Asserts a refused call answered `status` with `code`. */
export async function refused(response: APIResponse, status: number, code: string, label: string) {
  const answer = await answerOf(response);
  expect(answer.status, `${label}: HTTP status (body ${answer.body.slice(0, 300)})`).toBe(status);
  const problem = expectProblem(answer, label);
  expect(problem.code, `${label}: code`).toBe(code);
  return { answer, problem: problem as typeof problem & { errors?: { field: string; detail: string; line?: number }[] } };
}

/** A 400 bad_request; returns its field errors. */
export async function fieldErrors(response: APIResponse, label: string) {
  const { problem, answer } = await refused(response, 400, 'bad_request', label);
  return { errors: problem.errors ?? [], body: answer.body };
}

// ---- Database observations (owner role unless a tenant is given; never writes) ----

export function hoursRowCounts(salonId: string): Record<string, number> {
  return Object.fromEntries(HOURS_TABLES.map(t => [t, Number(identitySql(`SELECT count(*) FROM ${t} WHERE tenant_id = ${sqlLiteral(salonId)}`))]));
}

/** A digest of every row of the four tables for a salon, to prove a refused call changed nothing. */
export function hoursSnapshot(salonId: string): string {
  return HOURS_TABLES.map(t => identitySql(
    `SELECT coalesce(md5(string_agg(row_to_json(x)::text, '|' ORDER BY row_to_json(x)::text)), 'none') FROM ${t} x WHERE tenant_id = ${sqlLiteral(salonId)}`)).join(',');
}

/** A digest of the platform calendar of one year (draft, published days and the year row). */
export function calendarSnapshot(year: number): string {
  return ['platform_holiday_year', 'platform_holiday_draft_day', 'platform_holiday_published_day'].map(t => identitySql(
    `SELECT coalesce(md5(string_agg(row_to_json(x)::text, '|' ORDER BY row_to_json(x)::text)), 'none') FROM ${t} x WHERE row_to_json(x)::text LIKE ${sqlLiteral(`%${year}%`)}`)).join(',');
}

export type AuditRow = { action: string; actorKind: string; actorId: string; at: string; details: string };

/**
 * The salon's audit entries of this stage's actions (`staff.*`) by one staff account, oldest first.
 * Filtered by the actor, not by time: the test clock may stand anywhere.
 */
export function hoursAudit(salonId: string, actorId: string): AuditRow[] {
  const out = identitySql(`SELECT json_build_object('action', action, 'actorKind', actor_kind, 'actorId', actor_id, 'at', created_at, 'details', row_to_json(a)::text)
    FROM audit_entry a WHERE tenant_id = ${sqlLiteral(salonId)} AND action LIKE 'staff.%' AND actor_id::text = ${sqlLiteral(actorId)}
    ORDER BY created_at, id`);
  return out ? out.split('\n').map(line => JSON.parse(line) as AuditRow) : [];
}
