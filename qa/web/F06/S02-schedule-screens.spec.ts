/**
 * F06-S02 the schedule in the browser: each card's Hours row and Schedule button, the schedule sheet
 * with Weekly hours and Date changes, their messages, the two shortcuts, the warning outside opening
 * hours, the affected-bookings list, who sees which controls and the note, the loading, unavailable,
 * conflict and refused states, the leave guard, every width of the matrix, keyboard use and accessibility.
 *
 * Expected results are the F06-S02 spec's (acceptance criteria, "UI and UX") and the prototype (Salon web,
 * Staff, the F06 block: `hrsLine`, `schOpen`, `schErr`, `schOut`, `schWeek`, `schCopy`, `schSalon`, `schSave`,
 * `schOv`, `ovSave`, `schList`, `schPaint`), with the lead's rulings: F06 writes times with the 24-hour
 * `formatClock`, so every expected string with a time is in `SCHEDULE_TEXT` (qa/shared/team-schedule.ts), and
 * no working day may be filled by breaks. The spec's "Corrections at close" take precedence over its criteria.
 * Rules the API cases prove (every boundary, every role on every endpoint) are not proved again: each case
 * checks what the person sees, and persistence once through the API.
 *
 * The test ids and the names of the times are the build's, from the handoff, all of them in `PENDING_F06_S02`.
 * `cacheComponents` keeps a route it has left in the document, hidden, so every lookup targets the visible
 * element.
 */
import type { Locator, Page } from '@playwright/test';

import { DESIGN_WIDTHS } from '../../shared/design-reference';
import { expect, type AccountBrowser } from '../../shared/identity-browser';
import type { SignedStaff } from '../../shared/identity';
import type { Operator } from '../../shared/operator';
import { fit, focusIndicator, seriousViolations } from '../../shared/page-checks';
import { DAYS, plusDays, week, type DayName } from '../../shared/salon-hours';
import { memberInput, teamSalon, type Team, type TeamMember, type WorkDay } from '../../shared/team';
import { TEAM_ROUTE, button, card, id, open, openSchedule, ready, salonToday, settled, sheetOf, supportBrowser, test } from '../../shared/team-browser';
import {
  LONG, PENDING_F06_S02, SCHEDULE_LIMITS, SCHEDULE_TEXT, brk, calendarDay, changeInput, copyOf, expectWeek, noWorkingDays, offChange, offDay, shownTime, weekOf, workingDay, type Schedules,
} from '../../shared/team-schedule';

test.describe.configure({ timeout: 180_000 });

const W = PENDING_F06_S02.web;
const N = PENDING_F06_S02.names;
const TEXT = SCHEDULE_TEXT;

const SAVE = 'Save hours';
const ADD_CHANGE = '+ Change a date';
const COPY_MONDAY = 'Copy Monday to working days';
const USE_SALON = /Use the salon.s hours/;
const ADD_BREAK = /Add break/;

/** The sheet, whether a save left it open or closed it: the spec fixes that only when it has bookings to list (AC-11). */
async function sheetStillOrAgain(page: Page, memberId: string) {
  await settled(page);
  return (await sheetOf(page).count()) > 0 ? sheetOf(page) : openSchedule(page, memberId);
}

const dateTab = (sheet: Locator) => sheet.getByRole('tab', { name: 'Date changes' });
const weekTab = (sheet: Locator) => sheet.getByRole('tab', { name: 'Weekly hours' });

/** A time by the accessible name AC-16 asks for, whole: "Starts" is also part of "Break 1 starts". */
const timeField = (sheet: Locator, name: string) => sheet.getByRole('combobox', { name, exact: true });
/** Sets a time through F04's TimeSelect. */
async function setTime(page: Page, sheet: Locator, name: string, hhmm: string) {
  await timeField(sheet, name).click();
  await page.getByRole('option', { name: shownTime(hhmm), exact: true }).click();
  await expect(page.getByRole('listbox')).toHaveCount(0);
}
const hoursOn = async (page: Page, sheet: Locator, day: DayName, starts: string, finishes: string) => {
  await setTime(page, sheet, N.starts(LONG[day]), starts);
  await setTime(page, sheet, N.finishes(LONG[day]), finishes);
};
const breakOn = async (page: Page, sheet: Locator, day: DayName, n: number, starts: string, ends: string) => {
  await setTime(page, sheet, N.breakStarts(LONG[day], n), starts);
  await setTime(page, sheet, N.breakEnds(LONG[day], n), ends);
};
/** Adds a break to a day through its own control and sets its times. */
async function addBreak(page: Page, sheet: Locator, day: DayName, n: number, starts: string, ends: string) {
  await button(id(sheet, W.day(day)), ADD_BREAK).click();
  await breakOn(page, sheet, day, n, starts, ends);
}
const breakRows = (sheet: Locator, day: DayName) => sheet.getByRole('combobox', { name: new RegExp(`^${LONG[day]} break \\d starts$`) });
const savedToast = (page: Page) => page.getByText(TEXT.saved).filter({ visible: true });

/** Chooses a date in the change form, moving the Calendar on to its month when it is not this one. */
async function pickDate(sheet: Locator, date: string) {
  const page = sheet.page();
  await id(sheet, W.changeDate).click();
  const calendar = page.getByTestId(W.changeCalendar);
  for (let n = 0; n < 13 && !(await calendarDay(calendar, date).isVisible()); n++) await calendar.getByRole('button', { name: /next month/i }).click();
  await calendarDay(calendar, date).click();
}
const segment = (sheet: Locator, name: string) => sheet.getByRole('radio', { name, exact: true }).or(sheet.getByRole('button', { name, exact: true }));
const changeRow = (sheet: Locator, date: string) => sheet.locator(`[data-testid="${W.changeRow}"][data-date="${date}"]`).filter({ visible: true });
const noteField = (sheet: Locator) => sheet.getByLabel(/^Note/);

/** A salon with opening hours and a person on a known week. */
async function arrange(operator: Operator, accounts: AccountBrowser, team: Team, schedules: Schedules, weekly: WorkDay[] = weekOf(), name = 'Sanduni Perera') {
  const made = await teamSalon(operator, accounts.identity);
  const person = await team.add(made.salon, made.owner.tokens, memberInput({ name }));
  await schedules.save(made.salon, made.owner.tokens, person.id, weekly);
  return { ...made, person };
}

/** The controls a caller without `team.manage` must not have. */
async function expectNoChangeControls(sheet: Locator, label: string) {
  await expect(sheet.getByRole('button', { name: SAVE }), `${label}: no Save hours`).toHaveCount(0);
  await expect(sheet.getByRole('button', { name: ADD_BREAK }), `${label}: no + Add break`).toHaveCount(0);
  await expect(sheet.getByRole('button', { name: COPY_MONDAY }), `${label}: no Copy Monday`).toHaveCount(0);
  await expect(sheet.getByRole('button', { name: USE_SALON }), `${label}: no Use the salon's hours`).toHaveCount(0);
  await dateTab(sheet).click();
  await expect(sheet.getByRole('button', { name: ADD_CHANGE }), `${label}: no + Change a date`).toHaveCount(0);
  await expect(sheet.getByRole('button', { name: /^Remove/ }), `${label}: no Remove`).toHaveCount(0);
}

// ---------------------------------------------------------------- AC-12 the Hours row, and the card

test('TC-F06-S02-030 AC-12: each card\'s Hours row summarises the week: one unbroken stretch with its break or breaks, a single day, "Varies by day" and "No working days", and it does not reflect date changes', async ({ accounts, operator, team, schedules }) => {
  const { salon, owner } = await teamSalon(operator, accounts.identity);
  const row = TEXT.row;
  const stretch = (starts: string, finishes: string, days: DayName[], changes: Partial<Record<DayName, Partial<WorkDay>>> = {}) => DAYS.map(day => (days.includes(day) ? { ...workingDay(day, starts, finishes), ...changes[day] } : offDay(day)));
  const monSat: DayName[] = ['monday', 'tuesday', 'wednesday', 'thursday', 'friday', 'saturday'];
  const oneBreak = Object.fromEntries(monSat.map(d => [d, { breaks: [brk('13:00', '14:00')] }]));
  const twoBreaks = Object.fromEntries(monSat.map(d => [d, { breaks: [brk('11:00', '11:15'), brk('13:00', '14:00')] }]));
  const rows: [string, WorkDay[], string][] = [
    ['one stretch with a break', stretch('09:00', '18:00', monSat, oneBreak), row.stretch('monday', 'saturday', '09:00', '18:00') + row.oneBreak('13:00')],
    ['one stretch with two breaks', stretch('09:00', '18:00', monSat, twoBreaks), row.stretch('monday', 'saturday', '09:00', '18:00') + row.breaks(2)],
    ['one stretch with none', stretch('10:00', '17:30', ['tuesday', 'wednesday', 'thursday']), row.stretch('tuesday', 'thursday', '10:00', '17:30')],
    ['a single day', stretch('09:00', '18:00', ['monday']), row.single('monday', '09:00', '18:00')],
    ['days not in one stretch', stretch('09:00', '18:00', ['monday', 'wednesday', 'friday']), row.varies],
    ['Sunday and Monday, not one stretch as the week starts on Monday', stretch('09:00', '18:00', ['monday', 'sunday']), row.varies],
    ['the same days, different times', stretch('09:00', '18:00', monSat, { friday: { finishes: '17:00' } }), row.varies],
    ['the same times, different breaks', stretch('09:00', '18:00', monSat, { friday: { breaks: [brk('13:00', '14:00')] } }), row.varies],
    ['no working days', noWorkingDays(), row.none],
  ];
  const people: { label: string; member: TeamMember; expected: string }[] = [];
  for (const [label, weekly, expected] of rows) {
    const member = await team.add(salon, owner.tokens, memberInput({ name: `Person ${people.length + 1}` }));
    // This salon has saved no hours, so a person already has no working days, and saving that changes nothing (AC-6).
    if (expected !== row.none) await schedules.save(salon, owner.tokens, member.id, weekly);
    people.push({ label, member, expected });
  }
  const { page } = await open(accounts, owner);
  for (const { label, member, expected } of people) await expect(id(card(page, member.id), W.cardHours), label).toHaveText(expected);
  await test.step('a date change does not change the row', async () => {
    const { member, expected } = people[0];
    const today = await salonToday(accounts);
    await schedules.add(salon, owner.tokens, member.id, offChange(today));
    await schedules.add(salon, owner.tokens, member.id, changeInput(plusDays(today, 1), { starts: '11:00', finishes: '12:00' }));
    await page.reload();
    await ready(page);
    await expect(id(card(page, member.id), W.cardHours)).toHaveText(expected);
  });
  await test.step('the row is above Online booking, and Schedule beside Edit profile', async () => {
    const one = card(page, people[0].member.id);
    expect((await id(one, W.cardHours).boundingBox())!.y, 'Hours above Online booking').toBeLessThan((await id(one, 'team-card-online').boundingBox())!.y);
    const [schedule, edit] = [await id(one, W.cardSchedule).boundingBox(), await id(one, 'team-card-edit').boundingBox()];
    expect(Math.abs(schedule!.y - edit!.y), 'Schedule on Edit profile\'s line').toBeLessThan(4);
  });
});

// ---------------------------------------------------------------- AC-1, AC-2 the week

test('TC-F06-S02-031 AC-1, AC-2: the Profile sheet says new people start on the salon\'s hours, and a person added there starts on them; Schedule opens over the Team page headed with the person, shows the banner and seven days, and Save hours saves the whole week and says so', async ({ accounts, operator, team, schedules, hours }) => {
  const { salon, owner } = await teamSalon(operator, accounts.identity);
  const opening = week('09:00', '18:00', ['sunday']);
  await hours.save(salon, owner.tokens, opening);
  const { page } = await open(accounts, owner);
  await test.step('the Profile sheet\'s closing line, and a person added through it', async () => {
    await id(page, 'team-empty-add').click();
    await expect(id(page, 'team-sheet').getByText(TEXT.profileLine)).toBeVisible();
    await id(page, 'team-name').fill('Sanduni Perera');
    await id(page, 'team-sheet-save').click();
    await expect(id(page, 'team-sheet')).toHaveCount(0);
  });
  const person = (await team.list(salon, owner.tokens)).find(m => m.name === 'Sanduni Perera')!;
  expectWeek(person.weekly, copyOf(opening), 'starts on the salon\'s hours');
  const sheet = await openSchedule(page, person.id);
  await test.step('the sheet', async () => {
    await expect(id(sheet, W.personName), 'headed with the name').toHaveText(person.name);
    await expect(id(sheet, W.avatar), 'and the avatar').toBeVisible();
    await expect(weekTab(sheet)).toHaveAttribute('aria-selected', 'true');
    await expect(sheet.getByText(TEXT.weeklyBanner)).toBeVisible();
    for (const day of DAYS) await expect(id(sheet, W.toggle(day)), `${day}`).toHaveAttribute('aria-pressed', String(day !== 'sunday'));
    await expect(id(sheet, W.off('sunday')), 'Sunday reads not working').toBeVisible();
    await expect(timeField(sheet, N.starts(LONG.monday))).toContainText(shownTime('09:00'));
    await expect(button(sheet, SAVE), 'nothing to save while nothing has changed').toBeDisabled();
  });
  await test.step('a change and a save', async () => {
    await hoursOn(page, sheet, 'monday', '08:30', '16:45');
    await id(sheet, W.toggle('wednesday')).click();
    await expect(id(sheet, W.off('wednesday'))).toBeVisible();
    await id(sheet, W.toggle('sunday')).click();
    await hoursOn(page, sheet, 'sunday', '10:00', '14:00');
    await button(sheet, SAVE).click();
    await expect(savedToast(page)).toBeVisible();
  });
  const expected = weekOf('09:00', '18:00', ['wednesday'], { monday: { starts: '08:30', finishes: '16:45' }, sunday: { starts: '10:00', finishes: '14:00' } });
  expectWeek((await schedules.read(salon, owner.tokens, person.id)).weekly, expected, 'stored');
});

test('TC-F06-S02-032 AC-2: a finish at or before the start says "Mon: the finish must be after the start." and disables Save hours while it lasts; correcting it clears both, and nothing was saved', async ({ accounts, operator, team, schedules }) => {
  const { salon, owner, person } = await arrange(operator, accounts, team, schedules);
  const { page } = await open(accounts, owner);
  const sheet = await openSchedule(page, person.id);
  const words = TEXT.finishAfterStart('monday');
  for (const [label, finishes] of [['equal', '09:00'], ['before', '08:00']] as const) {
    await test.step(label, async () => {
      await setTime(page, sheet, N.finishes(LONG.monday), finishes);
      await expect(sheet.getByText(words)).toBeVisible();
      await expect(button(sheet, SAVE)).toBeDisabled();
    });
  }
  await test.step('corrected, to something new', async () => {
    await setTime(page, sheet, N.finishes(LONG.monday), '17:30');
    await expect(sheet.getByText(words)).toHaveCount(0);
    await expect(button(sheet, SAVE)).toBeEnabled();
  });
  await test.step('back to what is saved, so there is nothing to save', async () => {
    await setTime(page, sheet, N.finishes(LONG.monday), '18:00');
    await expect(button(sheet, SAVE)).toBeDisabled();
  });
  expectWeek((await schedules.read(salon, owner.tokens, person.id)).weekly, weekOf(), 'nothing saved');
});

test('TC-F06-S02-033 AC-3: a working day takes breaks up to 5 and no sixth, each removed by its own control; each broken rule says its words on that weekday and disables Save hours, and a day set to not working saves no break', async ({ accounts, operator, team, schedules }) => {
  const { salon, owner, person } = await arrange(operator, accounts, team, schedules);
  const { page } = await open(accounts, owner);
  const sheet = await openSchedule(page, person.id);
  await test.step('up to five, and no sixth', async () => {
    const five = [['09:00', '09:15'], ['10:00', '10:15'], ['11:00', '11:15'], ['12:00', '12:15'], ['17:45', '18:00']];
    for (const [n, [starts, ends]] of five.entries()) await addBreak(page, sheet, 'monday', n + 1, starts, ends);
    await expect(breakRows(sheet, 'monday'), 'five breaks').toHaveCount(SCHEDULE_LIMITS.breaks);
    await expect(button(sheet, SAVE), 'touching the start and the finish is allowed').toBeEnabled();
    await expect(button(id(sheet, W.day('monday')), ADD_BREAK), '+ Add break is disabled at five').toBeDisabled();
    await expect(id(sheet, W.breaksFull('monday')), 'and says why').toHaveText(TEXT.maxBreaks);
  });
  await test.step('removed by its own control, the others staying', async () => {
    await button(sheet, N.breakRemove(LONG.monday, 5)).click();
    await expect(breakRows(sheet, 'monday')).toHaveCount(4);
    for (const n of [4, 3, 2]) await button(sheet, N.breakRemove(LONG.monday, n)).click();
    await expect(breakRows(sheet, 'monday')).toHaveCount(1);
    await expect(timeField(sheet, N.breakStarts(LONG.monday, 1)), 'the first stays').toContainText(shownTime('09:00'));
  });
  const rules: [string, string, string, string][] = [
    ['a break that ends before it starts', TEXT.breakOrder('monday'), '13:00', '12:00'],
    ['a break outside the hours', TEXT.breakInside('monday'), '08:00', '10:00'],
    ['one break covering the day', TEXT.breakFills('monday'), '09:00', '18:00'],
  ];
  for (const [label, words, starts, ends] of rules) {
    await test.step(label, async () => {
      await breakOn(page, sheet, 'monday', 1, starts, ends);
      await expect(sheet.getByText(words), label).toBeVisible();
      await expect(button(sheet, SAVE), label).toBeDisabled();
    });
  }
  await test.step('two breaks that overlap, then that touch and fill the day', async () => {
    await breakOn(page, sheet, 'monday', 1, '12:00', '13:00');
    await addBreak(page, sheet, 'monday', 2, '12:45', '14:00');
    await expect(sheet.getByText(TEXT.breakOverlap('monday'))).toBeVisible();
    await expect(button(sheet, SAVE)).toBeDisabled();
    await breakOn(page, sheet, 'monday', 1, '09:00', '13:00');
    await breakOn(page, sheet, 'monday', 2, '13:00', '18:00');
    await expect(sheet.getByText(TEXT.breakFills('monday'))).toBeVisible();
    await expect(button(sheet, SAVE)).toBeDisabled();
  });
  await test.step('a good break saves, and a day set to not working then keeps none', async () => {
    await button(sheet, N.breakRemove(LONG.monday, 2)).click();
    await breakOn(page, sheet, 'monday', 1, '13:00', '14:00');
    await addBreak(page, sheet, 'tuesday', 1, '13:00', '14:00');
    await button(sheet, SAVE).click();
    await expect(savedToast(page)).toBeVisible();
    expectWeek((await schedules.read(salon, owner.tokens, person.id)).weekly, weekOf('09:00', '18:00', ['sunday'], { monday: { breaks: [brk('13:00', '14:00')] }, tuesday: { breaks: [brk('13:00', '14:00')] } }), 'the breaks saved');
    const again = await sheetStillOrAgain(page, person.id);
    await id(again, W.toggle('monday')).click();
    await button(again, SAVE).click();
    await expect.poll(async () => (await schedules.read(salon, owner.tokens, person.id)).weekly.find(d => d.day === 'monday'), { message: 'no break kept' }).toEqual(offDay('monday'));
  });
});

// ---------------------------------------------------------------- AC-4 the shortcuts

test('TC-F06-S02-034 AC-4: Copy Monday to working days copies Monday\'s times and breaks to every other working day and leaves days off alone, is disabled while Monday is not working, and saves nothing until Save hours', async ({ accounts, operator, team, schedules }) => {
  const start = weekOf('10:00', '16:00', ['wednesday', 'sunday'], { monday: { starts: '08:00', finishes: '17:00', breaks: [brk('12:00', '12:45')] }, friday: { breaks: [brk('14:00', '15:00')] } });
  const { salon, owner, person } = await arrange(operator, accounts, team, schedules, start);
  const { page } = await open(accounts, owner);
  const sheet = await openSchedule(page, person.id);
  await button(sheet, COPY_MONDAY).click();
  await expect(timeField(sheet, N.starts(LONG.tuesday)), 'Tuesday takes Monday\'s start').toContainText(shownTime('08:00'));
  await expect(timeField(sheet, N.breakStarts(LONG.friday, 1)), 'Friday\'s break becomes Monday\'s').toContainText(shownTime('12:00'));
  await expect(id(sheet, W.off('wednesday')), 'a day off stays off').toBeVisible();
  expectWeek((await schedules.read(salon, owner.tokens, person.id)).weekly, start, 'nothing saved yet');
  await button(sheet, SAVE).click();
  await expect(savedToast(page)).toBeVisible();
  const mondays = { starts: '08:00', finishes: '17:00', breaks: [brk('12:00', '12:45')] };
  expectWeek((await schedules.read(salon, owner.tokens, person.id)).weekly, weekOf('10:00', '16:00', ['wednesday', 'sunday'], { monday: mondays, tuesday: mondays, thursday: mondays, friday: mondays, saturday: mondays }), 'saved on Save hours');
  await test.step('disabled while Monday is not working', async () => {
    const reopened = await sheetStillOrAgain(page, person.id);
    await expect(button(reopened, COPY_MONDAY)).toBeEnabled();
    await id(reopened, W.toggle('monday')).click();
    await expect(button(reopened, COPY_MONDAY)).toBeDisabled();
  });
});

test('TC-F06-S02-035 AC-4: Use the salon\'s hours sets every weekday from the salon\'s hours, keeps the breaks of days that stay working, and saves nothing until Save hours; with no salon hours it is disabled and says why', async ({ accounts, operator, team, schedules, hours }) => {
  const start = weekOf('10:00', '16:00', ['sunday'], { tuesday: { breaks: [brk('13:00', '14:00')] }, wednesday: { breaks: [brk('13:00', '14:00')] } });
  const { salon, owner, person } = await arrange(operator, accounts, team, schedules, start);
  await test.step('with no salon hours', async () => {
    const { page, context } = await open(accounts, owner);
    const sheet = await openSchedule(page, person.id);
    await expect(button(sheet, USE_SALON)).toBeDisabled();
    await expect(id(sheet, W.noHoursReason), 'with its reason').toBeVisible();
    await expect(id(sheet, W.noHoursReason)).not.toHaveText('');
    await context.close();
  });
  const opening = week('09:00', '18:00', ['wednesday'], { saturday: ['09:00', '13:00'], sunday: ['11:00', '15:00'] });
  await hours.save(salon, owner.tokens, opening);
  const { page } = await open(accounts, owner);
  const sheet = await openSchedule(page, person.id);
  await button(sheet, USE_SALON).click();
  await expect(timeField(sheet, N.starts(LONG.monday)), 'Monday takes the salon\'s').toContainText(shownTime('09:00'));
  await expect(id(sheet, W.off('wednesday')), 'a day the salon is closed').toBeVisible();
  await expect(timeField(sheet, N.starts(LONG.sunday)), 'a day off the salon opens').toContainText(shownTime('11:00'));
  await expect(timeField(sheet, N.breakStarts(LONG.tuesday, 1)), 'Tuesday stays working and keeps its break').toContainText(shownTime('13:00'));
  expectWeek((await schedules.read(salon, owner.tokens, person.id)).weekly, start, 'nothing saved yet');
  await button(sheet, SAVE).click();
  await expect(savedToast(page)).toBeVisible();
  const expected = copyOf(opening).map(d => (d.day === 'tuesday' ? { ...d, breaks: [brk('13:00', '14:00')] } : d));
  expectWeek((await schedules.read(salon, owner.tokens, person.id)).weekly, expected, 'saved on Save hours');
});

// ---------------------------------------------------------------- AC-5 the warning

test('TC-F06-S02-036 AC-5: hours outside the salon\'s warn without refusing, Monday first, naming each day and the salon\'s hours; inside them there is no warning; a salon with no saved hours has its one warning; saving keeps the hours as entered', async ({ accounts, operator, team, schedules, hours }) => {
  const { salon, owner, person } = await arrange(operator, accounts, team, schedules);
  await test.step('no saved hours: the one warning, and it does not refuse', async () => {
    const { page, context } = await open(accounts, owner);
    const sheet = await openSchedule(page, person.id);
    await expect(id(sheet, W.hoursUnset)).toHaveText(TEXT.hoursUnset);
    await expect(id(sheet, W.outside), 'not the other warning').toHaveCount(0);
    await setTime(page, sheet, N.finishes(LONG.monday), '17:00');
    await expect(button(sheet, SAVE)).toBeEnabled();
    await context.close();
  });
  await hours.save(salon, owner.tokens, week('09:00', '18:00', ['sunday']));
  const { page } = await open(accounts, owner);
  const sheet = await openSchedule(page, person.id);
  await expect(id(sheet, W.outside), 'inside the salon\'s hours, no warning').toHaveCount(0);
  await expect(id(sheet, W.hoursUnset), 'the salon has hours now').toHaveCount(0);
  await setTime(page, sheet, N.finishes(LONG.monday), '19:00');
  await id(sheet, W.toggle('sunday')).click();
  await hoursOn(page, sheet, 'sunday', '10:00', '14:00');
  const warning = id(sheet, W.outside);
  const inOrder = [TEXT.outsideHead, TEXT.outsideOpen('monday', '09:00', '18:00'), TEXT.outsideClosed('sunday'), TEXT.outsideTail];
  await expect(warning, 'the whole warning, in the spec\'s order, Monday first').toHaveText(new RegExp(`^${inOrder.map(words => words.replace(/[.*+?^${}()|[\]\\]/g, '\\$&')).join('\\s*')}$`));
  await expect(warning, 'a day inside the hours is not named').not.toContainText('Tue:');
  await expect(button(sheet, SAVE), 'it does not refuse').toBeEnabled();
  await button(sheet, SAVE).click();
  await expect(savedToast(page)).toBeVisible();
  const stored = weekOf('09:00', '18:00', [], { monday: { finishes: '19:00' }, sunday: { starts: '10:00', finishes: '14:00' } });
  expectWeek((await schedules.read(salon, owner.tokens, person.id)).weekly, stored, 'as entered');
});

// ---------------------------------------------------------------- AC-6 conflict and the guard

test('TC-F06-S02-037 AC-6, AC-16: a save someone else overtook shows the conflict, keeps their week and offers a way to reload that shows it', async ({ accounts, operator, team, schedules }) => {
  const { salon, owner, person } = await arrange(operator, accounts, team, schedules);
  const { page } = await open(accounts, owner);
  const sheet = await openSchedule(page, person.id);
  const theirs = weekOf('11:00', '15:00');
  await schedules.save(salon, owner.tokens, person.id, theirs);
  await setTime(page, sheet, N.finishes(LONG.monday), '17:00');
  await button(sheet, SAVE).click();
  await expect(id(sheet, W.conflict), 'told someone else changed it').toBeVisible();
  expectWeek((await schedules.read(salon, owner.tokens, person.id)).weekly, theirs, 'their week stands');
  await id(sheet, W.conflictReload).click();
  await expect(timeField(sheet, N.starts(LONG.monday)), 'Reload shows what is saved').toContainText(shownTime('11:00'));
  await expect(id(sheet, W.conflict)).toHaveCount(0);
});

test('TC-F06-S02-038 AC-6: moving between the tabs keeps unsaved edits with no prompt; leaving the sheet with them by Escape or Close asks first; Stay keeps the edits, Leave discards them, closing the page asks the browser, and an untouched sheet closes without asking', async ({ accounts, operator, team, schedules }) => {
  const { salon, owner, person } = await arrange(operator, accounts, team, schedules);
  const { page } = await open(accounts, owner);
  const sheet = await openSchedule(page, person.id);
  await setTime(page, sheet, N.finishes(LONG.monday), '17:00');
  await test.step('to Date changes and back', async () => {
    await dateTab(sheet).click();
    await expect(sheet.getByText(TEXT.datesBanner)).toBeVisible();
    await expect(page.getByTestId(W.leaveDialog), 'no prompt').toHaveCount(0);
    await weekTab(sheet).click();
    await expect(timeField(sheet, N.finishes(LONG.monday)), 'the edit is kept').toContainText(shownTime('17:00'));
  });
  await test.step('Escape, then Stay', async () => {
    await page.keyboard.press('Escape');
    await expect(id(page, W.leaveDialog)).toBeVisible();
    await id(page, W.leaveStay).click();
    await expect(timeField(sheet, N.finishes(LONG.monday)), 'the edit is kept').toContainText(shownTime('17:00'));
  });
  await test.step('closing the page asks the browser', async () => {
    const dialog = page.waitForEvent('dialog');
    await page.close({ runBeforeUnload: true });
    const shown = await dialog;
    expect(shown.type()).toBe('beforeunload');
    await shown.dismiss();
    await expect(timeField(sheet, N.finishes(LONG.monday)), 'dismissing keeps the page and the edit').toContainText(shownTime('17:00'));
  });
  await test.step('Close, then Leave', async () => {
    await id(sheet, W.close).click();
    await expect(id(page, W.leaveDialog)).toBeVisible();
    await id(page, W.leaveConfirm).click();
    await expect(sheetOf(page)).toHaveCount(0);
    expectWeek((await schedules.read(salon, owner.tokens, person.id)).weekly, weekOf(), 'nothing saved');
  });
  await test.step('an untouched sheet closes without asking', async () => {
    const untouched = await openSchedule(page, person.id);
    await id(untouched, W.close).click();
    await expect(sheetOf(page)).toHaveCount(0);
    await expect(page.getByTestId(W.leaveDialog)).toHaveCount(0);
  });
});

// ---------------------------------------------------------------- AC-7, AC-8 date changes

test('TC-F06-S02-039 AC-7, AC-8: on Date changes an Owner adds different hours with a break and a note and a day off, sees them in date order with "No date changes." before and after, and Remove says "Back to the weekly hours on that date."; the week stays as it was', async ({ accounts, operator, team, schedules }) => {
  const { salon, owner, person } = await arrange(operator, accounts, team, schedules);
  const { page } = await open(accounts, owner);
  const sheet = await openSchedule(page, person.id);
  await dateTab(sheet).click();
  await expect(sheet.getByText(TEXT.datesBanner)).toBeVisible();
  await expect(sheet.getByText(TEXT.noChanges), 'nothing yet').toBeVisible();
  const today = await salonToday(accounts);
  const later = plusDays(today, 2);
  await test.step('a day off, later', async () => {
    await button(sheet, ADD_CHANGE).click();
    await pickDate(sheet, later);
    await segment(sheet, 'Not working').click();
    await button(sheet, N.saveChange).click();
    await expect(sheet.getByText(TEXT.noChanges)).toHaveCount(0);
  });
  await test.step('different hours today, with a break and a note', async () => {
    await button(sheet, ADD_CHANGE).click();
    await pickDate(sheet, today);
    await segment(sheet, 'Different hours').click();
    await setTime(page, sheet, N.changeStarts, '10:00');
    await setTime(page, sheet, N.changeFinishes, '14:00');
    await button(id(sheet, W.changeForm), ADD_BREAK).click();
    await setTime(page, sheet, N.changeBreakStarts(1), '12:00');
    await setTime(page, sheet, N.changeBreakEnds(1), '12:30');
    await noteField(sheet).fill('Half day for a family event');
    await button(sheet, N.saveChange).click();
  });
  await expect.poll(async () => (await schedules.read(salon, owner.tokens, person.id)).dateChanges.length, { message: 'both are stored' }).toBe(2);
  const stored = await schedules.read(salon, owner.tokens, person.id);
  await test.step('listed in date order, as stored', async () => {
    expect(stored.dateChanges.map(c => c.date)).toEqual([today, later]);
    const first = stored.dateChanges[0];
    expect(first).toMatchObject({ working: true, starts: '10:00', finishes: '14:00', breaks: [brk('12:00', '12:30')], note: 'Half day for a family event' });
    expect(stored.dateChanges[1]).toMatchObject({ working: false, starts: null, finishes: null, breaks: [] });
    const rows = sheet.getByTestId(W.changeRow).filter({ visible: true });
    await expect(rows).toHaveCount(2);
    await expect(rows.first(), 'today\'s comes first').toHaveAttribute('data-date', today);
    await expect(rows.first()).toContainText(shownTime('10:00'));
    await expect(rows.first()).toContainText('Half day for a family event');
    await expect(rows.nth(1)).toContainText('Not working');
  });
  expectWeek(stored.weekly, weekOf(), 'the week stays as it was');
  await test.step('Remove', async () => {
    await button(changeRow(sheet, today), /^Remove/).click();
    await expect(page.getByText(TEXT.changeRemoved).filter({ visible: true })).toBeVisible();
    await button(changeRow(sheet, later), /^Remove/).click();
    await expect(sheet.getByText(TEXT.noChanges), 'empty again').toBeVisible();
    expect((await schedules.read(salon, owner.tokens, person.id)).dateChanges).toEqual([]);
  });
});

test('TC-F06-S02-040 AC-7: the change form says "This date already has a change. Remove it first." for a date someone already changed, and shows the words for a finish before the start and for breaks that fill the day, saving nothing', async ({ accounts, operator, team, schedules }) => {
  const { salon, owner, person } = await arrange(operator, accounts, team, schedules);
  const { page } = await open(accounts, owner);
  const sheet = await openSchedule(page, person.id);
  await dateTab(sheet).click();
  const date = plusDays(await salonToday(accounts), 3);
  await button(sheet, ADD_CHANGE).click();
  await pickDate(sheet, date);
  await test.step('a finish before the start', async () => {
    await setTime(page, sheet, N.changeStarts, '14:00');
    await setTime(page, sheet, N.changeFinishes, '10:00');
    await expect(id(sheet, W.changeProblem).filter({ hasText: TEXT.changeFinishAfterStart })).toBeVisible();
    await expect(button(sheet, N.saveChange)).toBeDisabled();
  });
  await test.step('breaks that fill the day', async () => {
    await setTime(page, sheet, N.changeStarts, '10:00');
    await setTime(page, sheet, N.changeFinishes, '14:00');
    await button(id(sheet, W.changeForm), ADD_BREAK).click();
    await setTime(page, sheet, N.changeBreakStarts(1), '10:00');
    await setTime(page, sheet, N.changeBreakEnds(1), '14:00');
    await expect(id(sheet, W.changeProblem).filter({ hasText: TEXT.changeBreakFills })).toBeVisible();
    await expect(button(sheet, N.saveChange)).toBeDisabled();
  });
  expect((await schedules.read(salon, owner.tokens, person.id)).dateChanges, 'nothing saved').toEqual([]);
  await test.step('a date someone else just changed', async () => {
    await setTime(page, sheet, N.changeBreakEnds(1), '12:30');
    await schedules.add(salon, owner.tokens, person.id, offChange(date));
    await button(sheet, N.saveChange).click();
    await expect(sheet.getByText(TEXT.secondChange)).toBeVisible();
    expect((await schedules.read(salon, owner.tokens, person.id)).dateChanges.map(c => c.working), 'theirs stands').toEqual([false]);
  });
});

// ---------------------------------------------------------------- AC-11 the bookings a change affects

test('TC-F06-S02-041 AC-11: when the answer lists affected bookings the sheet says how many and that they are kept, singular and plural, with each one\'s date, time, customer and service, after a weekly save and after adding a change', async ({ accounts, operator, team, schedules }) => {
  const { salon, owner, person } = await arrange(operator, accounts, team, schedules);
  const { page, context } = await open(accounts, owner);
  // No booking exists before F07 (deferred case 029), so the save is real and only its answer gains a list.
  const today = await salonToday(accounts);
  const bookings = [
    { date: plusDays(today, 4), time: '17:30', customerName: 'Nimali Silva', serviceName: 'Root touch-up' },
    { date: plusDays(today, 5), time: '18:15', customerName: 'Tharushi Fernando', serviceName: 'Balayage' },
  ];
  let affected = bookings;
  const addList = async (route: import('@playwright/test').Route) => {
    const answer = await route.fetch();
    await route.fulfill({ response: answer, json: { ...(await answer.json() as object), affected } });
  };
  await context.route(PENDING_F06_S02.bff.saveWeek, addList);
  await context.route(PENDING_F06_S02.bff.addChange, addList);
  const expectList = async (sheet: Locator, label: string) => {
    const rows = id(sheet, W.affectedRow);
    await expect(rows, `${label}: one row each`).toHaveCount(affected.length);
    for (const [n, booking] of affected.entries()) {
      for (const words of [shownTime(booking.time), booking.customerName, booking.serviceName]) await expect(rows.nth(n), `${label}: ${words}`).toContainText(words);
    }
  };
  const sheet = await openSchedule(page, person.id);
  const finishes = ['17:00', '16:30'];
  for (const [n, count] of [2, 1].entries()) {
    await test.step(`a weekly save with ${count}`, async () => {
      affected = bookings.slice(0, count);
      await setTime(page, sheet, N.finishes(LONG.monday), finishes[n]);
      await button(sheet, SAVE).click();
      await expect(id(sheet, W.affected), 'the sheet stays open to say so').toContainText(TEXT.affected(count));
      await expectList(sheet, `a weekly save with ${count}`);
    });
  }
  await dateTab(sheet).click();
  for (const count of [2, 1]) {
    await test.step(`adding a date change with ${count}`, async () => {
      affected = bookings.slice(0, count);
      await button(sheet, ADD_CHANGE).click();
      await pickDate(sheet, plusDays(today, 4 + count));
      await segment(sheet, 'Not working').click();
      await button(sheet, N.saveChange).click();
      await expect(id(sheet, W.affected)).toContainText(TEXT.affectedOnDate(count));
      await expectList(sheet, `adding a change with ${count}`);
    });
  }
  const stored = await schedules.read(salon, owner.tokens, person.id);
  expect(stored.weekly.find(d => d.day === 'monday')?.finishes, 'the saves were real').toBe('16:30');
  expect(stored.dateChanges.length, 'and so were the changes').toBe(2);
});

// ---------------------------------------------------------------- AC-13 who sees what

test('TC-F06-S02-042 AC-13: the Receptionist, the Professional and a support session open Schedule read-only: the week and the changes show, with no control to change them and no note anywhere in the page', async ({ accounts, operator, team, schedules, browser }) => {
  const weekly = weekOf('09:00', '18:00', ['sunday'], { monday: { breaks: [brk('13:00', '14:00')] } });
  const { salon, owner, created, person } = await arrange(operator, accounts, team, schedules, weekly);
  const note = 'A private reason for this half day';
  const date = plusDays(await salonToday(accounts), 3);
  await schedules.add(salon, owner.tokens, person.id, changeInput(date, { note }));
  const checks = async (page: Page, label: string) => {
    await ready(page);
    // What reached the browser, not only what it drew: a screen can hide what the answer carried.
    const answers: Promise<string>[] = [];
    page.on('response', r => { if (PENDING_F06_S02.bff.read(new URL(r.url()))) answers.push(r.text().catch(() => '')); });
    const sheet = await openSchedule(page, person.id);
    await expect(sheet.getByText(shownTime('13:00')).first(), `${label}: reads the break`).toBeVisible();
    await expectNoChangeControls(sheet, label);
    await expect(changeRow(sheet, date), `${label}: reads the change`).toBeVisible();
    expect(await page.content(), `${label}: no note anywhere in the page`).not.toContain(note);
    const read = (await Promise.all(answers)).join('\n');
    expect(read, `${label}: the schedule's answer was seen, or this proves nothing`).toContain(date);
    expect(read, `${label}: no note in what the browser received`).not.toContain(note);
    expect(read, `${label}: no note field at all`).not.toContain('"note"');
  };
  for (const role of ['RECEPTIONIST', 'PROFESSIONAL'] as const) {
    await test.step(role, async () => {
      const actor = await accounts.identity.staff(salon, role);
      const context = await accounts.context(salon, [actor]);
      await checks(await accounts.open(context, TEAM_ROUTE), role);
      await context.close();
    });
  }
  await test.step('a support session', async () => {
    const context = await supportBrowser(browser, operator, created, salon);
    const page = await context.newPage();
    await page.goto(TEAM_ROUTE);
    await checks(page, 'support');
    await expect(id(sheetOf(page), W.readOnly), 'a support session is told why it cannot change anything').toBeVisible();
    await context.close();
  });
  await test.step('the Owner does see the note', async () => {
    const { page } = await open(accounts, owner);
    const sheet = await openSchedule(page, person.id);
    await dateTab(sheet).click();
    await expect(changeRow(sheet, date)).toContainText(note);
  });
});

// ---------------------------------------------------------------- AC-16 states, widths, keyboard and accessibility

test('TC-F06-S02-043 AC-16: the sheet shows its loading state, says an unreachable service plainly and recovers on Try again, and a change refused after a role change says so and saves nothing, on both tabs', async ({ accounts, operator, team, schedules }) => {
  const { salon, owner, person } = await arrange(operator, accounts, team, schedules);
  const context = await accounts.context(salon, [owner]);
  const page = await accounts.open(context, TEAM_ROUTE);
  await ready(page);
  const read = PENDING_F06_S02.bff.read;
  await test.step('loading', async () => {
    let release!: () => void;
    const held = new Promise<void>(resolve => { release = resolve; });
    await context.route(read, async r => { await held; await r.continue(); });
    await card(page, person.id).getByTestId(W.cardSchedule).click();
    await expect(id(sheetOf(page), W.loading), 'the Skeleton while loading').toBeVisible();
    release();
    await expect(id(sheetOf(page), W.loading)).toHaveCount(0);
    await context.unroute(read);
    await id(sheetOf(page), W.close).click();
  });
  await test.step('unreachable, then Try again', async () => {
    await context.route(read, r => r.abort('connectionfailed'));
    await card(page, person.id).getByTestId(W.cardSchedule).click();
    await expect(id(sheetOf(page), W.unavailable)).toBeVisible();
    await context.unroute(read);
    await id(sheetOf(page), W.retry).click();
    await expect(sheetOf(page).getByRole('button', { name: SAVE })).toBeVisible();
    await id(sheetOf(page), W.close).click();
  });
  /** A Manager with the sheet open and an edit ready, made a Receptionist before they press save: one person per tab, as a refusal may turn the sheet read-only. */
  const madeReceptionist = async (ready: (sheet: Locator, on: Page) => Promise<void>) => {
    const manager = await accounts.identity.staff(salon, 'MANAGER');
    const managed = await open(accounts, manager);
    const sheet = await openSchedule(managed.page, person.id);
    await ready(sheet, managed.page);
    const record = (await accounts.identity.list(owner as SignedStaff)).find(x => x.id === manager.principal.id)!;
    const changed = await accounts.identity.call(salon, 'PATCH', `/staff/${manager.principal.id}`, { version: record.version, role: 'RECEPTIONIST' }, owner.tokens);
    expect(changed.status(), 'the Owner changed the role').toBe(200);
    return sheet;
  };
  await test.step('a refused save on Weekly hours', async () => {
    const sheet = await madeReceptionist(async (on, onPage) => { await setTime(onPage, on, N.finishes(LONG.monday), '17:00'); });
    await button(sheet, SAVE).click();
    await expect(id(sheet, W.refused), 'says the change is not allowed').toBeVisible();
  });
  await test.step('a refused save on Date changes', async () => {
    const date = plusDays(await salonToday(accounts), 3);
    const sheet = await madeReceptionist(async on => {
      await dateTab(on).click();
      await button(on, ADD_CHANGE).click();
      await pickDate(on, date);
      await segment(on, 'Not working').click();
    });
    await button(sheet, N.saveChange).click();
    await expect(id(sheet, W.refused), 'says the change is not allowed').toBeVisible();
  });
  const stored = await schedules.read(salon, owner.tokens, person.id);
  expectWeek(stored.weekly, weekOf(), 'nothing saved');
  expect(stored.dateChanges, 'nothing saved').toEqual([]);
});

test('TC-F06-S02-044 AC-16: at every width of the matrix both tabs fit with five breaks on several days, a long name, the warning and a list of changes, with no sideways scrolling and every control reachable', async ({ accounts, operator, team, schedules, hours }) => {
  test.setTimeout(300_000);
  const full = [brk('09:00', '09:15'), brk('10:00', '10:15'), brk('11:00', '11:15'), brk('12:00', '12:15'), brk('17:45', '18:00')];
  const weekly = weekOf('09:00', '18:00', [], { monday: { breaks: full }, sunday: { breaks: full } });
  const { salon, owner, person } = await arrange(operator, accounts, team, schedules, weekly, 'A name of exactly forty characters long!');
  await hours.save(salon, owner.tokens, week('09:00', '18:00', ['sunday']));
  const today = await salonToday(accounts);
  for (let n = 1; n <= 6; n++) await schedules.add(salon, owner.tokens, person.id, changeInput(plusDays(today, n), { note: 'A note of sixty characters, the most a date change can hold.' }));
  for (const { width, height } of DESIGN_WIDTHS) {
    await test.step(`${width} wide`, async () => {
      const context = await accounts.context(salon, [owner], { width });
      const page = await accounts.open(context, TEAM_ROUTE);
      await page.setViewportSize({ width, height });
      await ready(page);
      const checkFit = async (label: string) => {
        await settled(page);
        const f = await fit(page);
        expect(f.counted, `${width} ${label}: elements measured`).toBeGreaterThan(0);
        expect(f.pageScrolls, `${width} ${label}: no sideways scrolling`).toBe(false);
        expect(f.outside, `${width} ${label}: nothing outside the screen`).toEqual([]);
      };
      const sheet = await openSchedule(page, person.id);
      await expect(id(sheet, W.outside), 'the warning shows too').toBeVisible();
      await checkFit('Weekly hours');
      for (const [label, control] of [['Save hours', button(sheet, SAVE)], ['Copy Monday', button(sheet, COPY_MONDAY)], ['a break\'s control', button(sheet, N.breakRemove(LONG.sunday, 5))]] as const) {
        await control.scrollIntoViewIfNeeded();
        await expect(control, `${width}: ${label} can be reached`).toBeInViewport();
      }
      await dateTab(sheet).click();
      await expect(sheet.getByTestId(W.changeRow).filter({ visible: true })).toHaveCount(6);
      await checkFit('Date changes');
      await button(sheet, ADD_CHANGE).click();
      await checkFit('the change form');
      await button(sheet, N.saveChange).scrollIntoViewIfNeeded();
      await expect(button(sheet, N.saveChange), `${width}: its save can be reached`).toBeInViewport();
      await context.close();
    });
  }
});

test('TC-F06-S02-045 AC-16: both tabs have no serious accessibility violation for the Owner and a read-only role; a week is changed and saved by keyboard alone with a visible focus and the focus back on the card, and each time says its weekday and break', async ({ accounts, operator, team, schedules }) => {
  const weekly = weekOf('09:00', '18:00', ['sunday'], { monday: { breaks: [brk('13:00', '14:00'), brk('15:00', '15:30')] } });
  const { salon, owner, person } = await arrange(operator, accounts, team, schedules, weekly);
  await schedules.add(salon, owner.tokens, person.id, changeInput(plusDays(await salonToday(accounts), 3), { note: 'A note' }));
  const { page } = await open(accounts, owner);
  const scan = async (label: string) => {
    await settled(page);
    expect.soft(await seriousViolations(page), `${label}: no serious or critical violation`).toEqual([]);
  };
  const sheet = await openSchedule(page, person.id);
  await scan('Weekly hours');
  await test.step('names say which weekday and which break', async () => {
    for (const name of [N.starts(LONG.monday), N.finishes(LONG.monday), N.breakStarts(LONG.monday, 1), N.breakStarts(LONG.monday, 2), N.breakEnds(LONG.monday, 2)]) {
      await expect(timeField(sheet, name), name).toBeVisible();
    }
    await expect(button(sheet, N.breakRemove(LONG.monday, 2))).toBeVisible();
    await expect(button(id(sheet, W.day('monday')), ADD_BREAK), 'Add break names its day').toHaveAccessibleName(/Monday/);
    await expect(id(sheet, W.toggle('monday')), 'the weekday\'s toggle').toHaveAccessibleName(/Monday|Mon/);
  });
  await test.step('a field error is announced, and scanned', async () => {
    await setTime(page, sheet, N.finishes(LONG.tuesday), '08:00');
    await expect(sheet.getByRole('alert').filter({ hasText: TEXT.finishAfterStart('tuesday') })).toBeVisible();
    await scan('Weekly hours with a field error');
    await setTime(page, sheet, N.finishes(LONG.tuesday), '18:00');
  });
  await dateTab(sheet).click();
  await scan('Date changes');
  await button(sheet, ADD_CHANGE).click();
  await scan('the change form');
  await id(sheet, W.close).click();
  await settled(page);
  if ((await id(page, W.leaveDialog).count()) > 0) await id(page, W.leaveConfirm).click();
  await expect(sheetOf(page)).toHaveCount(0);
  await test.step('by keyboard alone', async () => {
    const opener = card(page, person.id).getByTestId(W.cardSchedule);
    let steps = 0;
    while (!(await opener.evaluate(el => el === document.activeElement)) && steps++ < 120) await page.keyboard.press('Tab');
    await expect(opener, 'Schedule reached by Tab').toBeFocused();
    const focus = await focusIndicator(opener);
    expect(focus.focusVisible && focus.outlineWidth > 0 && focus.outlineStyle !== 'none', `a visible focus: ${JSON.stringify(focus)}`).toBe(true);
    await page.keyboard.press('Enter');
    const opened = sheetOf(page);
    await expect(opened).toBeVisible();
    const finishes = timeField(opened, N.finishes(LONG.wednesday));
    steps = 0;
    while (!(await finishes.evaluate(el => el === document.activeElement)) && steps++ < 120) await page.keyboard.press('Tab');
    await expect(finishes, 'the time reached by Tab, inside the sheet').toBeFocused();
    await page.keyboard.press('Enter');
    await expect(page.getByRole('option', { name: shownTime('18:00'), exact: true }), 'the list opens on the time it holds').toBeFocused();
    await page.keyboard.press('ArrowUp');
    await expect(page.getByRole('option', { name: shownTime('17:45'), exact: true }), 'one step earlier').toBeFocused();
    await page.keyboard.press('Enter');
    await expect(page.getByRole('listbox')).toHaveCount(0);
    await expect(finishes).toContainText(shownTime('17:45'));
    const save = opened.getByRole('button', { name: SAVE });
    steps = 0;
    while (!(await save.evaluate(el => el === document.activeElement)) && steps++ < 120) await page.keyboard.press('Tab');
    await expect(save, 'Save hours reached by Tab').toBeFocused();
    await page.keyboard.press('Enter');
    await expect(savedToast(page)).toBeVisible();
    const stored = (await schedules.read(salon, owner.tokens, person.id)).weekly.find(d => d.day === 'wednesday')!;
    expect(stored.finishes, 'one step earlier, saved').toBe('17:45');
    await settled(page);
    if ((await sheetOf(page).count()) > 0) await page.keyboard.press('Escape');
    await expect(sheetOf(page)).toHaveCount(0);
    await expect(opener, 'the focus goes back to the card').toBeFocused();
  });
  await test.step('a read-only role', async () => {
    const receptionist = await accounts.identity.staff(salon, 'RECEPTIONIST');
    const other = await open(accounts, receptionist);
    const readOnly = await openSchedule(other.page, person.id);
    await settled(other.page);
    expect.soft(await seriousViolations(other.page), 'Weekly hours, Receptionist').toEqual([]);
    await dateTab(readOnly).click();
    await settled(other.page);
    expect.soft(await seriousViolations(other.page), 'Date changes, Receptionist').toEqual([]);
  });
});
