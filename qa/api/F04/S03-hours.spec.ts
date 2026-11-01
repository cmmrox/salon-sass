/**
 * F04-S03 opening hours, holidays and closures from outside: the weekly hours and their public
 * reading, the operator's holiday calendar (CSV import, single-day edits, publication and
 * revisions), each salon's holiday choices, closures, priority, the salon's time zone at day
 * boundaries, tenant isolation through the API and at the database, roles, a support session,
 * suspended and read-only salons, the operator routes' host binding, versions and races, and the
 * audit trails.
 *
 * Expected results are the F04-S03 spec's (acceptance criteria, "API and data changes", "Design
 * notes", "Corrections at close"), docs/qa/test-types.md and the generated contract. The holiday
 * calendar is shared platform data, so each test that needs a year takes a fresh one
 * (shared/salon-hours.ts) and, when "today" must fall inside it, sets the test clock there.
 * Salon-side state that a test must see from empty comes from a salon the operator creates now.
 */
import { randomInt, randomUUID } from 'node:crypto';

import { readOnlySalon } from '../../shared/billing';
import type { Identity, Role, Tokens } from '../../shared/identity';
import { identitySql } from '../../shared/identity-storage';
import {
  test, expect, alpha, beta, gamma, comparable, operatorJson, ownerSession, sqlLiteral, supportJson, platformAuditWhere,
  type Operator, type SupportStart, type TestClock,
} from '../../shared/operator';
import { logOffsets, logsSince } from '../../shared/product-logs';
import {
  CALENDARS, CLOSURES, HOLIDAYS, HOURS_SETTINGS, HOURS_TABLES, OPENING_HOURS,
  Calendar, Hours, calendarSnapshot, csv, fieldErrors, hoursAudit, hoursRowCounts, hoursSnapshot, localDate, nextWeekday,
  plusDays, plusYear, refused, stored, unique, week, SHARED,
  type ChoiceInput, type Day, type PublicHours,
} from '../../shared/salon-hours';
import type { Salon } from '../../shared/tenants';

const COLOMBO = 'Asia/Colombo';

/** Sets the test clock to a local time in Colombo on a date (Colombo is UTC+05:30 all year). */
async function colomboAt(clock: TestClock, date: string, time = '10:00') {
  const [h, m] = time.split(':').map(Number);
  const utc = new Date(Date.parse(`${date}T00:00:00Z`) + ((h * 60 + m) - 330) * 60_000).toISOString();
  await clock.set(utc);
  return utc;
}

/** The exceptions of the public information on the given dates, in date order. */
const exceptionsOn = (info: PublicHours, dates: string[]) => info.exceptions.filter(e => dates.includes(e.date));

/** Days of a year after `from`, spread so they all fall in the public 180-day window from `from`. */
function daysAfter(from: string, kinds: Day['kind'][]): Day[] {
  return kinds.map((kind, n) => ({ date: plusDays(from, 10 + n * 9), name: unique(`QA ${kind} day`), kind }));
}

/** A salon the operator creates now, with its Owner signed in (MFA enrolled). */
async function newSalon(operator: Operator, identity: Identity, overrides: { timeZone?: string } = {}) {
  const admin = await operator.actor();
  const plan = await operator.plan(admin);
  const { input, created, salon } = await operator.salon(admin, plan.id, overrides);
  const owner = await ownerSession(identity, salon, input);
  return { admin, created, salon, owner };
}

/** The shared published year (shared/salon-hours.ts), with the clock at 10:00 in Colombo on its "today". */
async function sharedYear(operator: Operator, calendar: Calendar, clock: TestClock) {
  await colomboAt(clock, SHARED.today);
  const admin = await operator.actor();
  await calendar.shared(admin);
  return { admin, ...SHARED };
}

/** Every day of the shared year still to come, with one choice. */
const allShared = (choice: 'closed' | 'open') => SHARED.days.map(d => ({ date: d.date, choice, opens: null, closes: null }));

test.describe.configure({ timeout: 120_000 });

test('TC-F04-S03-001 AC-1: before the first save the settings and the public information have no weekly hours; Save hours stores the week at once and the public information shows it on the next request', async ({ identity, operator }) => {
  const hours = new Hours(identity);
  const { salon, owner } = await newSalon(operator, identity);
  const before = await hours.settings(salon, owner.tokens);
  expect(before.version, 'nothing stored before the first save').toBe(0);
  expect(before.weekly, 'no weekly hours before the first save').toBeNull();
  expect((await hours.published(salon)).weekly, 'the public information has no weekly hours').toBeNull();
  const first = week('08:15', '17:45', ['sunday'], { saturday: ['10:30', '19:00'] });
  const saved = await hours.save(salon, owner.tokens, first, before.version);
  expect(saved.version, 'saving moves the version on').toBeGreaterThan(before.version);
  expect(saved.weekly).toEqual(stored(first));
  expect((await hours.settings(salon, owner.tokens)).weekly, 'the re-read week is the saved one').toEqual(stored(first));
  expect((await hours.published(salon)).weekly, 'the public information shows the week on the next request').toEqual(stored(first));
  const second = week('07:00', '23:45', ['sunday', 'wednesday']);
  await hours.save(salon, owner.tokens, second, saved.version);
  expect((await hours.published(salon)).weekly, 'a later save shows on the next request too').toEqual(stored(second));
});

test('TC-F04-S03-002 AC-1: a closing time at or before the opening time, a time off the quarter hour or a week without each day once is a 400 and saves nothing', async ({ identity }) => {
  const hours = new Hours(identity);
  const manager = await identity.staff(alpha, 'MANAGER');
  await hours.save(alpha, manager.tokens, week());
  const tuesday = 1;
  const rows: { label: string; weekly: unknown; field: RegExp }[] = [
    { label: 'closing equal to opening', weekly: week('09:00', '18:00', ['sunday'], { tuesday: ['12:00', '12:00'] }), field: new RegExp(`^weekly\\[${tuesday}\\]\\.closes$`) },
    { label: 'closing before opening', weekly: week('09:00', '18:00', ['sunday'], { tuesday: ['14:00', '09:30'] }), field: new RegExp(`^weekly\\[${tuesday}\\]\\.closes$`) },
    { label: 'opening off the quarter hour', weekly: week('09:00', '18:00', ['sunday'], { tuesday: ['09:10', '18:00'] }), field: new RegExp(`^weekly\\[${tuesday}\\]\\.opens$`) },
    { label: 'closing off the quarter hour', weekly: week('09:00', '18:00', ['sunday'], { tuesday: ['09:00', '17:59'] }), field: new RegExp(`^weekly\\[${tuesday}\\]\\.closes$`) },
    { label: 'open with no times', weekly: week().map((d, n) => n === tuesday ? { ...d, opens: null, closes: null } : d), field: /^weekly\[1\]\.(opens|closes)$/ },
    { label: 'a weekday missing', weekly: week().slice(0, 6), field: /^weekly/ },
    { label: 'a weekday twice', weekly: [...week().slice(0, 6), { day: 'monday', open: true, opens: '09:00', closes: '18:00' }], field: /^weekly/ },
  ];
  for (const row of rows) {
    await test.step(row.label, async () => {
      const current = await hours.settings(alpha, manager.tokens);
      const before = hoursSnapshot(alpha.id);
      const { errors } = await fieldErrors(await hours.saveRaw(alpha, manager.tokens, current.version, row.weekly), row.label);
      expect(errors.length, `${row.label}: names the field`).toBeGreaterThan(0);
      for (const error of errors) expect(error.field, `${row.label}: the error is on that day`).toMatch(row.field);
      expect(hoursSnapshot(alpha.id), `${row.label}: nothing saved`).toBe(before);
    });
  }
});

test('TC-F04-S03-003 AC-2: the public opening information lists the closed days from today to 180 days ahead with no reason, follows a save at once and shows only its own salon', async ({ identity, operator, clock }) => {
  const hours = new Hours(identity);
  const today = '2027-03-10';
  await colomboAt(clock, today, '11:30');
  const { salon, owner } = await newSalon(operator, identity);
  const own = week('07:45', '16:15', ['sunday', 'thursday']);
  await hours.save(salon, owner.tokens, own);
  const reason = unique('QA private reason');
  const dates = { today, last: plusDays(today, 180), beyond: plusDays(today, 181) };
  await hours.add(salon, owner.tokens, dates.today, reason);
  await hours.add(salon, owner.tokens, dates.last, `${reason} last`);
  await hours.add(salon, owner.tokens, dates.beyond, null);
  const answer = await identity.api.getOn(salon.hostname, OPENING_HOURS);
  const body = await answer.text();
  const info = JSON.parse(body) as PublicHours;
  expect(answer.status(), 'public: no credential needed').toBe(200);
  expect(info.weekly).toEqual(stored(own));
  expect(info.exceptions, 'exactly the closed days from today to day 180, in date order').toEqual([
    { date: dates.today, kind: 'closed', opens: null, closes: null, holidayName: null },
    { date: dates.last, kind: 'closed', opens: null, closes: null, holidayName: null },
  ]);
  expect(body, 'no closure reason in the public information').not.toContain('QA private reason');
  const later = week('10:00', '20:00', ['monday']);
  await hours.save(salon, owner.tokens, later);
  expect((await hours.published(salon)).weekly, 'the next request shows the new week').toEqual(stored(later));
  for (const other of [alpha, beta]) {
    const theirs = await hours.published(other);
    expect(JSON.stringify(theirs.weekly), `${other.slug} never shows this salon's hours`).not.toBe(JSON.stringify(stored(later)));
    expect(exceptionsOn(theirs, [dates.last]), `${other.slug} never shows this salon's closure`).toEqual([]);
  }
});

test('TC-F04-S03-004 AC-3: the Admin imports a year as a draft, a file with any bad line is refused whole naming each line and reason, single days are added, changed and removed, and each change is in the platform audit', async ({ identity, operator }) => {
  const calendar = new Calendar(identity);
  const admin = await operator.actor();
  const year = await calendar.freshYear(admin);
  const t0 = await identity.now();
  const audited = `subject_id = ${sqlLiteral(String(year))} AND created_at >= ${sqlLiteral(t0)}::timestamptz`;
  const first: Day[] = [
    { date: `${year}-01-13`, name: 'Duruthu Full Moon Poya Day', kind: 'poya' },
    { date: `${year}-02-04`, name: 'Independence Day', kind: 'public' },
    { date: `${year}-04-14`, name: 'Sinhala and Tamil New Year Day', kind: 'mercantile' },
  ];
  await test.step('a valid file replaces the draft\'s days', async () => {
    const imported = await calendar.import(admin, year, first);
    expect(imported.days.map(d => ({ date: d.date, name: d.name, kind: d.kind })), 'the file\'s days, in date order, and nothing else').toEqual(first);
    expect(imported.state).toBe('draft');
    expect(imported.revision, 'never published').toBe(0);
    const replacement = first.slice(1).map(d => ({ ...d, name: `${d.name} (gazette)` }));
    const again = await calendar.import(admin, year, replacement);
    expect(again.days.map(d => ({ date: d.date, name: d.name, kind: d.kind })), 'a second file replaces the first').toEqual(replacement);
  });
  await test.step('a file with any bad line is refused whole, naming each line and its reason', async () => {
    const before = await calendar.year(admin, year);
    const snapshot = calendarSnapshot(year);
    const file = [
      'date,name,kind',
      `${year}-05-12,Vesak Full Moon Poya Day,poya`,
      `${year + 1}-01-01,A date outside the year,public`,
      `${year}-05-12,The same date again,bank`,
      `${year}-06-10,An unknown kind,festival`,
      `${year}-07-09,,poya`,
      'a malformed row without its columns',
      `${year}-02-30,A date that does not exist,public`,
    ].join('\n');
    const { errors } = await fieldErrors(await calendar.importRaw(admin, year, file), 'bad file');
    expect(errors.map(e => e.line).sort((a, b) => (a ?? 0) - (b ?? 0)), 'each bad line, by number, and no good one').toEqual([3, 4, 5, 6, 7, 8]);
    for (const error of errors) {
      expect(error.field, `line ${error.line}: the file`).toBe('file');
      expect(error.detail.trim().length, `line ${error.line}: a reason`).toBeGreaterThan(0);
    }
    expect(calendarSnapshot(year), 'nothing changed').toBe(snapshot);
    expect((await calendar.year(admin, year)).days, 'the draft is as it was').toEqual(before.days);
  });
  await test.step('single days are added, changed and removed', async () => {
    const day: Day = { date: `${year}-09-07`, name: 'Binara Full Moon Poya Day', kind: 'poya' };
    const added = await calendar.add(admin, year, day);
    expect(added.days.find(d => d.date === day.date), 'the added day').toMatchObject(day);
    const changed = await calendar.change(admin, year, day.date, { name: 'Binara Poya (corrected)', kind: 'bank' });
    expect(changed.days.find(d => d.date === day.date)).toMatchObject({ name: 'Binara Poya (corrected)', kind: 'bank' });
    const removed = await calendar.remove(admin, year, day.date);
    expect(removed.days.find(d => d.date === day.date), 'the removed day is gone').toBeUndefined();
  });
  await calendar.publish(admin, year);
  await test.step('every import, edit and publication is in the platform audit, by the Admin', async () => {
    const rows = platformAuditWhere(`action LIKE 'holiday_calendar.%' AND ${audited}`);
    const actions = rows.map(r => r.action);
    expect(actions.filter(a => /import/.test(a)).length, 'two imports (the refused file imported nothing)').toBe(2);
    for (const kind of [/add/, /chang/, /remov/, /publish/]) expect(actions.filter(a => kind.test(a)).length, `one ${kind.source} entry`).toBe(1);
    for (const row of rows) expect(row.actorId, `${row.action}: the Admin`).toBe(admin.principal.id);
  });
  await calendar.dispose();
});

test('TC-F04-S03-005 AC-3: a Support operator reads the calendar and every change it sends is 403 permission_denied and changes nothing', async ({ identity, operator }) => {
  const calendar = new Calendar(identity);
  const admin = await operator.actor();
  const { year, days, past } = await calendar.shared(admin);
  const support = await operator.actor('OPERATOR_SUPPORT');
  expect((await calendar.list(support)).items.map(y => y.year), 'Support lists the years').toContain(year);
  const read = await calendar.year(support, year);
  expect(read.days.map(d => d.date), 'Support reads the year\'s days').toEqual([past, ...days].map(d => d.date));
  const snapshot = calendarSnapshot(year);
  const changes: [string, () => Promise<import('@playwright/test').APIResponse>][] = [
    ['import', () => calendar.importRaw(support, year, csv([{ date: `${year}-04-04`, name: 'Support import', kind: 'public' }]))],
    ['add a day', () => calendar.addRaw(support, year, { date: `${year}-04-05`, name: 'Support day', kind: 'public' })],
    ['change a day', () => calendar.changeRaw(support, year, days[0].date, { name: 'Support rename', kind: 'bank' })],
    ['remove a day', () => calendar.removeRaw(support, year, days[0].date)],
    ['publish', () => calendar.publishRaw(support, year, read.version)],
  ];
  for (const [label, send] of changes) {
    await test.step(label, async () => {
      await refused(await send(), 403, 'permission_denied', `Support ${label}`);
      expect(calendarSnapshot(year), `Support ${label}: nothing changed`).toBe(snapshot);
    });
  }
  await calendar.dispose();
});

test('TC-F04-S03-006 AC-4: publishing a year needs its current version, makes it visible to every salon as days to review, and changes nothing a salon publishes until it accepts', async ({ identity, operator, clock }) => {
  const calendar = new Calendar(identity);
  const hours = new Hours(identity);
  const admin = await operator.actor();
  const year = await calendar.freshYear(admin);
  const today = `${year}-01-10`;
  await colomboAt(clock, today);
  await operator.renew(admin);
  const past: Day = { date: `${year}-01-05`, name: 'QA past day', kind: 'public' };
  const coming = daysAfter(today, ['poya', 'public', 'bank']);
  const draft = await calendar.import(admin, year, [past, ...coming]);
  await test.step('an older version is 409 conflict and publishes nothing', async () => {
    const snapshot = calendarSnapshot(year);
    await refused(await calendar.publishRaw(admin, year, draft.version - 1), 409, 'conflict', 'stale publication');
    expect(calendarSnapshot(year)).toBe(snapshot);
  });
  const published = await calendar.publish(admin, year, draft.version);
  expect(published.revision, 'the first revision').toBe(1);
  expect(published.state).toBe('published');
  expect(published.publishedAt, 'publication time').not.toBeNull();
  await test.step('publishing again with nothing changed is 409 invalid_state', async () => {
    await refused(await calendar.publishRaw(admin, year, published.version), 409, 'invalid_state', 'nothing to publish');
  });
  for (const salon of [alpha, beta]) {
    await test.step(`${salon.slug} sees the year to review, and nothing changes for it`, async () => {
      const manager = await identity.staff(salon, 'MANAGER');
      const seen = await hours.year(salon, manager.tokens, year);
      expect(seen.revision).toBe(1);
      expect(seen.reviewed, 'to review').toBe(false);
      expect(seen.days.map(d => ({ date: d.date, name: d.name, kind: d.kind })), 'the published days with kind and name').toEqual([past, ...coming]);
      expect(seen.days.find(d => d.date === past.date)!.past, 'a day before the salon\'s today is past').toBe(true);
      for (const day of coming) {
        const shown = seen.days.find(d => d.date === day.date)!;
        expect(shown.past, `${day.date}: still to come`).toBe(false);
        expect(shown.toReview, `${day.date}: to review`).toBe(true);
        expect(shown.choice, `${day.date}: no choice yet`).toBeNull();
      }
      expect(exceptionsOn(await hours.published(salon), coming.map(d => d.date)), 'no public exception until the salon accepts').toEqual([]);
    });
  }
  await calendar.dispose();
});

test('TC-F04-S03-007 AC-5: a salon accepts Closed, Open as usual and Special hours, which apply to its public information at once; a later single-day choice saves at once; another salon is unaffected', async ({ identity, operator, clock }) => {
  const calendar = new Calendar(identity);
  const hours = new Hours(identity);
  const { year, days } = await sharedYear(operator, calendar, clock);
  const [closed, open, special, other] = days;
  const { salon, owner } = await newSalon(operator, identity);
  const second = await newSalon(operator, identity);
  await hours.save(salon, owner.tokens, week());
  await test.step('before acceptance one day\'s choice is 409 invalid_state', async () => {
    const before = hoursSnapshot(salon.id);
    await refused(await hours.chooseRaw(salon, owner.tokens, year, open.date, { choice: 'closed', opens: null, closes: null }), 409, 'invalid_state', 'choice before acceptance');
    expect(hoursSnapshot(salon.id)).toBe(before);
  });
  const choices: ChoiceInput[] = [
    { date: closed.date, choice: 'closed', opens: null, closes: null },
    { date: open.date, choice: 'open', opens: null, closes: null },
    { date: special.date, choice: 'special', opens: '10:00', closes: '14:00' },
    { date: other.date, choice: 'open', opens: null, closes: null },
  ];
  const accepted = await hours.accept(salon, owner.tokens, year, choices);
  expect(accepted.reviewed, 'Reviewed').toBe(true);
  for (const c of choices) {
    expect(accepted.days.find(d => d.date === c.date), `${c.date}: the choice is kept`).toMatchObject({ choice: c.choice, opens: c.opens, closes: c.closes, toReview: false });
  }
  const dates = days.map(d => d.date);
  expect(exceptionsOn(await hours.published(salon), dates), 'closed and special hours apply at once; open as usual adds nothing').toEqual([
    { date: closed.date, kind: 'closed', opens: null, closes: null, holidayName: closed.name },
    { date: special.date, kind: 'special', opens: '10:00', closes: '14:00', holidayName: special.name },
  ]);
  await test.step('after acceptance one day\'s choice saves at once', async () => {
    const changed = await hours.choose(salon, owner.tokens, year, open.date, { choice: 'closed', opens: null, closes: null });
    expect(changed.days.find(d => d.date === open.date)!.choice).toBe('closed');
    expect(changed.reviewed).toBe(true);
    expect(exceptionsOn(await hours.published(salon), [open.date])).toEqual([{ date: open.date, kind: 'closed', opens: null, closes: null, holidayName: open.name }]);
  });
  await test.step('another salon still has the year to review and publishes nothing of these choices', async () => {
    const theirs = await hours.year(second.salon, second.owner.tokens, year);
    expect(theirs.reviewed).toBe(false);
    for (const day of theirs.days.filter(d => !d.past)) expect({ date: day.date, choice: day.choice, toReview: day.toReview }).toEqual({ date: day.date, choice: null, toReview: true });
    expect(exceptionsOn(await hours.published(second.salon), dates)).toEqual([]);
  });
  await calendar.dispose();
});

test('TC-F04-S03-008 AC-6: a revision shows its counts before publishing; afterwards only added and changed days are to review again, unchanged choices still apply and a withdrawn day disappears', async ({ identity, operator, clock }) => {
  const calendar = new Calendar(identity);
  const hours = new Hours(identity);
  const admin = await operator.actor();
  const year = await calendar.freshYear(admin);
  const today = `${year}-01-10`;
  await colomboAt(clock, today);
  await operator.renew(admin);
  const [kept, renamed, withdrawn, added] = daysAfter(today, ['poya', 'public', 'bank', 'mercantile']);
  await calendar.import(admin, year, [kept, renamed, withdrawn]);
  await calendar.publish(admin, year);
  const manager = await identity.staff(alpha, 'MANAGER');
  await hours.accept(alpha, manager.tokens, year, [kept, renamed, withdrawn].map(d => ({ date: d.date, choice: 'closed' as const, opens: null, closes: null })));
  await calendar.change(admin, year, renamed.date, { name: `${renamed.name} (revised)`, kind: renamed.kind });
  await calendar.remove(admin, year, withdrawn.date);
  const draft = await calendar.add(admin, year, added);
  await test.step('before publishing the console counts what the revision adds, changes and withdraws', async () => {
    expect(draft.changes).toEqual({ added: 1, changed: 1, withdrawn: 1 });
    expect(draft.state).toBe('changed');
    expect(Object.fromEntries(draft.days.map(d => [d.date, d.change]))).toEqual({ [kept.date]: 'unchanged', [renamed.date]: 'changed', [added.date]: 'added' });
    expect(draft.withdrawn.map(d => d.date)).toEqual([withdrawn.date]);
  });
  const revised = await calendar.publish(admin, year, draft.version);
  expect(revised.revision).toBe(2);
  const seen = await hours.year(alpha, manager.tokens, year);
  expect(seen.reviewed, 'to review again').toBe(false);
  expect(Object.fromEntries(seen.days.map(d => [d.date, d.toReview])), 'only the added and changed days are to review; the withdrawn day is gone').toEqual({
    [kept.date]: false, [renamed.date]: true, [added.date]: true,
  });
  expect(seen.days.find(d => d.date === kept.date)!.choice, 'the unchanged day keeps its choice').toBe('closed');
  expect(exceptionsOn(await hours.published(alpha), [kept.date, renamed.date, withdrawn.date, added.date]),
    'the unchanged choice applies; the changed day waits for review; the withdrawn day no longer applies').toEqual([
    { date: kept.date, kind: 'closed', opens: null, closes: null, holidayName: kept.name },
  ]);
  const again = await hours.accept(alpha, manager.tokens, year, [renamed, added].map(d => ({ date: d.date, choice: 'open' as const, opens: null, closes: null })));
  expect(again.reviewed, 'reviewed once the new days are chosen').toBe(true);
  await calendar.dispose();
});

test('TC-F04-S03-009 AC-7: a closure from today to a year ahead is saved, closes the day publicly with no reason and is reopened; a past date, a date beyond a year, a long reason and a second closure on a date are refused', async ({ identity, operator, clock }) => {
  const hours = new Hours(identity);
  const today = '2027-04-10';
  await colomboAt(clock, today);
  const { salon, owner } = await newSalon(operator, identity);
  const reason = unique('Staff training');
  const first = await hours.add(salon, owner.tokens, today, reason);
  expect(first).toMatchObject({ date: today, reason, affectedBookings: 0 });
  const last = await hours.add(salon, owner.tokens, plusYear(today), null);
  expect(last).toMatchObject({ date: plusYear(today), reason: null });
  const listed = await hours.closures(salon, owner.tokens);
  expect(listed.find(c => c.id === first.id), 'the team sees the reason').toMatchObject({ date: today, reason });
  expect(listed.find(c => c.id === last.id)).toMatchObject({ date: plusYear(today) });
  expect(await hours.impact(salon, owner.tokens, today), 'no bookings on the day in this stage').toEqual({ date: today, bookings: [] });
  const publicBody = await (await identity.api.getOn(salon.hostname, OPENING_HOURS)).text();
  expect(exceptionsOn(JSON.parse(publicBody), [today])).toEqual([{ date: today, kind: 'closed', opens: null, closes: null, holidayName: null }]);
  expect(publicBody, 'no reason in public').not.toContain(reason);
  const refusals: { label: string; date: string; reason: string | null; status: number; code: string; field?: string }[] = [
    { label: 'yesterday', date: plusDays(today, -1), reason: null, status: 400, code: 'bad_request', field: 'date' },
    { label: 'a year and a day ahead', date: plusDays(plusYear(today), 1), reason: null, status: 400, code: 'bad_request', field: 'date' },
    { label: 'a reason of 61 characters', date: plusDays(today, 3), reason: 'R'.repeat(61), status: 400, code: 'bad_request', field: 'reason' },
    { label: 'a second closure on the same date', date: today, reason: 'again', status: 409, code: 'conflict' },
  ];
  for (const row of refusals) {
    await test.step(row.label, async () => {
      const before = hoursSnapshot(salon.id);
      const { problem } = await refused(await hours.addRaw(salon, owner.tokens, row.date, row.reason), row.status, row.code, row.label);
      if (row.field) expect((problem.errors ?? []).map(e => e.field), `${row.label}: on ${row.field}`).toEqual([row.field]);
      expect(hoursSnapshot(salon.id), `${row.label}: nothing saved`).toBe(before);
    });
  }
  await test.step('a reason of exactly 60 characters is kept', async () => {
    const sixty = await hours.add(salon, owner.tokens, plusDays(today, 4), 'S'.repeat(60));
    expect(sixty.reason).toBe('S'.repeat(60));
  });
  await test.step('Reopen removes the closure, publicly too, and a second Reopen finds nothing', async () => {
    expect((await hours.reopenRaw(salon, owner.tokens, first.id)).status()).toBe(204);
    expect((await hours.closures(salon, owner.tokens)).find(c => c.id === first.id)).toBeUndefined();
    expect(exceptionsOn(await hours.published(salon), [today])).toEqual([]);
    await refused(await hours.reopenRaw(salon, owner.tokens, first.id), 404, 'not_found', 'reopened twice');
  });
});

test('TC-F04-S03-010 AC-7: of eight closures sent at once for one date exactly one is kept and the others are 409 conflict', async ({ identity, operator }) => {
  const hours = new Hours(identity);
  const { salon, owner } = await newSalon(operator, identity);
  const date = plusDays(localDate(await identity.now(), COLOMBO), 7);
  const reasons = Array.from({ length: 8 }, (_, n) => `QA race ${n}`);
  const responses = await Promise.all(reasons.map(r => hours.addRaw(salon, owner.tokens, date, r)));
  const created = responses.map((r, n) => ({ r, n })).filter(x => x.r.status() === 201);
  expect(created.length, 'exactly one closure').toBe(1);
  for (const r of responses.filter(r => r.status() !== 201)) await refused(r, 409, 'conflict', 'losing closure');
  expect(identitySql(`SELECT count(*) FROM staff_closure WHERE tenant_id = ${sqlLiteral(salon.id)} AND closure_date = ${sqlLiteral(date)}`), 'one stored row').toBe('1');
  const kept = (await hours.closures(salon, owner.tokens)).filter(c => c.date === date);
  expect(kept.map(c => c.reason), 'the one kept is the winner\'s').toEqual([reasons[created[0].n]]);
});

test('TC-F04-S03-011 AC-8: the public information follows weekly hours, then accepted holiday choices, then closures; a holiday still to review changes nothing; the bookings a change affects are none in this stage', async ({ identity, operator, clock }) => {
  const calendar = new Calendar(identity);
  const hours = new Hours(identity);
  const admin = await operator.actor();
  const year = await calendar.freshYear(admin);
  const today = `${year}-01-10`;
  await colomboAt(clock, today);
  await operator.renew(admin);
  const [closedDay, specialDay, closureOnHoliday, openDay, toReview] = daysAfter(today, ['poya', 'public', 'bank', 'mercantile', 'poya']);
  await calendar.import(admin, year, [closedDay, specialDay, closureOnHoliday, openDay]);
  await calendar.publish(admin, year);
  const manager = await identity.staff(alpha, 'MANAGER');
  const weekly = week('09:00', '18:00', ['sunday']);
  const settings = await hours.save(alpha, manager.tokens, weekly);
  expect(settings.outsideHours, 'no booking falls outside the hours in this stage').toEqual([]);
  await hours.accept(alpha, manager.tokens, year, [
    { date: closedDay.date, choice: 'closed', opens: null, closes: null },
    { date: specialDay.date, choice: 'special', opens: '10:00', closes: '13:00' },
    { date: closureOnHoliday.date, choice: 'special', opens: '11:00', closes: '15:00' },
    { date: openDay.date, choice: 'open', opens: null, closes: null },
  ]);
  const closure = await hours.add(alpha, manager.tokens, closureOnHoliday.date, null);
  expect(closure.affectedBookings, 'no booking on the closed day in this stage').toBe(0);
  await calendar.add(admin, year, toReview);
  await calendar.publish(admin, year);
  const ordinary = nextWeekday(plusDays(today, 1), 'tuesday');
  const sunday = nextWeekday(plusDays(today, 1), 'sunday');
  const info = await hours.published(alpha);
  expect(info.weekly, 'the weekly hours, Sunday closed').toEqual(stored(weekly));
  expect(exceptionsOn(info, [ordinary, sunday, closedDay.date, specialDay.date, closureOnHoliday.date, openDay.date, toReview.date]), [
    'an ordinary day and a closed weekday follow the weekly hours (no exception)',
    'an accepted closed holiday is closed; accepted special hours apply',
    'a closure overrides special hours on its holiday',
    'open as usual and a holiday still to review change nothing',
  ].join('; ')).toEqual([
    { date: closedDay.date, kind: 'closed', opens: null, closes: null, holidayName: closedDay.name },
    { date: specialDay.date, kind: 'special', opens: '10:00', closes: '13:00', holidayName: specialDay.name },
    { date: closureOnHoliday.date, kind: 'closed', opens: null, closes: null, holidayName: closureOnHoliday.name },
  ].sort((a, b) => a.date.localeCompare(b.date)));
  await calendar.dispose();
});

test('TC-F04-S03-012 AC-9: "today" is the salon\'s local date: at 23:30 in Colombo today\'s closure is allowed and yesterday\'s refused, and at 00:01 a holiday and a closure of the day before have passed', async ({ identity, operator, clock }) => {
  const calendar = new Calendar(identity);
  const hours = new Hours(identity);
  await calendar.shared(await operator.actor());
  const day = SHARED.days[0].date;
  await clock.set(`${day}T18:00:00Z`);
  expect(localDate(await identity.now(), COLOMBO), '18:00 UTC is 23:30 the same day in Colombo').toBe(day);
  const { salon, owner } = await newSalon(operator, identity);
  const closure = await test.step('23:30 in Colombo: today allowed, yesterday refused, the holiday not yet past', async () => {
    const made = await hours.add(salon, owner.tokens, day, null);
    const { problem } = await refused(await hours.addRaw(salon, owner.tokens, plusDays(day, -1), null), 400, 'bad_request', 'yesterday at 23:30');
    expect((problem.errors ?? []).map(e => e.field)).toEqual(['date']);
    const seen = (await hours.year(salon, owner.tokens, SHARED.year)).days.find(d => d.date === day)!;
    expect({ past: seen.past, toReview: seen.toReview }).toEqual({ past: false, toReview: true });
    expect(exceptionsOn(await hours.published(salon), [day]).map(e => e.kind), 'today\'s closure is public').toEqual(['closed']);
    return made;
  });

  await clock.set(`${day}T18:31:00Z`);
  const next = plusDays(day, 1);
  expect(localDate(await identity.now(), COLOMBO), '18:31 UTC is 00:01 the next day in Colombo').toBe(next);
  const fresh = (await identity.login(salon, owner.account)).tokens;
  await test.step('00:01 in Colombo: the holiday is past and the closure has left the public window', async () => {
    const seen = (await hours.year(salon, fresh, SHARED.year)).days.find(d => d.date === day)!;
    expect(seen.past, 'past once its local date ended').toBe(true);
    expect(exceptionsOn(await hours.published(salon), [day]), 'the window starts at the new local today').toEqual([]);
    await refused(await hours.addRaw(salon, fresh, day, null), 400, 'bad_request', 'the day just ended');
    await refused(await hours.reopenRaw(salon, fresh, closure.id), 409, 'invalid_state', 'reopening a closure that has passed');
  });
  await test.step('at the same instant it is still the day in London, so a London salon may close it', async () => {
    expect(localDate(await identity.now(), 'Europe/London')).toBe(day);
    const london = await newSalon(operator, identity, { timeZone: 'Europe/London' });
    const made = await hours.add(london.salon, london.owner.tokens, day, null);
    expect(made.date).toBe(day);
    const seen = (await hours.year(london.salon, london.owner.tokens, SHARED.year)).days.find(d => d.date === day)!;
    expect(seen.past, 'the holiday is not past in London yet').toBe(false);
  });
  await calendar.dispose();
});

/** Every salon settings route of this stage, with a body that is valid apart from who sends it. */
function salonRoutes(year: number, holiday: string, closureId: string, date: string) {
  return [
    { label: 'read hours', method: 'GET', path: HOURS_SETTINGS, data: undefined, write: false },
    { label: 'save hours', method: 'PUT', path: HOURS_SETTINGS, data: { version: 1, weekly: week() }, write: true },
    { label: 'read holidays', method: 'GET', path: HOLIDAYS, data: undefined, write: false },
    { label: 'accept holidays', method: 'POST', path: `${HOLIDAYS}/${year}/acceptance`, data: { version: 1, choices: [{ date: holiday, choice: 'closed', opens: null, closes: null }] }, write: true },
    { label: 'choose a day', method: 'PUT', path: `${HOLIDAYS}/${year}/days/${holiday}`, data: { choice: 'open', opens: null, closes: null }, write: true },
    { label: 'read closures', method: 'GET', path: CLOSURES, data: undefined, write: false },
    { label: 'read impact', method: 'GET', path: `${CLOSURES}/impact?date=${date}`, data: undefined, write: false },
    { label: 'add a closure', method: 'POST', path: CLOSURES, data: { date: plusDays(date, 1), reason: null }, write: true },
    { label: 'reopen', method: 'DELETE', path: `${CLOSURES}/${closureId}`, data: undefined, write: true },
  ];
}

/** Salons the operator creates now, each with a saved week, the shared year accepted and a closure; the clock on the shared year's today. */
async function arrangedSalons(identity: Identity, operator: Operator, calendar: Calendar, clock: TestClock, count: number) {
  const hours = new Hours(identity);
  const { today, year, days } = await sharedYear(operator, calendar, clock);
  const out = [];
  for (let n = 0; n < count; n++) {
    const { salon, owner, created } = await newSalon(operator, identity);
    await hours.save(salon, owner.tokens, week());
    await hours.accept(salon, owner.tokens, year, allShared('closed'));
    const reason = unique(`QA ${salon.slug} reason`);
    const closureId = (await hours.add(salon, owner.tokens, plusDays(today, 3), reason)).id;
    out.push({ salon, owner, created, closureId, reason });
  }
  return { year, today, days, salons: out };
}

test('TC-F04-S03-013 AC-10: another salon\'s Owner gets "not found" from every hours, holidays and closures endpoint, and another salon\'s closure id is "not found" exactly as an unknown one', async ({ identity, operator, clock }) => {
  const calendar = new Calendar(identity);
  const hours = new Hours(identity);
  const { year, today, days, salons: [a, b] } = await arrangedSalons(identity, operator, calendar, clock, 2);
  const bBefore = hoursSnapshot(b.salon.id);
  await test.step('the other salon\'s Owner on this salon\'s hostname', async () => {
    for (const route of salonRoutes(year, days[0].date, a.closureId, today)) {
      const aBefore = hoursSnapshot(a.salon.id);
      await refused(await hours.call(a.salon, route.method, route.path, b.owner.tokens, route.data), 404, 'not_found', `other salon: ${route.label}`);
      expect(hoursSnapshot(a.salon.id), `${route.label}: unchanged`).toBe(aBefore);
    }
  });
  await test.step('the other salon\'s closure id is the same answer as an unknown id', async () => {
    const foreign = await refused(await hours.reopenRaw(a.salon, a.owner.tokens, b.closureId), 404, 'not_found', 'other salon\'s closure');
    const unknown = await refused(await hours.reopenRaw(a.salon, a.owner.tokens, randomUUID()), 404, 'not_found', 'unknown closure');
    expect(comparable(foreign.answer.body).replace(/[0-9a-f-]{36}/g, 'ID')).toBe(comparable(unknown.answer.body).replace(/[0-9a-f-]{36}/g, 'ID'));
  });
  await test.step('this salon\'s reads name nothing of the other', async () => {
    const lists = JSON.stringify(await hours.closures(a.salon, a.owner.tokens)) + JSON.stringify(await hours.published(a.salon));
    for (const value of [b.closureId, b.reason]) expect(lists, 'nothing of the other salon').not.toContain(value);
  });
  expect(hoursSnapshot(b.salon.id), 'the other salon changed in no way').toBe(bBefore);
  await calendar.dispose();
});

test('TC-F04-S03-014 AC-10: with no tenant context the four new tables return no rows to the application role, and a salon\'s context shows only its own', async ({ identity, operator, clock }) => {
  const calendar = new Calendar(identity);
  const { salons: [a, b] } = await arrangedSalons(identity, operator, calendar, clock, 2);
  for (const table of HOURS_TABLES) {
    await test.step(table, async () => {
      expect(hoursRowCounts(a.salon.id)[table], `${table}: the first salon has rows`).toBeGreaterThan(0);
      expect(hoursRowCounts(b.salon.id)[table], `${table}: the second salon has rows`).toBeGreaterThan(0);
      expect(identitySql(`SELECT count(*) FROM ${table}`, null), `${table}: no tenant, no rows`).toBe('0');
      expect(Number(identitySql(`SELECT count(*) FROM ${table}`, a.salon.id)), `${table}: a salon sees its rows`).toBeGreaterThan(0);
      expect(identitySql(`SELECT count(*) FROM ${table} WHERE tenant_id <> ${sqlLiteral(a.salon.id)}`, a.salon.id), `${table}: and no other salon's row`).toBe('0');
    });
  }
  await calendar.dispose();
});

test('TC-F04-S03-015 AC-10: the Owner and Manager may read and change hours, holidays and closures; the Receptionist, Professional and Accountant are 403 permission_denied and signed out is 401, changing nothing; the public information answers everyone', async ({ identity, operator, clock }) => {
  const calendar = new Calendar(identity);
  const hours = new Hours(identity);
  const { year, today, days } = await sharedYear(operator, calendar, clock);
  const keeper = await identity.staff(alpha, 'MANAGER');
  await hours.save(alpha, keeper.tokens, week());
  // alpha keeps its choices between runs: accept the shared year only once.
  if (!(await hours.year(alpha, keeper.tokens, year)).reviewed) await hours.accept(alpha, keeper.tokens, year, allShared('closed'));
  const kept = await hours.add(alpha, keeper.tokens, plusDays(today, randomInt(30, 300)), null);
  const expected: Record<Role, 'allowed' | 'refused'> = { OWNER: 'allowed', MANAGER: 'allowed', RECEPTIONIST: 'refused', PROFESSIONAL: 'refused', ACCOUNTANT: 'refused' };
  try {
    for (const role of Object.keys(expected) as Role[]) {
      await test.step(`${role}: ${expected[role]}`, async () => {
        const actor = await identity.staff(alpha, role);
        expect((await hours.published(alpha)).weekly, 'the public information answers').not.toBeNull();
        if (expected[role] === 'allowed') {
          const settings = await hours.settings(alpha, actor.tokens);
          await hours.save(alpha, actor.tokens, week('08:00', '17:00'), settings.version);
          await hours.choose(alpha, actor.tokens, year, days[1].date, { choice: role === 'OWNER' ? 'open' : 'closed', opens: null, closes: null });
          const made = await hours.add(alpha, actor.tokens, plusDays(today, role === 'OWNER' ? 20 : 21), null);
          expect((await hours.closures(alpha, actor.tokens)).some(c => c.id === made.id)).toBe(true);
          expect((await hours.reopenRaw(alpha, actor.tokens, made.id)).status()).toBe(204);
          return;
        }
        for (const route of salonRoutes(year, days[1].date, kept.id, today)) {
          const before = hoursSnapshot(alpha.id);
          await refused(await hours.call(alpha, route.method, route.path, actor.tokens, route.data), 403, 'permission_denied', `${role} ${route.label}`);
          expect(hoursSnapshot(alpha.id), `${role} ${route.label}: nothing changed`).toBe(before);
        }
      });
    }
    await test.step('signed out', async () => {
      for (const route of salonRoutes(year, days[1].date, kept.id, today)) {
        const before = hoursSnapshot(alpha.id);
        expect((await hours.call(alpha, route.method, route.path, undefined, route.data)).status(), `signed out ${route.label}`).toBe(401);
        expect(hoursSnapshot(alpha.id)).toBe(before);
      }
    });
  } finally {
    // The closure is alpha's, kept between runs otherwise: reopen it (its date is still to come).
    await hours.reopenRaw(alpha, (await identity.staff(alpha, 'MANAGER')).tokens, kept.id);
  }
  await calendar.dispose();
});

test('TC-F04-S03-016 AC-10: a support session reads hours, holidays and closures as the Owner does and every change is 403 support_read_only', async ({ identity, operator }) => {
  const calendar = new Calendar(identity);
  const hours = new Hours(identity);
  const { created, salon, owner } = await newSalon(operator, identity);
  const { year, days } = await calendar.shared(await operator.actor());
  await hours.save(salon, owner.tokens, week());
  const date = plusDays(localDate(await identity.now(), COLOMBO), 5);
  const closure = await hours.add(salon, owner.tokens, date, 'QA team-only reason');
  const support = await operator.actor('OPERATOR_SUPPORT');
  const session = await supportJson<SupportStart>(await operator.startSupport(support, created.id, { reason: 'QA F04 hours support reason' }), 'supportStart', 201);
  const tokens: Tokens = await operator.supportCredential(await operator.exchange(session.entryCode, salon.hostname));
  expect(await hours.settings(salon, tokens), 'the same hours').toEqual(await hours.settings(salon, owner.tokens));
  expect(await hours.holidays(salon, tokens), 'the same holidays').toEqual(await hours.holidays(salon, owner.tokens));
  expect(await hours.closures(salon, tokens), 'the same closures').toEqual(await hours.closures(salon, owner.tokens));
  expect(await hours.impact(salon, tokens, date)).toEqual({ date, bookings: [] });
  for (const route of salonRoutes(year, days[0].date, closure.id, date).filter(r => r.write)) {
    const before = hoursSnapshot(salon.id);
    await refused(await hours.call(salon, route.method, route.path, tokens, route.data), 403, 'support_read_only', `support ${route.label}`);
    expect(hoursSnapshot(salon.id), `support ${route.label}: nothing changed`).toBe(before);
  }
  await calendar.dispose();
});

test('TC-F04-S03-017 AC-10: a suspended salon answers 403 salon_suspended and a read-only salon reads but refuses every change with salon_read_only, as the other settings endpoints do', async ({ identity, operator, clock }) => {
  test.setTimeout(240_000);
  const calendar = new Calendar(identity);
  const hours = new Hours(identity);
  await clock.set('2026-11-20T07:00:00Z');
  const admin = await operator.actor();
  const { year, days } = await calendar.shared(admin);
  const date = plusDays(localDate(await identity.now(), COLOMBO), 300);
  await test.step('suspended', async () => {
    const { created, salon, owner } = await newSalon(operator, identity);
    await hours.save(salon, owner.tokens, week());
    const closure = await hours.add(salon, owner.tokens, date, null);
    await operatorJson(await operator.suspend(admin, created.id, await operator.version(admin, created.id)), 'salon');
    for (const route of salonRoutes(year, days[0].date, closure.id, date)) {
      const before = hoursSnapshot(salon.id);
      await refused(await hours.call(salon, route.method, route.path, owner.tokens, route.data), 403, 'salon_suspended', `suspended ${route.label}`);
      expect(hoursSnapshot(salon.id), `suspended ${route.label}: nothing changed`).toBe(before);
    }
  });
  await test.step('read-only', async () => {
    const { created, salon, owner } = await newSalon(operator, identity);
    const own = week('08:30', '17:30');
    await hours.save(salon, owner.tokens, own);
    const closure = await hours.add(salon, owner.tokens, date, null);
    await readOnlySalon(operator, clock, admin, created.id);
    const now = await identity.login(salon, owner.account);
    expect((await hours.settings(salon, now.tokens)).weekly, 'the hours are still readable').toEqual(stored(own));
    expect((await hours.closures(salon, now.tokens)).map(c => c.id)).toContain(closure.id);
    for (const route of salonRoutes(year, days[0].date, closure.id, date).filter(r => r.write)) {
      const before = hoursSnapshot(salon.id);
      await refused(await hours.call(salon, route.method, route.path, now.tokens, route.data), 403, 'salon_read_only', `read-only ${route.label}`);
      expect(hoursSnapshot(salon.id), `read-only ${route.label}: nothing changed`).toBe(before);
    }
  });
  await calendar.dispose();
});

test('TC-F04-S03-018 AC-10: the operator calendar routes answer 404 on a salon\'s hostname, even to an Admin, and refuse a salon user\'s credential on the console', async ({ identity, operator }) => {
  const calendar = new Calendar(identity);
  const admin = await operator.actor();
  const { year, days } = await calendar.shared(admin);
  const version = (await calendar.year(admin, year)).version;
  const manager = await identity.staff(alpha, 'MANAGER');
  const snapshot = calendarSnapshot(year);
  const day = days[0].date;
  const routes: [string, string, unknown][] = [
    ['GET', '', undefined], ['GET', `/${year}`, undefined],
    ['POST', `/${year}/days`, { date: `${year}-06-07`, name: 'QA', kind: 'public' }],
    ['PUT', `/${year}/days/${day}`, { name: 'QA', kind: 'bank' }],
    ['DELETE', `/${year}/days/${day}`, undefined],
    ['POST', `/${year}/publication`, { version }],
  ];
  for (const [method, path, data] of routes) {
    await test.step(`${method} ${CALENDARS}${path}`, async () => {
      for (const [who, credential] of [['Admin', admin.tokens], ['alpha\'s Manager', manager.tokens], ['no credential', null]] as const) {
        await refused(await calendar.call(credential, method, path, data, alpha.hostname), 404, 'not_found', `${who} on alpha's host`);
      }
      const r = await calendar.call(manager.tokens, method, path, data);
      expect([401, 403], `alpha's Manager on the console host (${r.status()})`).toContain(r.status());
    });
  }
  await refused(await calendar.importRaw(admin, year, csv([{ date: `${year}-06-08`, name: 'QA', kind: 'public' }]), alpha.hostname), 404, 'not_found', 'import on alpha\'s host');
  expect([401, 403]).toContain((await calendar.importRaw(manager.tokens, year, csv([{ date: `${year}-06-08`, name: 'QA', kind: 'public' }]))).status());
  expect(calendarSnapshot(year), 'nothing changed').toBe(snapshot);
  await calendar.dispose();
});

test('TC-F04-S03-019 AC-11: a stale version on saving hours or accepting holidays is 409 conflict and changes nothing, and of simultaneous saves or acceptances on one version exactly one succeeds', async ({ identity, operator, clock }) => {
  const calendar = new Calendar(identity);
  const hours = new Hours(identity);
  const people = [await identity.staff(alpha, 'MANAGER'), await identity.staff(alpha, 'OWNER')];
  await test.step('hours: a stale version', async () => {
    const seen = await hours.settings(alpha, people[1].tokens);
    await hours.save(alpha, people[0].tokens, week('09:15'), seen.version);
    const before = hoursSnapshot(alpha.id);
    await refused(await hours.saveRaw(alpha, people[1].tokens, seen.version, week('10:15')), 409, 'conflict', 'stale hours');
    expect(hoursSnapshot(alpha.id)).toBe(before);
  });
  await test.step('hours: ten saves at once on one version', async () => {
    const current = await hours.settings(alpha, people[0].tokens);
    const weeks = Array.from({ length: 10 }, (_, n) => week(`0${n}:00`.slice(-5), '20:00'));
    const responses = await Promise.all(weeks.map((w, n) => hours.saveRaw(alpha, people[n % 2].tokens, current.version, w)));
    const ok = responses.map((r, n) => ({ r, n })).filter(x => x.r.status() === 200);
    expect(ok.length, 'exactly one save').toBe(1);
    for (const r of responses.filter(r => r.status() !== 200)) await refused(r, 409, 'conflict', 'losing save');
    expect((await hours.settings(alpha, people[0].tokens)).weekly, 'the winner\'s week, whole').toEqual(stored(weeks[ok[0].n]));
  });
  const { year } = await sharedYear(operator, calendar, clock);
  const { salon, owner } = await newSalon(operator, identity);
  await test.step('acceptance: six at once on the first version', async () => {
    const v = (await hours.year(salon, owner.tokens, year)).version;
    const responses = await Promise.all(Array.from({ length: 6 }, (_, n) => hours.acceptRaw(salon, owner.tokens, year, v, allShared(n % 2 ? 'open' : 'closed'))));
    const ok = responses.map((r, n) => ({ r, n })).filter(x => x.r.status() === 200);
    expect(ok.length, 'exactly one acceptance').toBe(1);
    for (const r of responses.filter(r => r.status() !== 200)) await refused(r, 409, 'conflict', 'losing acceptance');
    const winner = ok[0].n % 2 ? 'open' : 'closed';
    expect((await hours.year(salon, owner.tokens, year)).days.filter(d => !d.past).map(d => d.choice), 'the winner\'s choices, whole').toEqual(SHARED.days.map(() => winner));
  });
  await test.step('acceptance: a stale version', async () => {
    const before = hoursSnapshot(salon.id);
    await refused(await hours.acceptRaw(salon, owner.tokens, year, 0, allShared('open')), 409, 'conflict', 'stale acceptance');
    expect(hoursSnapshot(salon.id)).toBe(before);
  });
  await calendar.dispose();
});

test('TC-F04-S03-020 AC-4: of publications sent at once by two Admins on one version exactly one publishes', async ({ identity, operator }) => {
  const calendar = new Calendar(identity);
  const admins = [await operator.actor(), await operator.actor()];
  const year = await calendar.freshYear(admins[0]);
  const draft = await calendar.import(admins[0], year, [{ date: `${year}-08-08`, name: 'QA race holiday', kind: 'poya' }]);
  const t0 = await identity.now();
  const responses = await Promise.all(Array.from({ length: 6 }, (_, n) => calendar.publishRaw(admins[n % 2], year, draft.version)));
  expect(responses.filter(r => r.status() === 200).length, 'exactly one publication').toBe(1);
  for (const r of responses.filter(r => r.status() !== 200)) {
    const answer = await r.json() as { code: string };
    expect(r.status(), 'a losing publication is a 409').toBe(409);
    expect(['conflict', 'invalid_state'], 'refused for its stale version or for nothing left to publish').toContain(answer.code);
  }
  const after = await calendar.year(admins[0], year);
  expect(after.revision, 'published once').toBe(1);
  const published = platformAuditWhere(`action LIKE 'holiday_calendar.%publish%' AND subject_id = ${sqlLiteral(String(year))} AND created_at >= ${sqlLiteral(t0)}::timestamptz`);
  expect(published.length, 'one publication in the platform audit').toBe(1);
  await calendar.dispose();
});

test('TC-F04-S03-021 AC-11: hours, acceptance, a day\'s choice, a closure and a reopening are each audited with actor, time, action and the day or date, and no closure reason reaches the audit, the logs or the public information', async ({ identity, operator, clock }) => {
  const calendar = new Calendar(identity);
  const hours = new Hours(identity);
  const { year, today, days } = await sharedYear(operator, calendar, clock);
  const { salon, owner } = await newSalon(operator, identity);
  await hours.save(salon, owner.tokens, week());
  const base = hoursAudit(salon.id, owner.principal.id).length;
  const offsets = logOffsets();
  const marker = unique('QAREASON').replace(' ', '');
  const t0 = await identity.now();
  await hours.save(salon, owner.tokens, week('09:00', '18:00', ['sunday'], { monday: ['10:00', '18:00'] }));
  await hours.accept(salon, owner.tokens, year, allShared('closed'));
  await hours.choose(salon, owner.tokens, year, days[1].date, { choice: 'open', opens: null, closes: null });
  const closureDate = plusDays(today, 2);
  const closure = await hours.add(salon, owner.tokens, closureDate, `Training ${marker}`);
  expect((await hours.reopenRaw(salon, owner.tokens, closure.id)).status()).toBe(204);
  const refusedReason = await hours.addRaw(salon, owner.tokens, plusDays(today, -3), `Late ${marker}`);
  expect(refusedReason.status()).toBe(400);
  const t1 = await identity.now();
  const rows = hoursAudit(salon.id, owner.principal.id).slice(base);
  expect(rows.length, 'one entry each for hours, acceptance, choice, closure and reopening').toBe(5);
  const [saved, accepted, chosen, closed, reopened] = rows;
  expect(new Set(rows.map(r => r.action)).size, 'five different actions').toBe(5);
  for (const row of rows) {
    expect(row.actorId, `${row.action}: the acting staff account`).toBe(owner.principal.id);
    expect(row.actorKind.toLowerCase()).toContain('staff');
    expect(Date.parse(row.at), `${row.action}: when`).toBeGreaterThanOrEqual(Date.parse(t0) - 1000);
    expect(Date.parse(row.at)).toBeLessThanOrEqual(Date.parse(t1) + 1000);
    expect(row.details, `${row.action}: no reason`).not.toContain(marker);
  }
  expect(saved.details.toLowerCase(), 'the hours entry names the changed day').toContain('monday');
  expect(saved.details.toLowerCase(), 'and not a day it did not change').not.toContain('tuesday');
  for (const d of days) expect(accepted.details, `the acceptance names ${d.date}`).toContain(d.date);
  expect(chosen.details, 'the choice names its date').toContain(days[1].date);
  expect(closed.details, 'the closure names its date').toContain(closureDate);
  expect(reopened.details, 'the reopening names its date').toContain(closureDate);
  expect(await refusedReason.text(), 'no reason in an error').not.toContain(marker);
  expect(await (await identity.api.getOn(salon.hostname, OPENING_HOURS)).text(), 'no reason in public').not.toContain(marker);
  const logs = logsSince(offsets);
  expect(logs.length, 'the logs were written to').toBeGreaterThan(0);
  expect(logs, 'no closure reason in the API or web log').not.toContain(marker);
  await calendar.dispose();
});

