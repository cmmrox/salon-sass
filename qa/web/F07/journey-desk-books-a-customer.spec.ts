/**
 * F07 journey: a customer phones, the desk books them with "No preference" on the New appointment sheet, the held time is
 * gone for everyone else while it is held, the booking is confirmed with a reference, and then marked arrived and
 * started, while another is cancelled with a reason; each change is in its history.
 *
 * Expected results are the F07 README's "Feature-level test focus" (the journey) and BKG-01, BKG-03, BKG-04, BKG-06,
 * BKG-11, BKG-14 and BKG-16. It asserts what passes between the stages (the hold the sheet placed is S01's hold, S02's
 * availability no longer offers it, S01's history records what the sheet did), not each stage's rules again.
 */
import { REFERENCE_SHOWN, bookingSalon, customer, holdInput, liveRowsOf, staffAt } from '../../shared/booking';
import {
  BOOKINGS_ROUTE, PENDING_F07_S03 as W, S03_TEXT as T, button, chooseCalendarDay, firstName, id, openAs, shownDay, test, expect,
} from '../../shared/booking-browser';
import { plusDays } from '../../shared/salon-hours';

test.describe.configure({ timeout: 300_000 });

test('TC-F07-001 BKG-01, BKG-03, BKG-04, BKG-06, BKG-11, BKG-14, BKG-16: the desk books a phoned customer with No preference; the held time is not offered to anyone else; confirmed, it has a reference and its source; arrived, started and another cancelled with a reason, every change is in its history', async ({ accounts, identity, operator, clock }) => {
  const s = await bookingSalon(identity, operator, clock, { people: 2 });
  const receptionist = await staffAt(s, 'RECEPTIONIST');
  const who = customer();
  const date = s.today;
  const { page } = await openAs(accounts, receptionist, BOOKINGS_ROUTE);
  await expect(id(page, W.page.loading)).toHaveCount(0);

  let heldId = '';
  let heldTime = '';
  let heldMember = '';
  await test.step('the desk holds a time with No preference', async () => {
    await button(page, T.newAppointment).click();
    const sheet = id(page, W.sheet.sheet);
    await id(sheet, W.sheet.name).fill(who.name);
    await id(sheet, W.sheet.mobile).fill(who.mobile);
    await id(sheet, W.sheet.service).click();
    await page.getByRole('option', { name: new RegExp(`^${s.service.name}`) }).filter({ visible: true }).click();
    await id(sheet, W.sheet.day).click();
    await chooseCalendarDay(id(page, W.sheet.calendar), date);
    await id(sheet, W.sheet.professional).click();
    await page.getByRole('option', { name: T.noPreference, exact: true }).filter({ visible: true }).click();
    await button(sheet, T.find).click();
    const chip = id(sheet, W.sheet.time).first();
    heldTime = (await chip.innerText()).split(' · ')[0];
    await chip.click();
    await expect(id(sheet, W.sheet.hold)).toContainText(T.holding);
    const held = s.members.flatMap(m => liveRowsOf(s.created.id, m.id).map(r => ({ ...r, member: m.id }))).filter(r => r.state === 'held');
    expect(held.length, 'one hold, placed by the sheet').toBe(1);
    heldId = held[0].id;
    heldMember = held[0].member;
  });
  await test.step('while held, nobody else is offered that time with that person', async () => {
    const manager = await staffAt(s, 'MANAGER');
    const offered = await s.bookings.availability(s.salon, manager.tokens, { serviceId: s.service.id, date, memberId: heldMember });
    expect(offered.times.map(t => t.time), 'not offered for the held person').not.toContain(heldTime);
    const noPreference = await s.bookings.availability(s.salon, manager.tokens, { serviceId: s.service.id, date });
    expect(noPreference.times.filter(t => t.time === heldTime).map(t => t.member.id), 'with No preference only someone else').not.toContain(heldMember);
  });
  let reference = '';
  await test.step('confirmed, with a reference and its source', async () => {
    await button(id(page, W.sheet.sheet), T.confirm).click();
    const detail = await s.bookings.get(s.salon, receptionist.tokens, heldId);
    reference = detail.booking.reference;
    expect(reference).toMatch(REFERENCE_SHOWN);
    expect(detail.booking.channel, 'where it came from').toBe('phone');
    expect(detail.booking.customer.name).toBe(who.name);
    await expect(page.getByText(T.booked(shownDay(date), heldTime, firstName(detail.booking.member.name), reference))).toBeVisible();
  });
  await test.step('arrived and started from the list', async () => {
    const row = id(page, W.page.row).or(id(page, W.page.item)).filter({ hasText: reference });
    await expect(row, 'on the list without a reload').toContainText(T.statusLabels.confirmed);
    await row.click();
    const sheet = id(page, W.appointment.sheet);
    await sheet.getByTestId(W.appointment.action('arrive')).click();
    await expect(page.getByText(T.done.arrive)).toBeVisible();
    await sheet.getByTestId(W.appointment.action('start')).click();
    await expect(page.getByText(T.done.start)).toBeVisible();
    await expect(sheet.getByTestId(W.appointment.historyRow), 'held, confirmed, arrived, in service').toHaveCount(4);
  });
  await test.step('another is cancelled with a reason', async () => {
    const other = await s.bookings.confirm(s.salon, receptionist.tokens, (await s.bookings.place(s.salon, receptionist.tokens, holdInput(s.members[0].id, s.service.id, plusDays(date, 1), '16:00'))).id);
    const { page: next } = await openAs(accounts, receptionist, `${BOOKINGS_ROUTE}?${W.query.day}=${plusDays(date, 1)}`);
    await id(next, W.page.row).or(id(next, W.page.item)).filter({ hasText: other.reference }).click();
    await id(next, W.appointment.sheet).getByTestId(W.appointment.action('cancel')).click();
    const dialog = next.getByRole('alertdialog');
    await id(dialog, W.cancel.reason).click();
    await next.getByRole('option', { name: T.cancelReasons[0][1], exact: true }).click();
    await button(dialog, T.cancelConfirm, true).click();
    await expect(next.getByText(T.cancelled)).toBeVisible();
    const history = (await s.bookings.get(s.salon, receptionist.tokens, other.id)).history;
    expect(history.map(h => [h.from, h.to, h.reason, h.by.label]), 'every change, by the Receptionist').toEqual([
      [null, 'held', null, receptionist.account.email], ['held', 'confirmed', null, receptionist.account.email], ['confirmed', 'cancelled', 'customer_asked', receptionist.account.email]]);
  });
  const first = (await s.bookings.get(s.salon, receptionist.tokens, heldId)).history;
  expect(first.map(h => h.to), 'the first booking\'s history').toEqual(['held', 'confirmed', 'arrived', 'in_service']);
});
