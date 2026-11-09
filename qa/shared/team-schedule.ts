/**
 * F06-S02 schedule fixtures: the schedule endpoints, week and date-change builders, how a week is
 * compared, the words the sheet and the card show, and read-only database observations of the stage's
 * tables.
 *
 * Routes, bodies, codes and limits come from the F06-S02 spec (acceptance criteria, "API and data
 * changes", "UI and UX") and the lead's rulings for the build:
 * 1. F06 writes times with the existing 24-hour `formatClock`. The owner may change that later, so
 *    every expected time goes through `shownTime` here and nothing else spells one on a screen.
 * 2. A working day must have time left: breaks that leave none are a field error on the weekday, or on
 *    `breaks` for a date change. A break touching only the start or only the finish is allowed.
 * 3. A date change's note is not written to the audit trail, and a support session never receives it.
 *
 * The spec's "Corrections at close" (decision 103) carry these and take precedence over its criteria:
 * Monday first, + Add break disabled at five, AC-11's sentences, versions, `today`, breaks in time order, a
 * salon with no saved hours, curly apostrophes, and an audit entry naming only the weekdays changed.
 *
 * Every name the build gives is in `PENDING_F06_S02`, confirmed against the stage's handoff at the dry run
 * (37129e9); nothing else in the F06-S02 tests spells one.
 */
import { randomUUID } from 'node:crypto';

import type { APIResponse, Locator } from '@playwright/test';

import { refused } from './booking-rules';
import { expect, json, type Tokens } from './identity';
import { identitySql } from './identity-storage';
import { fieldsOf, sqlLiteral } from './operator';
import { DAYS, type DayName, type Weekday } from './salon-hours';
import { PENDING_F06_S01, memberPath, type BreakTime, type Team, type WorkDay } from './team';
import type { Salon } from './tenants';

/** Names the spec leaves to the build, from the F06-S02 handoff. */
export const PENDING_F06_S02 = {
  /** Contract schemas (docs/api/openapi.json, components.schemas). */
  schemas: { schedule: 'TeamMemberSchedule', saved: 'SavedTeamMemberWeek', added: 'AddedTeamMemberDateChange' },
  /** A field error "on that weekday" (AC-2, AC-3): `weekly[n]`, or inside it. The week is always sent in `DAYS` order. */
  dayField: (day: DayName) => new RegExp(`^weekly\\[${DAYS.indexOf(day)}\\]($|[.\\[])`),
  /** The browser's calls for the schedule sheet, for holding, failing or adding to them. */
  bff: {
    read: (url: URL) => url.pathname.startsWith('/identity-web/') && /\/team-schedule$/.test(url.pathname),
    saveWeek: (url: URL) => url.pathname.startsWith('/identity-web/') && /\/team-week-save$/.test(url.pathname),
    addChange: (url: URL) => url.pathname.startsWith('/identity-web/') && /\/team-date-change-add$/.test(url.pathname),
  },
  /** Test ids (the spec: every interactive element has one). A control the spec names is found by its role and name instead. */
  web: {
    cardHours: 'team-card-hours', cardSchedule: 'team-card-schedule',
    sheet: 'schedule-sheet', avatar: 'team-member-avatar', personName: 'schedule-name', close: 'schedule-close',
    loading: 'schedule-loading', unavailable: 'schedule-unavailable', retry: 'schedule-retry', refused: 'schedule-refused', readOnly: 'schedule-read-only',
    conflict: 'schedule-conflict', conflictReload: 'schedule-conflict-reload', problems: 'schedule-problems', outside: 'schedule-outside', hoursUnset: 'schedule-salon-hours-unset', noHoursReason: 'schedule-no-salon-hours',
    leaveDialog: 'team-leave-dialog', leaveStay: 'team-leave-stay', leaveConfirm: 'team-leave-confirm',
    day: (day: DayName) => `schedule-day-${day}`, toggle: (day: DayName) => `schedule-${day}-working`, off: (day: DayName) => `schedule-${day}-off`,
    addBreak: (day: DayName) => `schedule-${day}-add-break`, breaksFull: (day: DayName) => `schedule-${day}-breaks-full`,
    affected: 'schedule-affected', affectedRow: 'schedule-affected-booking',
    changeForm: 'change-form', changeDate: 'change-date', changeCalendar: 'change-calendar', changeProblem: 'change-problem',
    changeRow: 'change-row', changeRowDate: 'change-row-date',
  },
  /** Accessible names of the times and their controls. AC-16 names "Monday break 1 starts"; the rest are the build's. */
  names: {
    starts: (day: string) => `${day} starts`, finishes: (day: string) => `${day} finishes`,
    breakStarts: (day: string, n: number) => `${day} break ${n} starts`, breakEnds: (day: string, n: number) => `${day} break ${n} ends`,
    breakRemove: (day: string, n: number) => `Remove ${day} break ${n}`,
    changeStarts: 'Starts', changeFinishes: 'Finishes', changeBreakStarts: (n: number) => `Break ${n} starts`, changeBreakEnds: (n: number) => `Break ${n} ends`,
    saveChange: 'Save this date',
  },
} as const;

export const schedulePath = (memberId: string) => `${memberPath(memberId)}/schedule`;
export const weeklyPath = (memberId: string) => `${schedulePath(memberId)}/weekly`;
export const changesPath = (memberId: string) => `${schedulePath(memberId)}/date-changes`;
export const changePath = (memberId: string, changeId: string) => `${changesPath(memberId)}/${changeId}`;

/** The spec's limits (AC-3, AC-7). */
export const SCHEDULE_LIMITS = { breaks: 5, noteLength: 60, upcomingChanges: 100 } as const;

// ---- shapes, from the spec's "API and data changes" until the contract exists ----

export type DateChange = { id: string; date: string; working: boolean; starts: string | null; finishes: string | null; breaks: BreakTime[]; note?: string | null };
export type ChangeInput = { date?: unknown; working?: unknown; starts?: unknown; finishes?: unknown; breaks?: unknown; note?: unknown };
export type MemberSchedule = { version: number; today: string; weekly: WorkDay[]; dateChanges: DateChange[] };
export type AffectedBooking = { date: string; time: string; customerName: string; serviceName: string };
export type SavedWeek = { version: number; weekly: WorkDay[]; affected: AffectedBooking[] };
export type AddedChange = { change: DateChange; affected: AffectedBooking[] };

// ---- builders ----

export const brk = (starts: string, finishes: string): BreakTime => ({ starts, finishes });
export const workingDay = (day: DayName, starts = '09:00', finishes = '18:00', breaks: BreakTime[] = []): WorkDay => ({ day, working: true, starts, finishes, breaks });
export const offDay = (day: DayName): WorkDay => ({ day, working: false, starts: null, finishes: null, breaks: [] });
export const noWorkingDays = (): WorkDay[] => DAYS.map(offDay);

/** A week: every day `starts`-`finishes` with no break, except the days named off and the changes given. */
export function weekOf(starts = '09:00', finishes = '18:00', off: DayName[] = ['sunday'], changes: Partial<Record<DayName, Partial<WorkDay>>> = {}): WorkDay[] {
  return DAYS.map(day => ({ ...(off.includes(day) ? offDay(day) : workingDay(day, starts, finishes)), ...changes[day], day }));
}

/** The week a new person's copy of the salon's opening hours is expected to be (AC-1). */
export const copyOf = (opening: Weekday[]): WorkDay[] => DAYS.map(day => {
  const d = opening.find(o => o.day === day);
  expect(d, `the salon's week names ${day}`).toBeTruthy();
  return d!.open ? workingDay(day, d!.opens ?? undefined, d!.closes ?? undefined) : offDay(day);
});

/** A date change: different hours from 10:00 to 14:00 with no break and no note, unless overridden. */
export const changeInput = (date: string, overrides: ChangeInput = {}): ChangeInput => ({ date, working: true, starts: '10:00', finishes: '14:00', breaks: [], note: null, ...overrides });
export const offChange = (date: string, note: string | null = null): ChangeInput => ({ date, working: false, starts: null, finishes: null, breaks: [], note });

// ---- comparing ----

const sortedBreaks = (breaks: BreakTime[]) => [...breaks].sort((a, b) => String(a.starts).localeCompare(String(b.starts)));
const inDayOrder = (weekly: WorkDay[]) => [...weekly].sort((a, b) => DAYS.indexOf(a.day) - DAYS.indexOf(b.day));

/**
 * The week is each of the seven weekdays once, and is the one expected. A week reads back with its breaks
 * in time order, whatever order they were sent (corrections at close), so only what was sent is put in order.
 */
export function expectWeek(actual: WorkDay[], expected: WorkDay[], label: string) {
  expect([...actual.map(d => d.day)].sort(), `${label}: each of the seven weekdays once`).toEqual([...DAYS].sort());
  expect(inDayOrder(actual), label).toEqual(inDayOrder(expected).map(d => ({ ...d, breaks: sortedBreaks(d.breaks) })));
}

/** What a change holds, leaving out its id and its note. */
export const comparableChange = (c: { date?: unknown; working?: unknown; starts?: unknown; finishes?: unknown; breaks?: unknown }) => ({
  date: c.date, working: c.working, starts: c.starts ?? null, finishes: c.finishes ?? null, breaks: c.breaks ?? [],
});

/** A 400 bad_request whose every field error is on the weekday (AC-2, AC-3). */
export async function refusedOnDay(response: APIResponse, day: DayName, label: string) {
  await refusedOnField(response, PENDING_F06_S02.dayField(day), label);
}

/** A 400 bad_request whose every field error matches `field`. */
export async function refusedOnField(response: APIResponse, field: RegExp, label: string) {
  const { problem, answer } = await refused(response, 400, 'bad_request', label);
  const fields = fieldsOf(problem as { errors?: { field?: string }[] });
  expect(fields.length, `${label}: names the field (${answer.body.slice(0, 300)})`).toBeGreaterThan(0);
  for (const f of fields) expect(f, `${label}: the error is on the field`).toMatch(field);
}

// ---- the endpoints ----

export class Schedules {
  constructor(readonly team: Team) {}

  readRaw(salon: Salon, tokens: Tokens | undefined, memberId: string) { return this.team.call(salon, 'GET', schedulePath(memberId), tokens); }
  async read(salon: Salon, tokens: Tokens, memberId: string): Promise<MemberSchedule> {
    return json<MemberSchedule>(await this.readRaw(salon, tokens, memberId), PENDING_F06_S02.schemas.schedule);
  }
  saveRaw(salon: Salon, tokens: Tokens | undefined, memberId: string, version: unknown, weekly: unknown) {
    return this.team.call(salon, 'PUT', weeklyPath(memberId), tokens, { version, weekly });
  }
  async save(salon: Salon, tokens: Tokens, memberId: string, weekly: WorkDay[], version?: number): Promise<SavedWeek> {
    const from = version ?? (await this.read(salon, tokens, memberId)).version;
    return json<SavedWeek>(await this.saveRaw(salon, tokens, memberId, from, weekly), PENDING_F06_S02.schemas.saved);
  }
  addRaw(salon: Salon, tokens: Tokens | undefined, memberId: string, input: unknown) { return this.team.call(salon, 'POST', changesPath(memberId), tokens, input); }
  async add(salon: Salon, tokens: Tokens, memberId: string, input: ChangeInput): Promise<AddedChange> {
    return json<AddedChange>(await this.addRaw(salon, tokens, memberId, input), PENDING_F06_S02.schemas.added, 201);
  }
  removeRaw(salon: Salon, tokens: Tokens | undefined, memberId: string, changeId: string) { return this.team.call(salon, 'DELETE', changePath(memberId, changeId), tokens); }
  async remove(salon: Salon, tokens: Tokens, memberId: string, changeId: string) {
    const r = await this.removeRaw(salon, tokens, memberId, changeId);
    expect(r.status(), `remove a date change (body ${(await r.text()).slice(0, 200)})`).toBe(204);
  }
}

/** Every endpoint that changes a schedule, with a body that would succeed for this schedule. */
export function changeRoutes(memberId: string, schedule: MemberSchedule, change: DateChange, freeDate: string) {
  const changed = schedule.weekly.map(d => (d.day === 'monday' ? workingDay('monday', '07:15', '12:45') : d));
  return [
    { label: 'save hours', method: 'PUT', path: weeklyPath(memberId), data: { version: schedule.version, weekly: changed } as unknown },
    { label: 'add a date change', method: 'POST', path: changesPath(memberId), data: changeInput(freeDate) as unknown },
    { label: 'remove a date change', method: 'DELETE', path: changePath(memberId, change.id), data: undefined as unknown },
  ];
}

// ---- the words the sheet and the card show ----

export const SHORT: Record<DayName, string> = { monday: 'Mon', tuesday: 'Tue', wednesday: 'Wed', thursday: 'Thu', friday: 'Fri', saturday: 'Sat', sunday: 'Sun' };
export const LONG: Record<DayName, string> = { monday: 'Monday', tuesday: 'Tuesday', wednesday: 'Wednesday', thursday: 'Thursday', friday: 'Friday', saturday: 'Saturday', sunday: 'Sunday' };

/** A time as a person reads it (ruling 1: F06 uses the existing 24-hour `formatClock`). The one place to change it. */
export const shownTime = (hhmm: string) => hhmm;
const range = (starts: string, finishes: string) => `${shownTime(starts)} – ${shownTime(finishes)}`;

export const SCHEDULE_TEXT = {
  weeklyBanner: 'Customers can book this person inside these hours while the salon is open.',
  datesBanner: 'A date change replaces the weekly hours on that date only. Use it for a half day or an extra working day.',
  profileLine: 'New people start on the salon’s opening hours. Change them under Schedule.',
  saved: 'Hours saved. New bookings follow them. Existing bookings are kept.',
  finishAfterStart: (day: DayName) => `${SHORT[day]}: the finish must be after the start.`,
  breakOrder: (day: DayName) => `${SHORT[day]}: a break must end after it starts.`,
  breakInside: (day: DayName) => `${SHORT[day]}: a break must be inside the working hours.`,
  breakOverlap: (day: DayName) => `${SHORT[day]}: breaks can’t overlap.`,
  breakFills: (day: DayName) => `${SHORT[day]}: breaks can’t fill the whole day. Set it as not working.`,
  maxBreaks: 'Up to 5 breaks a day.',
  changeFinishAfterStart: 'The finish must be after the start.',
  changeBreakFills: 'Breaks can’t fill the whole day. Set it as not working.',
  outsideHead: 'Outside opening hours.',
  outsideClosed: (day: DayName) => `${SHORT[day]}: the salon is closed.`,
  outsideOpen: (day: DayName, opens: string, closes: string) => `${SHORT[day]}: the salon is open ${range(opens, closes)}.`,
  outsideTail: 'Bookings still follow the salon’s hours.',
  hoursUnset: 'The salon hasn’t set its opening hours yet, so nobody can be booked.',
  secondChange: 'This date already has a change. Remove it first.',
  changeRemoved: 'Back to the weekly hours on that date.',
  noChanges: 'No date changes.',
  affected: (n: number) => (n === 1 ? '1 upcoming booking falls outside these hours. It is kept.' : `${n} upcoming bookings fall outside these hours. They are kept.`),
  affectedOnDate: (n: number) => (n === 1 ? '1 upcoming booking on this date is affected. It is kept.' : `${n} upcoming bookings on this date are affected. They are kept.`),
  /** AC-12's Hours row. */
  row: {
    none: 'No working days', varies: 'Varies by day',
    stretch: (first: DayName, last: DayName, starts: string, finishes: string) => `${SHORT[first]}–${SHORT[last]}, ${range(starts, finishes)}`,
    single: (day: DayName, starts: string, finishes: string) => `${SHORT[day]}, ${range(starts, finishes)}`,
    oneBreak: (starts: string) => ` · break ${shownTime(starts)}`,
    breaks: (n: number) => ` · ${n} breaks`,
  },
};

/**
 * A Calendar day button by its date, as calendar-labels.ts names it (F04's closure sheet): the date
 * written out, "Today, " before today and ", selected" after the chosen day.
 */
export function calendarDay(scope: Locator, date: string): Locator {
  const written = new Intl.DateTimeFormat('en-LK', { weekday: 'long', day: 'numeric', month: 'long', year: 'numeric', timeZone: 'UTC' })
    .format(new Date(`${date}T00:00:00Z`));
  return scope.getByRole('button', { name: new RegExp(`^(Today, )?${written}(, selected)?$`) });
}

// ---- database observations (owner role unless a tenant is given) ----

/** The five tables this stage added, from its handoff. F06-S03's have their own helpers and may hold no row here. */
export const S02_SCHEDULE_TABLES = ['staff_member_schedule', 'staff_member_weekday', 'staff_member_weekday_break', 'staff_member_date_change', 'staff_member_date_change_break'] as const;
export const scheduleTables = (): string[] => [...S02_SCHEDULE_TABLES];

/** A digest of a salon's schedule rows and its members, whose row may hold the schedule's version, to prove a refused call changed nothing. */
export function scheduleSnapshot(salonId: string): string {
  return [...scheduleTables(), PENDING_F06_S01.memberTable].map(t => identitySql(
    `SELECT coalesce(md5(string_agg(row_to_json(x)::text, '|' ORDER BY row_to_json(x)::text)), 'none') FROM ${t} x WHERE tenant_id = ${sqlLiteral(salonId)}`)).join(',');
}

/** How many of a salon's audit entries carry the words, whoever may read them. */
export const auditEntriesWith = (salonId: string, words: string) => Number(identitySql(
  `SELECT count(*) FROM audit_entry a WHERE tenant_id = ${sqlLiteral(salonId)} AND row_to_json(a)::text LIKE ${sqlLiteral(`%${words}%`)}`));

/**
 * A person as they stand after the F06-S01 migration: the member's row with no schedule stored, a state no
 * endpoint of this stage can make (AC-1; corrections at close: no backfill). It writes one row to a salon the
 * test created, copied from the person given, as the handoff says to.
 */
export function memberBeforeThisStage(salonId: string, fromMemberId: string, name: string): string {
  const id = randomUUID();
  const table = PENDING_F06_S01.memberTable;
  identitySql(`INSERT INTO ${table} SELECT (jsonb_populate_record(x, jsonb_build_object('id', ${sqlLiteral(id)}, 'name', ${sqlLiteral(name)}))).*
    FROM ${table} x WHERE tenant_id = ${sqlLiteral(salonId)} AND id = ${sqlLiteral(fromMemberId)}`);
  return id;
}
