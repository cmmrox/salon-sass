/**
 * F06-S03 the Leave and Blocked time tabs in the browser, and the card's leave badge: each path, its words, the
 * bookings a period would affect, who sees which controls, what reaches a browser without `team.manage`, the
 * loading, unavailable, conflict and refused states, the guard on a started form, every width of the matrix,
 * keyboard use and accessibility.
 *
 * Expected results are the F06-S03 spec's (acceptance criteria, "UI and UX") and the prototype (Salon web, Staff, the
 * F06 block: `schLv`, `lvSave`, `schBl`, `blSave`, `conflictNote`, `nextOff`), with curly apostrophes. Rules the API
 * cases prove (every boundary, every role on every endpoint, both limits) are not proved again: each case checks what
 * the person sees, and persistence once through the API.
 *
 * Privacy is checked in what the browser receives as well as on the screen. At the F06-S02 dry run a leaked note
 * passed a check of the page, because the sheet hid it from a read-only role.
 *
 * AC-8's list can only be shown here from a real request whose answer gains a list in the browser: no booking exists
 * before F07 (deferred case 031). The test ids and names are guesses until the handoff, all in `PENDING_F06_S03`.
 */
import type { Locator, Page, Route } from '@playwright/test';

import { DESIGN_WIDTHS } from '../../shared/design-reference';
import { expect, type AccountBrowser } from '../../shared/identity-browser';
import type { SignedStaff } from '../../shared/identity';
import type { Operator } from '../../shared/operator';
import { fit, focusIndicator, seriousViolations } from '../../shared/page-checks';
import { plusDays } from '../../shared/salon-hours';
import { memberInput, teamSalon, type Team } from '../../shared/team';
import { TEAM_ROUTE, browserReceived, button, card, id, open, openSchedule, ready, salonToday, settled, sheetOf, supportBrowser, test } from '../../shared/team-browser';
import {
  ABSENCE_TEXT, Absences, PENDING_F06_S03, TYPE_WORDS, blockInput, daysWords, dayWords, leaveInput, pickDay,
} from '../../shared/team-leave';
import { PENDING_F06_S02, shownTime } from '../../shared/team-schedule';

test.describe.configure({ timeout: 180_000 });

const W = PENDING_F06_S03.web;
const N = PENDING_F06_S03.names;
const TEXT = ABSENCE_TEXT;
const S = PENDING_F06_S02.web;

const tab = (sheet: Locator, name: string) => sheet.getByRole('tab', { name, exact: true });
const leaveTab = (sheet: Locator) => tab(sheet, N.leaveTab);
const blockTab = (sheet: Locator) => tab(sheet, N.blockTab);
const toast = (page: Page, words: string) => page.getByText(words, { exact: true }).filter({ visible: true });

/** Chooses a time through F04's TimeSelect, by the trigger's and the option's test ids. */
async function setTime(page: Page, sheet: Locator, trigger: string, hhmm: string) {
  await id(sheet, trigger).click();
  await page.getByTestId(`${trigger}-option-${hhmm.replace(':', '')}`).click();
  await expect(page.getByRole('listbox')).toHaveCount(0);
}

async function fillLeave(page: Page, sheet: Locator, first: string, last: string, type?: keyof typeof TYPE_WORDS, note?: string) {
  await pickDay(page, id(sheet, W.leaveFirst), W.leaveFirstCalendar, first);
  await pickDay(page, id(sheet, W.leaveLast), W.leaveLastCalendar, last);
  if (type) {
    await id(sheet, W.leaveType).click();
    await page.getByRole('option', { name: TYPE_WORDS[type], exact: true }).click();
  }
  if (note) await id(sheet, W.leaveNote).fill(note);
}

async function fillBlock(page: Page, sheet: Locator, date: string, starts: string, finishes: string, label: string) {
  await pickDay(page, id(sheet, W.blockDate), W.blockCalendar, date);
  await setTime(page, sheet, W.blockStarts, starts);
  await setTime(page, sheet, W.blockFinishes, finishes);
  await id(sheet, W.blockLabel).fill(label);
}

/** A salon with one person, and the helpers. */
async function arrange(operator: Operator, accounts: AccountBrowser, team: Team, name = 'Sanduni Perera') {
  const made = await teamSalon(operator, accounts.identity);
  const person = await team.add(made.salon, made.owner.tokens, memberInput({ name }));
  return { ...made, person, absences: new Absences(team), today: await salonToday(accounts) };
}

// ---------------------------------------------------------------- AC-1, AC-2, AC-3 leave

test('TC-F06-S03-032 AC-1, AC-3: on Leave an Owner adds leave of many days and of one, sees them in date order with their days, type and note, "No leave planned." before and after, and Remove asks first, Cancel keeps it', async ({ accounts, operator, team }) => {
  const { salon, owner, person, absences, today } = await arrange(operator, accounts, team);
  const { page } = await open(accounts, owner);
  const sheet = await openSchedule(page, person.id);
  await leaveTab(sheet).click();
  await expect(id(sheet, W.leaveBanner)).toHaveText(TEXT.leaveBanner);
  await expect(id(sheet, W.leaveEmpty)).toHaveText(TEXT.noLeave);
  const [first, last, single] = [plusDays(today, 12), plusDays(today, 14), plusDays(today, 2)];
  await test.step('many days', async () => {
    await button(sheet, N.addLeave, true).click();
    await fillLeave(page, sheet, first, last, 'sick', 'Back on the fifteenth');
    await button(sheet, N.saveLeave, true).click();
    await expect(toast(page, TEXT.leaveAdded)).toBeVisible();
  });
  await test.step('one day alone, sooner', async () => {
    await button(sheet, N.addLeave, true).click();
    await fillLeave(page, sheet, single, single);
    await button(sheet, N.saveLeave, true).click();
    await expect(id(sheet, W.leaveRow)).toHaveCount(2);
  });
  const rows = id(sheet, W.leaveRow);
  await test.step('the list', async () => {
    await expect(rows.nth(0).getByTestId(W.leaveRowDays), 'one day alone, first').toHaveText(dayWords(single));
    await expect(rows.nth(1).getByTestId(W.leaveRowDays)).toHaveText(daysWords(first, last));
    await expect(rows.nth(1).getByTestId(W.leaveRowType)).toHaveText(TYPE_WORDS.sick);
    await expect(rows.nth(1).getByTestId(W.leaveRowNote)).toHaveText('Back on the fifteenth');
    await expect(rows.nth(1).getByTestId(W.leaveRowResolve), 'no bookings to resolve').toHaveCount(0);
  });
  const stored = (await absences.read(salon, owner.tokens, person.id)).leave;
  expect(stored.map(l => [l.firstDay, l.lastDay, l.type]), 'stored as shown').toEqual([[single, single, 'annual'], [first, last, 'sick']]);
  await test.step('Remove asks first, and Cancel keeps it', async () => {
    await button(rows.nth(0), N.remove).click();
    const dialog = id(page, W.leaveRemoveDialog);
    await expect(dialog).toContainText(TEXT.removeTitle);
    await expect(dialog).toContainText(TEXT.removeBody);
    await id(dialog, W.leaveRemoveCancel).click();
    await expect(rows).toHaveCount(2);
  });
  await test.step('Remove, twice, leaves none', async () => {
    for (const left of [1, 0]) {
      await button(rows.first(), N.remove).click();
      await id(id(page, W.leaveRemoveDialog), W.leaveRemoveConfirm).click();
      await expect(rows).toHaveCount(left);
    }
    await expect(id(sheet, W.leaveEmpty)).toHaveText(TEXT.noLeave);
    expect((await absences.read(salon, owner.tokens, person.id)).leave).toEqual([]);
  });
});

test('TC-F06-S03-033 AC-1, AC-2: the leave form says "The last day can’t be before the first." for days out of order and "This overlaps leave already planned." for days someone else just took, saving nothing of its own', async ({ accounts, operator, team }) => {
  const { salon, owner, person, absences, today } = await arrange(operator, accounts, team);
  const { page } = await open(accounts, owner);
  const sheet = await openSchedule(page, person.id);
  await leaveTab(sheet).click();
  await button(sheet, N.addLeave, true).click();
  const [a, b] = [plusDays(today, 6), plusDays(today, 8)];
  await test.step('days out of order', async () => {
    await fillLeave(page, sheet, b, a);
    await expect(id(sheet, W.leaveProblem).filter({ hasText: TEXT.lastBeforeFirst })).toBeVisible();
    await expect(button(sheet, N.saveLeave, true), 'nothing can be saved while it lasts').toBeDisabled();
  });
  expect((await absences.read(salon, owner.tokens, person.id)).leave, 'nothing saved').toEqual([]);
  await test.step('days someone else just took', async () => {
    await fillLeave(page, sheet, a, b);
    await absences.addLeave(salon, owner.tokens, person.id, leaveInput(b, { type: 'other' }));
    await button(sheet, N.saveLeave, true).click();
    await expect(id(sheet, W.leaveProblem).filter({ hasText: TEXT.leaveOverlap })).toBeVisible();
    expect((await absences.read(salon, owner.tokens, person.id)).leave.map(l => l.type), 'theirs stands').toEqual(['other']);
  });
});

// ---------------------------------------------------------------- AC-4, AC-5, AC-6 blocks

test('TC-F06-S03-034 AC-4, AC-6: a Receptionist blocks time with a trimmed label, sees it in date and time order with "No blocked time." before and after, and Remove says "Blocked time removed."; they have no control over leave', async ({ accounts, operator, team }) => {
  const { salon, owner, person, absences, today } = await arrange(operator, accounts, team);
  const receptionist = await accounts.identity.staff(salon, 'RECEPTIONIST');
  const { page } = await open(accounts, receptionist);
  const sheet = await openSchedule(page, person.id);
  await test.step('no control over leave', async () => {
    await leaveTab(sheet).click();
    await expect(sheet.getByRole('button', { name: N.addLeave, exact: true })).toHaveCount(0);
  });
  await blockTab(sheet).click();
  await expect(id(sheet, W.blockBanner)).toHaveText(TEXT.blockBanner);
  await expect(id(sheet, W.blockEmpty)).toHaveText(TEXT.noBlocks);
  const [later, sooner] = [plusDays(today, 3), plusDays(today, 2)];
  await button(sheet, N.addBlock, true).click();
  await fillBlock(page, sheet, later, '10:00', '11:30', '  Training  ');
  await button(sheet, N.saveBlock, true).click();
  await expect(toast(page, TEXT.blocked)).toBeVisible();
  await button(sheet, N.addBlock, true).click();
  await fillBlock(page, sheet, sooner, '15:00', '15:45', 'Errand');
  await button(sheet, N.saveBlock, true).click();
  const rows = id(sheet, W.blockRow);
  await expect(rows).toHaveCount(2);
  await test.step('the list', async () => {
    for (const words of [dayWords(sooner), TEXT.blockTimes('15:00', '15:45'), 'Errand']) await expect(rows.nth(0), words).toContainText(words);
    for (const words of [dayWords(later), TEXT.blockTimes('10:00', '11:30'), 'Training']) await expect(rows.nth(1), words).toContainText(words);
  });
  expect((await absences.read(salon, owner.tokens, person.id)).blockedTime.map(b => [b.date, b.starts, b.finishes, b.label]), 'stored, trimmed').toEqual([[sooner, '15:00', '15:45', 'Errand'], [later, '10:00', '11:30', 'Training']]);
  await test.step('Remove', async () => {
    await button(rows.first(), N.remove).click();
    await expect(toast(page, TEXT.blockRemoved)).toBeVisible();
    await button(rows.first(), N.remove).click();
    await expect(id(sheet, W.blockEmpty)).toHaveText(TEXT.noBlocks);
    expect((await absences.read(salon, owner.tokens, person.id)).blockedTime).toEqual([]);
  });
});

test('TC-F06-S03-035 AC-4, AC-5: the block form says "The end must be after the start." and "Add a label, like Training." while they last, and "This overlaps time already blocked." for time someone else just blocked, saving nothing of its own', async ({ accounts, operator, team }) => {
  const { salon, owner, person, absences, today } = await arrange(operator, accounts, team);
  const { page } = await open(accounts, owner);
  const sheet = await openSchedule(page, person.id);
  await blockTab(sheet).click();
  await button(sheet, N.addBlock, true).click();
  const date = plusDays(today, 4);
  await fillBlock(page, sheet, date, '11:00', '10:00', 'Training');
  await test.step('an end before the start', async () => {
    await expect(id(sheet, W.blockProblem).filter({ hasText: TEXT.endAfterStart })).toBeVisible();
    await expect(button(sheet, N.saveBlock, true)).toBeDisabled();
    await setTime(page, sheet, W.blockFinishes, '12:00');
    await expect(id(sheet, W.blockProblem).filter({ hasText: TEXT.endAfterStart })).toHaveCount(0);
  });
  await test.step('a label of one letter', async () => {
    await id(sheet, W.blockLabel).fill(' T ');
    await expect(id(sheet, W.blockProblem).filter({ hasText: TEXT.addLabel })).toBeVisible();
    await expect(button(sheet, N.saveBlock, true)).toBeDisabled();
    await id(sheet, W.blockLabel).fill('Training');
  });
  expect((await absences.read(salon, owner.tokens, person.id)).blockedTime, 'nothing saved').toEqual([]);
  await test.step('time someone else just blocked', async () => {
    await absences.addBlock(salon, owner.tokens, person.id, blockInput(date, { starts: '11:30', finishes: '12:30', label: 'Theirs' }));
    await button(sheet, N.saveBlock, true).click();
    await expect(id(sheet, W.blockProblem).filter({ hasText: TEXT.blockOverlap })).toBeVisible();
    expect((await absences.read(salon, owner.tokens, person.id)).blockedTime.map(b => b.label), 'theirs stands').toEqual(['Theirs']);
  });
});

// ---------------------------------------------------------------- AC-3, AC-8 bookings

test('TC-F06-S03-036 AC-3, AC-8: both forms show the bookings a period would affect before saving, singular and plural; saving is allowed and says they were kept and flagged; a leave\'s row says how many are left to resolve', async ({ accounts, operator, team }) => {
  const { salon, owner, person, absences, today } = await arrange(operator, accounts, team);
  const { page, context } = await open(accounts, owner);
  // No booking exists before F07 (deferred case 031), so each request is real and only its answer gains a list.
  const bookings = [
    { date: plusDays(today, 2), time: '14:00', customerName: 'Nimali Silva', serviceName: 'Root touch-up' },
    { date: plusDays(today, 3), time: '10:30', customerName: 'Tharushi Fernando', serviceName: 'Balayage' },
  ];
  let affected = bookings;
  const withList = async (route: Route) => {
    const answer = await route.fetch();
    await route.fulfill({ response: answer, json: { ...(await answer.json() as object), affected } });
  };
  for (const match of [PENDING_F06_S03.bff.leaveAffected, PENDING_F06_S03.bff.blockAffected, PENDING_F06_S03.bff.leaveAdd, PENDING_F06_S03.bff.blockAdd]) await context.route(match, withList);
  const expectPreview = async (sheet: Locator, label: string) => {
    await expect(id(sheet, S.affected), `${label}: the sentence`).toContainText(TEXT.affected(affected.length));
    const rows = id(sheet, S.affectedRow);
    await expect(rows, `${label}: one row each`).toHaveCount(affected.length);
    for (const [n, booking] of affected.entries()) {
      for (const words of [dayWords(booking.date), shownTime(booking.time), booking.customerName, booking.serviceName]) await expect(rows.nth(n), `${label}: ${words}`).toContainText(words);
    }
  };
  const sheet = await openSchedule(page, person.id);
  await test.step('leave, two bookings', async () => {
    await leaveTab(sheet).click();
    await button(sheet, N.addLeave, true).click();
    await fillLeave(page, sheet, plusDays(today, 2), plusDays(today, 3));
    await expectPreview(sheet, 'leave');
    await expect(button(sheet, N.saveLeave, true), 'saving is allowed').toBeEnabled();
    await button(sheet, N.saveLeave, true).click();
    await expect(toast(page, TEXT.leaveAddedFlagged)).toBeVisible();
  });
  await test.step('a block, one booking, then two', async () => {
    await blockTab(sheet).click();
    for (const [n, count] of [1, 2].entries()) {
      affected = bookings.slice(0, count);
      await button(sheet, N.addBlock, true).click();
      await fillBlock(page, sheet, plusDays(today, 10 + n), '10:00', '11:00', 'Training');
      await expectPreview(sheet, `a block with ${count}`);
      await button(sheet, N.saveBlock, true).click();
      await expect(toast(page, TEXT.blockedFlagged(count))).toBeVisible();
    }
  });
  const stored = await absences.read(salon, owner.tokens, person.id);
  expect([stored.leave.length, stored.blockedTime.length], 'the saves were real').toEqual([1, 2]);
  await test.step('a leave\'s row with bookings to resolve, singular and plural', async () => {
    let count = 2;
    const fresh = await open(accounts, owner);
    await fresh.context.route(PENDING_F06_S02.bff.read, async route => {
      const answer = await route.fetch();
      const body = await answer.json() as { leave: { bookingsToResolve: number }[] };
      await route.fulfill({ response: answer, json: { ...body, leave: body.leave.map(l => ({ ...l, bookingsToResolve: count })) } });
    });
    for (const n of [2, 1]) {
      count = n;
      await fresh.page.reload();
      await ready(fresh.page);
      const again = await openSchedule(fresh.page, person.id);
      await leaveTab(again).click();
      await expect(id(again, W.leaveRow).first().getByTestId(W.leaveRowResolve)).toHaveText(TEXT.toResolve(n));
    }
  });
});

// ---------------------------------------------------------------- AC-9, AC-10 the badge and who sees what

test('TC-F06-S03-037 AC-9: each card has a badge for the current or next leave: with team.manage its type and days, otherwise "On leave" and the days; with none, no badge', async ({ accounts, operator, team }) => {
  const { salon, owner, person, absences, today } = await arrange(operator, accounts, team);
  const none = await team.add(salon, owner.tokens, memberInput({ name: 'No Leave' }));
  const [first, last] = [plusDays(today, 2), plusDays(today, 4)];
  await absences.addLeave(salon, owner.tokens, person.id, leaveInput(first, { lastDay: last, type: 'sick', note: 'Private' }));
  await absences.addLeave(salon, owner.tokens, person.id, leaveInput(plusDays(today, 20), { type: 'annual' }));
  const single = await team.add(salon, owner.tokens, memberInput({ name: 'One Day' }));
  await absences.addLeave(salon, owner.tokens, single.id, leaveInput(first, { type: 'annual' }));
  const manager = await accounts.identity.staff(salon, 'MANAGER');
  for (const [who, actor] of [['the Owner', owner], ['a Manager', manager]] as const) {
    await test.step(who, async () => {
      const { page, context } = await open(accounts, actor);
      await expect(id(card(page, person.id), W.cardLeave), 'the nearer leave, its type and days').toHaveText(TEXT.badge('sick', first, last));
      await expect(id(card(page, single.id), W.cardLeave), 'one day alone').toHaveText(TEXT.badge('annual', first, first));
      await expect(card(page, none.id).getByTestId(W.cardLeave), 'no leave, no badge').toHaveCount(0);
      await context.close();
    });
  }
  for (const role of ['RECEPTIONIST', 'PROFESSIONAL'] as const) {
    await test.step(role, async () => {
      const actor = await accounts.identity.staff(salon, role);
      const { page, context } = await open(accounts, actor);
      await expect(id(card(page, person.id), W.cardLeave), 'days alone').toHaveText(TEXT.badgeWithoutType(first, last));
      await expect(card(page, none.id).getByTestId(W.cardLeave)).toHaveCount(0);
      await context.close();
    });
  }
});

test('TC-F06-S03-038 AC-9, AC-10: sick leave\'s type and note reach no browser without team.manage: a Receptionist, a Professional and a support session see its days on the card and the tab, with neither on the screen, in the document or in anything the browser received; the block controls follow team.block', async ({ accounts, operator, team, browser }) => {
  test.setTimeout(240_000);
  const { salon, owner, created, person, absences, today } = await arrange(operator, accounts, team);
  const note = 'A private reason for this sick day';
  const [first, last] = [plusDays(today, 2), plusDays(today, 3)];
  await absences.addLeave(salon, owner.tokens, person.id, leaveInput(first, { lastDay: last, type: 'sick', note }));
  await absences.addBlock(salon, owner.tokens, person.id, blockInput(plusDays(today, 5), { label: 'Training' }));
  /** Walks the card and both tabs, then searches the screen, the document and every answer received. */
  const walk = async (page: Page, who: string, canBlock: boolean) => {
    const received = browserReceived(page);
    await page.goto(TEAM_ROUTE);
    await ready(page);
    await expect(id(card(page, person.id), W.cardLeave), `${who}: the card's days`).toHaveText(TEXT.badgeWithoutType(first, last));
    const sheet = await openSchedule(page, person.id);
    await leaveTab(sheet).click();
    const row = id(sheet, W.leaveRow).first();
    await expect(row.getByTestId(W.leaveRowDays), `${who}: the tab's days`).toHaveText(daysWords(first, last));
    await expect(row.getByTestId(W.leaveRowType), `${who}: no type`).toHaveCount(0);
    await expect(row.getByTestId(W.leaveRowNote), `${who}: no note`).toHaveCount(0);
    await expect(sheet.getByRole('button', { name: N.addLeave, exact: true }), `${who}: no + Add leave`).toHaveCount(0);
    await expect(row.getByRole('button', { name: N.remove }), `${who}: no Remove on leave`).toHaveCount(0);
    await blockTab(sheet).click();
    await expect(id(sheet, W.blockRow).first(), `${who}: reads the block`).toContainText('Training');
    await expect(sheet.getByRole('button', { name: N.addBlock, exact: true }), `${who}: + Block time`).toHaveCount(canBlock ? 1 : 0);
    await expect(id(sheet, W.blockRow).first().getByRole('button', { name: N.remove }), `${who}: Remove on a block`).toHaveCount(canBlock ? 1 : 0);
    const document = await page.content();
    expect(document, `${who}: no note in the document`).not.toContain(note);
    expect(document, `${who}: no type in the document`).not.toMatch(/sick/i);
    const answers = await received.text();
    expect(received.count(), `${who}: answers were seen, or this proves nothing`).toBeGreaterThan(0);
    expect(answers, `${who}: the leave's days were received, so the search covers its answer`).toContain(first);
    expect(answers, `${who}: no note received`).not.toContain(note);
    expect(answers, `${who}: no note field received`).not.toContain('"note"');
    expect(answers, `${who}: no type received`).not.toMatch(/sick/i);
    expect(answers, `${who}: no type field received`).not.toMatch(/"type"\s*:\s*"(annual|sick|personal|other)"/);
  };
  for (const [role, canBlock] of [['RECEPTIONIST', true], ['PROFESSIONAL', false]] as const) {
    await test.step(role, async () => {
      const actor = await accounts.identity.staff(salon, role);
      const context = await accounts.context(salon, [actor]);
      await walk(await context.newPage(), role, canBlock);
      await context.close();
    });
  }
  await test.step('a support session', async () => {
    const context = await supportBrowser(browser, operator, created, salon);
    await walk(await context.newPage(), 'support', false);
    await context.close();
  });
  await test.step('the control: the Owner does receive and see them', async () => {
    const context = await accounts.context(salon, [owner]);
    const page = await context.newPage();
    const received = browserReceived(page);
    await page.goto(TEAM_ROUTE);
    await ready(page);
    await expect(id(card(page, person.id), W.cardLeave)).toHaveText(TEXT.badge('sick', first, last));
    const sheet = await openSchedule(page, person.id);
    await leaveTab(sheet).click();
    await expect(id(sheet, W.leaveRow).first().getByTestId(W.leaveRowNote)).toHaveText(note);
    const answers = await received.text();
    expect(answers, 'the search would find the note').toContain(note);
    expect(answers, 'and the type').toMatch(/sick/i);
    await context.close();
  });
});

// ---------------------------------------------------------------- AC-13 states, guard, widths, keyboard and accessibility

test('TC-F06-S03-039 AC-13: a dropped connection on saving says so and keeps what was entered, on both tabs, and a change refused after a role change says so and saves nothing', async ({ accounts, operator, team }) => {
  const { salon, owner, person, absences, today } = await arrange(operator, accounts, team);
  const date = plusDays(today, 3);
  await test.step('a dropped connection on each tab, then a retry that saves', async () => {
    const { page, context } = await open(accounts, owner);
    const sheet = await openSchedule(page, person.id);
    await leaveTab(sheet).click();
    await button(sheet, N.addLeave, true).click();
    await fillLeave(page, sheet, date, date, 'personal', 'Kept through a failure');
    await context.route(PENDING_F06_S03.bff.leaveAdd, r => r.abort('connectionfailed'));
    await button(sheet, N.saveLeave, true).click();
    await expect(id(sheet, S.unavailable), 'leave: says the service could not be reached').toBeVisible();
    await expect(id(sheet, W.leaveNote), 'leave: what was entered is still here').toHaveValue('Kept through a failure');
    await context.unroute(PENDING_F06_S03.bff.leaveAdd);
    await button(sheet, N.saveLeave, true).click();
    await expect(toast(page, TEXT.leaveAdded), 'leave: the retry saves').toBeVisible();
    await blockTab(sheet).click();
    await button(sheet, N.addBlock, true).click();
    await fillBlock(page, sheet, date, '10:00', '11:00', 'Kept through a failure');
    await context.route(PENDING_F06_S03.bff.blockAdd, r => r.abort('connectionfailed'));
    await button(sheet, N.saveBlock, true).click();
    await expect(id(sheet, S.unavailable), 'blocks: says the service could not be reached').toBeVisible();
    await expect(id(sheet, W.blockLabel), 'blocks: what was entered is still here').toHaveValue('Kept through a failure');
    await context.unroute(PENDING_F06_S03.bff.blockAdd);
    await context.close();
  });
  const before = await absences.read(salon, owner.tokens, person.id);
  expect([before.leave.length, before.blockedTime.length], 'one leave saved, no block').toEqual([1, 0]);
  /** A Manager with the form ready, made another role before they press save. */
  const madeRole = async (role: string, ready: (sheet: Locator, on: Page) => Promise<void>) => {
    const manager = await accounts.identity.staff(salon, 'MANAGER');
    const managed = await open(accounts, manager);
    const sheet = await openSchedule(managed.page, person.id);
    await ready(sheet, managed.page);
    const record = (await accounts.identity.list(owner as SignedStaff)).find(x => x.id === manager.principal.id)!;
    const changed = await accounts.identity.call(salon, 'PATCH', `/staff/${manager.principal.id}`, { version: record.version, role }, owner.tokens);
    expect(changed.status(), 'the Owner changed the role').toBe(200);
    return sheet;
  };
  await test.step('leave refused for a Manager made a Receptionist', async () => {
    const sheet = await madeRole('RECEPTIONIST', async (on, onPage) => {
      await leaveTab(on).click();
      await button(on, N.addLeave, true).click();
      await fillLeave(onPage, on, plusDays(today, 8), plusDays(today, 8));
    });
    await button(sheet, N.saveLeave, true).click();
    await expect(id(sheet, S.refused), 'says the change is not allowed').toBeVisible();
  });
  await test.step('a block refused for a Manager made a Professional', async () => {
    const sheet = await madeRole('PROFESSIONAL', async (on, onPage) => {
      await blockTab(on).click();
      await button(on, N.addBlock, true).click();
      await fillBlock(onPage, on, plusDays(today, 8), '10:00', '11:00', 'Training');
    });
    await button(sheet, N.saveBlock, true).click();
    await expect(id(sheet, S.refused), 'says the change is not allowed').toBeVisible();
  });
  const after = await absences.read(salon, owner.tokens, person.id);
  expect([after.leave.length, after.blockedTime.length], 'nothing more saved').toEqual([1, 0]);
});

test('TC-F06-S03-040 AC-13: leaving the sheet with a started form by Escape or Close asks first on both tabs; Stay keeps what was entered, Leave discards it, and moving between tabs keeps it with no prompt', async ({ accounts, operator, team }) => {
  const { salon, owner, person, absences } = await arrange(operator, accounts, team);
  const { page } = await open(accounts, owner);
  const sheet = await openSchedule(page, person.id);
  await leaveTab(sheet).click();
  await button(sheet, N.addLeave, true).click();
  await id(sheet, W.leaveNote).fill('Started');
  await test.step('Escape, then Stay', async () => {
    await page.keyboard.press('Escape');
    await expect(id(page, S.leaveDialog)).toBeVisible();
    await id(page, S.leaveStay).click();
    await expect(id(sheet, W.leaveNote), 'kept').toHaveValue('Started');
  });
  await test.step('to Blocked time and back, with no prompt', async () => {
    await blockTab(sheet).click();
    await expect(page.getByTestId(S.leaveDialog)).toHaveCount(0);
    await button(sheet, N.addBlock, true).click();
    await id(sheet, W.blockLabel).fill('Started too');
    await leaveTab(sheet).click();
    await expect(id(sheet, W.leaveNote), 'kept').toHaveValue('Started');
    await blockTab(sheet).click();
    await expect(id(sheet, W.blockLabel), 'kept').toHaveValue('Started too');
  });
  await test.step('Close, then Leave', async () => {
    await id(sheet, S.close).click();
    await expect(id(page, S.leaveDialog)).toBeVisible();
    await id(page, S.leaveConfirm).click();
    await expect(sheetOf(page)).toHaveCount(0);
  });
  const stored = await absences.read(salon, owner.tokens, person.id);
  expect([stored.leave.length, stored.blockedTime.length], 'nothing saved').toEqual([0, 0]);
});

test('TC-F06-S03-041 AC-13: at every width of the matrix both tabs and both forms fit with long lists, long notes and labels and the card\'s badge, with no sideways scrolling and every control reachable', async ({ accounts, operator, team }) => {
  test.setTimeout(300_000);
  const { salon, owner, person, absences, today } = await arrange(operator, accounts, team, 'A name of exactly forty characters long!');
  const note = 'A note of sixty characters, the most that leave can hold....';
  const label = 'A label of forty characters, the longest';
  for (let n = 0; n < 6; n++) {
    await absences.addLeave(salon, owner.tokens, person.id, leaveInput(plusDays(today, 2 + n * 3), { lastDay: plusDays(today, 3 + n * 3), type: 'annual', note }));
    await absences.addBlock(salon, owner.tokens, person.id, blockInput(plusDays(today, 2 + n), { label }));
  }
  for (const { width, height } of DESIGN_WIDTHS) {
    await test.step(`${width} wide`, async () => {
      const context = await accounts.context(salon, [owner], { width });
      const page = await accounts.open(context, TEAM_ROUTE);
      await page.setViewportSize({ width, height });
      await ready(page);
      const checkFit = async (what: string) => {
        await settled(page);
        const f = await fit(page);
        expect(f.counted, `${width} ${what}: elements measured`).toBeGreaterThan(0);
        expect(f.pageScrolls, `${width} ${what}: no sideways scrolling`).toBe(false);
        expect(f.outside, `${width} ${what}: nothing outside the screen`).toEqual([]);
      };
      await checkFit('the card with its badge');
      const sheet = await openSchedule(page, person.id);
      await leaveTab(sheet).click();
      await expect(id(sheet, W.leaveRow)).toHaveCount(6);
      await checkFit('Leave');
      await button(sheet, N.addLeave, true).click();
      await checkFit('the leave form');
      await button(sheet, N.saveLeave, true).scrollIntoViewIfNeeded();
      await expect(button(sheet, N.saveLeave, true), `${width}: its save can be reached`).toBeInViewport();
      await button(sheet, 'Cancel', true).click();
      await blockTab(sheet).click();
      await expect(id(sheet, W.blockRow)).toHaveCount(6);
      await checkFit('Blocked time');
      await button(sheet, N.addBlock, true).click();
      await checkFit('the block form');
      await button(sheet, N.saveBlock, true).scrollIntoViewIfNeeded();
      await expect(button(sheet, N.saveBlock, true), `${width}: its save can be reached`).toBeInViewport();
      await context.close();
    });
  }
});

test('TC-F06-S03-042 AC-13: both tabs, both forms and the confirmation have no serious accessibility violation for the Owner and a read-only role; time is blocked by keyboard alone with a visible focus; each control is named', async ({ accounts, operator, team }) => {
  const { salon, owner, person, absences, today } = await arrange(operator, accounts, team);
  await absences.addLeave(salon, owner.tokens, person.id, leaveInput(plusDays(today, 2), { type: 'annual', note: 'A note' }));
  await absences.addBlock(salon, owner.tokens, person.id, blockInput(plusDays(today, 3)));
  const { page } = await open(accounts, owner);
  const scan = async (on: Page, label: string) => {
    await settled(on);
    expect.soft(await seriousViolations(on), `${label}: no serious or critical violation`).toEqual([]);
  };
  await scan(page, 'the card');
  const sheet = await openSchedule(page, person.id);
  await leaveTab(sheet).click();
  await scan(page, 'Leave');
  await test.step('the leave form, named, with a problem announced', async () => {
    await button(sheet, N.addLeave, true).click();
    await scan(page, 'the leave form');
    for (const control of [W.leaveFirst, W.leaveLast, W.leaveType, W.leaveNote]) await expect(id(sheet, control), control).toHaveAccessibleName(/\S/);
    await button(sheet, 'Cancel', true).click();
  });
  await test.step('the confirmation', async () => {
    await button(id(sheet, W.leaveRow).first(), N.remove).click();
    await scan(page, 'removing leave');
    await id(id(page, W.leaveRemoveDialog), W.leaveRemoveCancel).click();
  });
  await blockTab(sheet).click();
  await scan(page, 'Blocked time');
  await test.step('the block form, named, with a problem announced', async () => {
    await button(sheet, N.addBlock, true).click();
    await scan(page, 'the block form');
    for (const control of [W.blockDate, W.blockStarts, W.blockFinishes, W.blockLabel]) await expect(id(sheet, control), control).toHaveAccessibleName(/\S/);
    await id(sheet, W.blockLabel).fill('T');
    await expect(sheet.getByRole('alert').filter({ hasText: TEXT.addLabel })).toBeVisible();
    await scan(page, 'the block form with a problem');
    await button(sheet, 'Cancel', true).click();
  });
  await test.step('time blocked by keyboard alone', async () => {
    const tabTo = async (target: Locator, what: string) => {
      let steps = 0;
      while (!(await target.evaluate(el => el === document.activeElement)) && steps++ < 120) await page.keyboard.press('Tab');
      await expect(target, `${what} reached by Tab, inside the sheet`).toBeFocused();
    };
    const add = button(sheet, N.addBlock, true);
    await tabTo(add, '+ Block time');
    const focus = await focusIndicator(add);
    expect(focus.focusVisible && focus.outlineWidth > 0 && focus.outlineStyle !== 'none', `a visible focus: ${JSON.stringify(focus)}`).toBe(true);
    await page.keyboard.press('Enter');
    const date = id(sheet, W.blockDate);
    await tabTo(date, 'the date');
    await page.keyboard.press('Enter');
    const day = calendarToday(page, today);
    await expect(day, 'the Calendar opens on today').toBeFocused();
    await page.keyboard.press('ArrowRight');
    await page.keyboard.press('Enter');
    await expect(page.getByTestId(W.blockCalendar)).toHaveCount(0);
    await tabTo(id(sheet, W.blockLabel), 'the label');
    await page.keyboard.type('Keyboard block');
    await tabTo(button(sheet, N.saveBlock, true), 'its save');
    await page.keyboard.press('Enter');
    await expect(toast(page, TEXT.blocked)).toBeVisible();
    expect((await absences.read(salon, owner.tokens, person.id)).blockedTime.map(b => b.label), 'saved').toContain('Keyboard block');
  });
  await test.step('a read-only role', async () => {
    const professional = await accounts.identity.staff(salon, 'PROFESSIONAL');
    const other = await open(accounts, professional);
    const readOnly = await openSchedule(other.page, person.id);
    await leaveTab(readOnly).click();
    await scan(other.page, 'Leave, Professional');
    await blockTab(readOnly).click();
    await scan(other.page, 'Blocked time, Professional');
  });
});

/** Today's button in the block form's Calendar, which a keyboard user lands on. */
function calendarToday(page: Page, today: string) {
  const written = new Intl.DateTimeFormat('en-LK', { weekday: 'long', day: 'numeric', month: 'long', year: 'numeric', timeZone: 'UTC' }).format(new Date(`${today}T00:00:00Z`));
  return page.getByTestId(W.blockCalendar).getByRole('button', { name: new RegExp(`^(Today, )?${written}`) });
}
