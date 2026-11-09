/**
 * F07-S03 the appointment sheet: its badges and rows, exactly the actions `allowedActions` permits with the words each
 * says, the prototype's explanations, the History newest first, the cancel Alert Dialog, and every refusal from the API
 * shown rather than swallowed, the list and sheet following each change without a reload.
 *
 * Expected results are the F07-S03 spec's AC-7 to AC-9 and the prototype's `openA`, `f7Set` and `f7Cancel`. Which actions
 * the API allows is F07-S01's (TC-F07-S01-016 to -018); here each one the API allows must be offered and none other.
 * Prepared from the approved spec during the build; test ids are `PENDING_F07_S03`, confirmed at S03's close.
 */
import type { Locator, Page } from '@playwright/test';

import { again, bookingSalon, customer, holdInput, national, staffAt, utcOf, type BookingSalon } from '../../shared/booking';
import {
  BOOKINGS_ROUTE, PENDING_F07_S03 as W, S03_TEXT as T, button, firstName, id, openAs, shownDay, shownTime, supportBrowser, test, expect,
} from '../../shared/booking-browser';
import type { SignedStaff } from '../../shared/identity';
import { plusDays } from '../../shared/salon-hours';

test.describe.configure({ timeout: 240_000 });

const A = W.appointment;
const ACTION_ORDER = ['arrive', 'start', 'no-show', 'undo-no-show', 'cancel', 'release'];

async function openBooking(page: Page, reference: string): Promise<Locator> {
  await expect(id(page, W.page.loading)).toHaveCount(0);
  await id(page, W.page.row).or(id(page, W.page.item)).filter({ hasText: reference }).first().click();
  const sheet = id(page, A.sheet);
  await expect(sheet).toBeVisible();
  return sheet;
}
/** The actions the sheet offers, by their words. */
const offered = async (sheet: Locator) => {
  const out: string[] = [];
  for (const a of ACTION_ORDER) if (await sheet.getByTestId(A.action(a)).filter({ visible: true }).count()) out.push(a);
  return out;
};
async function expectOffered(s: BookingSalon, who: SignedStaff, sheet: Locator, bookingId: string, label: string) {
  const allowed = (await s.bookings.get(s.salon, who.tokens, bookingId)).allowedActions.filter(a => a !== 'confirm');
  await expect.poll(() => offered(sheet), `${label}: exactly what the API allows (${allowed.join(', ')})`).toEqual(ACTION_ORDER.filter(a => allowed.includes(a as never)));
  for (const a of allowed) if (T.actions[a]) await expect(sheet.getByTestId(A.action(a)), a).toHaveText(T.actions[a]);
}

test('TC-F07-S03-010 AC-7: the sheet shows the customer, status and source, the rows Reference to Source, exactly the actions the API allows with the words each says after it, the prototype\'s explanations, and the History newest first with what, who and when', async ({ accounts, identity, operator, clock }) => {
  const s = await bookingSalon(identity, operator, clock, { people: 1 });
  const who = customer();
  const booking = await s.bookings.confirm(s.salon, s.owner.tokens, (await s.bookings.place(s.salon, s.owner.tokens, holdInput(s.members[0].id, s.service.id, s.today, '09:00', { customer: who, channel: 'instagram' }))).id);
  const { page } = await openAs(accounts, s.owner, BOOKINGS_ROUTE);
  const sheet = await openBooking(page, booking.reference);
  await expect(id(sheet, A.name)).toHaveText(who.name);
  await expect(id(sheet, A.status)).toHaveText(T.statusLabels.confirmed);
  await expect(id(sheet, A.source)).toHaveText('Instagram');
  for (const label of T.rows) await expect(sheet.getByText(label, { exact: true }).filter({ visible: true }).first(), `the ${label} row`).toBeVisible();
  for (const fact of [booking.reference, national(who.mobile), s.service.name, s.members[0].name, shownDay(s.today), shownTime('09:00')]) await expect(sheet, fact).toContainText(fact);
  await test.step('before the start', async () => {
    await expectOffered(s, s.owner, sheet, booking.id, 'confirmed, before the start');
    await expect(sheet).toContainText(T.explanations.noShowAfterStart);
    await expect(sheet).toContainText(T.explanations.completedAtCheckout);
  });
  await test.step('at the start: no-show and undo', async () => {
    await clock.set(utcOf(s.today, '09:05'));
    const owner = await again(identity, s.owner);
    const { page: later } = await openAs(accounts, owner, BOOKINGS_ROUTE);
    const now = await openBooking(later, booking.reference);
    await expectOffered(s, owner, now, booking.id, 'after the start');
    await now.getByTestId(A.action('no-show')).click();
    await expect(later.getByText(T.done['no-show'](firstName(who.name)))).toBeVisible();
    await expect(id(now, A.status), 'the sheet follows').toHaveText(T.statusLabels.no_show);
    await expect(now).toContainText(T.explanations.undoToday);
    await expectOffered(s, owner, now, booking.id, 'a no-show');
    await now.getByTestId(A.action('undo-no-show')).click();
    await expect(later.getByText(T.done['undo-no-show'])).toBeVisible();
    await now.getByTestId(A.action('arrive')).click();
    await expect(later.getByText(T.done.arrive)).toBeVisible();
    await expect(now).toContainText(T.explanations.noCancelAfterArrival);
    await now.getByTestId(A.action('start')).click();
    await expect(later.getByText(T.done.start)).toBeVisible();
    await expect(id(later, W.page.row).or(id(later, W.page.item)).filter({ hasText: booking.reference }), 'the list follows without a reload').toContainText(T.statusLabels.in_service);
    const rows = now.getByTestId(A.historyRow);
    await expect(rows, 'six changes').toHaveCount(6);
    await expect(rows.first(), 'newest first').toContainText(T.statusLabels.in_service);
    await expect(rows.first(), 'by you').toContainText(T.you);
  });
  await test.step('a hold is released', async () => {
    const owner = await again(identity, s.owner);
    const hold = await s.bookings.place(s.salon, owner.tokens, holdInput(s.members[0].id, s.service.id, s.today, '15:00'));
    const { page: p } = await openAs(accounts, owner, BOOKINGS_ROUTE);
    const holdSheet = await openBooking(p, hold.reference);
    await expectOffered(s, owner, holdSheet, hold.id, 'a hold');
    await holdSheet.getByTestId(A.action('release')).click();
    await expect(p.getByText(T.done.release)).toBeVisible();
    expect((await s.bookings.getRaw(s.salon, owner.tokens, hold.id)).status(), 'released, through the API').toBe(404);
  });
});

test('TC-F07-S03-011 AC-8: Cancel booking asks first in an Alert Dialog with the customer, day and time, a required Reason and an optional Note of up to 200 characters; Keep booking changes nothing; Cancel booking cancels with the reason and note and says "Cancelled. The time is open again."', async ({ accounts, identity, operator, clock }) => {
  const s = await bookingSalon(identity, operator, clock, { people: 1 });
  const who = customer();
  const date = plusDays(s.today, 1);
  const booking = await s.bookings.confirm(s.salon, s.owner.tokens, (await s.bookings.place(s.salon, s.owner.tokens, holdInput(s.members[0].id, s.service.id, date, '11:00', { customer: who }))).id);
  const { page } = await openAs(accounts, s.owner, `${BOOKINGS_ROUTE}?${W.query.day}=${date}`);
  const sheet = await openBooking(page, booking.reference);
  await sheet.getByTestId(A.action('cancel')).click();
  const dialog = page.getByRole('alertdialog');
  await expect(dialog).toBeVisible();
  for (const text of [T.cancelTitle, who.name, shownDay(date), shownTime('11:00'), T.cancelOpens, T.cancelNoteHint]) await expect(dialog, text).toContainText(text);
  await id(dialog, W.cancel.reason).click();
  await expect(page.getByRole('option').filter({ visible: true }), 'the reasons').toHaveText(T.cancelReasons.map(r => r[1]));
  await page.keyboard.press('Escape');
  await button(dialog, T.keep, true).click();
  await expect(dialog).toHaveCount(0);
  expect((await s.bookings.get(s.salon, s.owner.tokens, booking.id)).booking.state, 'Keep booking changes nothing').toBe('confirmed');
  await sheet.getByTestId(A.action('cancel')).click();
  await button(dialog, T.cancelConfirm, true).click();
  await expect(dialog, 'a reason is required').toBeVisible();
  expect((await s.bookings.get(s.salon, s.owner.tokens, booking.id)).booking.state, 'not without a reason').toBe('confirmed');
  await id(dialog, W.cancel.reason).click();
  await page.getByRole('option', { name: T.cancelReasons[1][1], exact: true }).click();
  await id(dialog, W.cancel.note).fill('N'.repeat(201));
  await expect(id(dialog, W.cancel.note), 'at most 200 characters').toHaveValue(/^N{0,200}$/);
  await id(dialog, W.cancel.note).fill('Stylist away; offered Thursday instead');
  await button(dialog, T.cancelConfirm, true).click();
  await expect(page.getByText(T.cancelled)).toBeVisible();
  const detail = await s.bookings.get(s.salon, s.owner.tokens, booking.id);
  expect(detail.history.at(-1), 'the reason and note, through the API').toMatchObject({ to: 'cancelled', reason: 'salon_unable', note: 'Stylist away; offered Thursday instead' });
  await expect(id(sheet, A.status)).toHaveText(T.statusLabels.cancelled);
  await expect(sheet).toContainText(T.explanations.cancelledFinal);
});

test('TC-F07-S03-012 AC-9: every refusal is shown: a booking changed elsewhere refreshes with "This booking changed. Here is its current state.", rules never saved says to set them with a link for those who may, a support session is told it is read-only, and an unavailable API shows the unavailable state', async ({ accounts, browser, identity, operator, clock }) => {
  const s = await bookingSalon(identity, operator, clock, { people: 1 });
  const booking = await s.bookings.confirm(s.salon, s.owner.tokens, (await s.bookings.place(s.salon, s.owner.tokens, holdInput(s.members[0].id, s.service.id, s.today, '12:00'))).id);
  const { page } = await openAs(accounts, s.owner, BOOKINGS_ROUTE);
  await test.step('invalid_state: changed elsewhere', async () => {
    const sheet = await openBooking(page, booking.reference);
    await s.bookings.act(s.salon, s.owner.tokens, booking.id, 'cancel', { reason: 'customer_asked' });
    await sheet.getByTestId(A.action('arrive')).click();
    await expect(id(sheet, A.refusal)).toHaveText(T.changed);
    await expect(id(sheet, A.status), 'refreshed').toHaveText(T.statusLabels.cancelled);
  });
  await test.step('rules_not_set', async () => {
    const bare = await bookingSalon(identity, operator, clock, { people: 1, rulesSaved: false, keepClock: true });
    const { page: p } = await openAs(accounts, bare.owner, BOOKINGS_ROUTE);
    await button(p, T.newAppointment).click();
    const sheet = id(p, W.sheet.sheet);
    await id(sheet, W.sheet.name).fill('Nethmi Bandara');
    await id(sheet, W.sheet.mobile).fill(customer().mobile);
    await button(sheet, T.find).click();
    await expect(sheet.getByText(T.rulesNotSet)).toBeVisible();
    await expect(sheet.getByRole('link', { name: /settings|booking rules/i }), 'a link for the Owner').toBeVisible();
    const receptionist = await staffAt(bare, 'RECEPTIONIST');
    const { page: r } = await openAs(accounts, receptionist, BOOKINGS_ROUTE);
    await button(r, T.newAppointment).click();
    const theirs = id(r, W.sheet.sheet);
    await id(theirs, W.sheet.name).fill('Nethmi Bandara');
    await id(theirs, W.sheet.mobile).fill(customer().mobile);
    await button(theirs, T.find).click();
    await expect(theirs.getByText(T.rulesNotSet)).toBeVisible();
    await expect(theirs.getByRole('link', { name: /settings|booking rules/i }), 'no link for someone who may not set them').toHaveCount(0);
  });
  await test.step('a support session: read-only', async () => {
    const context = await supportBrowser(browser, operator, s.created, s.salon);
    const p = await context.newPage();
    await p.goto(BOOKINGS_ROUTE);
    await expect(id(p, W.page.loading)).toHaveCount(0);
    await expect(p.getByTestId(W.page.newAppointment).or(button(p, T.newAppointment)), 'no New appointment').toHaveCount(0);
    await context.close();
  });
  await test.step('the API unavailable', async () => {
    const { page: p } = await openAs(accounts, s.owner, '/workspace/account');
    await p.route(url => new URL(url).pathname.includes('booking'), route => route.fulfill({ status: 503, contentType: 'application/problem+json',
      body: JSON.stringify({ type: 'about:blank', title: 'Service Unavailable', status: 503, code: 'service_unavailable', detail: 'Try again shortly.' }) }));
    await p.goto(BOOKINGS_ROUTE);
    await expect(id(p, W.page.unavailable)).toBeVisible();
    await expect(id(p, W.page.retry)).toBeVisible();
  });
});
