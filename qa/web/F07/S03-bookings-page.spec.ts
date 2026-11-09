/**
 * F07-S03 the Bookings page: who finds it in the navigation, one day's bookings and holds in time order as a Table wide
 * and as rows narrow, a hold's countdown and its row leaving at zero without a reload, the search, day, status and
 * professional tools, the empty state, pages, and the URL keeping the day and filters.
 *
 * Expected results are the F07-S03 spec's AC-1 to AC-3 and AC-12, and the prototype's `aApptsW`, `aAppts`, `f7Tools` and
 * `f7Badges`. Bookings are arranged through the API (qa/shared/booking.ts); what is checked is what the person sees.
 * Prepared from the approved spec during the build; test ids are `PENDING_F07_S03`, confirmed at S03's close.
 */
import type { Page } from '@playwright/test';

import { bookingSalon, customer, holdInput, national, staffAt, type BookingSalon } from '../../shared/booking';
import {
  BOOKINGS_ROUTE, PENDING_F07_S03 as W, S03_TEXT as T, button, chooseCalendarDay, id, left, openAs, shownDay, shownTime, test, expect,
} from '../../shared/booking-browser';
import { plusDays } from '../../shared/salon-hours';

test.describe.configure({ timeout: 240_000 });

const P = W.page;

async function ready(page: Page) {
  await expect(id(page, P.loading)).toHaveCount(0);
  await expect(id(page, P.heading)).toBeVisible();
}
async function bookingsOf(page: Page) {
  await ready(page);
  return id(page, P.row).or(id(page, P.item));
}

/** A day with a confirmed booking at 10:00, a hold at 11:00 and a cancellation at 12:00, and one booking the next day. */
async function aDay(s: BookingSalon) {
  const [m1, m2] = s.members;
  const amaya = customer();
  const kavindi = customer();
  const confirmed = await s.bookings.confirm(s.salon, s.owner.tokens, (await s.bookings.place(s.salon, s.owner.tokens, holdInput(m1.id, s.service.id, s.today, '10:00', { customer: amaya, channel: 'whatsapp' }))).id);
  const held = await s.bookings.place(s.salon, s.owner.tokens, holdInput(m2.id, s.service.id, s.today, '11:00', { customer: kavindi }));
  const cancelled = await s.bookings.confirm(s.salon, s.owner.tokens, (await s.bookings.place(s.salon, s.owner.tokens, holdInput(m1.id, s.service.id, s.today, '12:00'))).id);
  await s.bookings.act(s.salon, s.owner.tokens, cancelled.id, 'cancel', { reason: 'customer_asked' });
  const tomorrow = await s.bookings.place(s.salon, s.owner.tokens, holdInput(m1.id, s.service.id, plusDays(s.today, 1), '10:00'));
  return { amaya, kavindi, confirmed, held, cancelled, tomorrow };
}

test('TC-F07-S03-001 AC-1: the Owner, a Manager, a Receptionist and a Professional find Bookings in the navigation, after Account and before Team, and it opens the page; an Accountant has no entry and the page shows the workspace\'s not-permitted state', async ({ accounts, identity, operator, clock }) => {
  const s = await bookingSalon(identity, operator, clock, { people: 1 });
  const allowed = [['the Owner', s.owner], ['a Manager', await staffAt(s, 'MANAGER')], ['a Receptionist', await staffAt(s, 'RECEPTIONIST')],
    ['a Professional', await staffAt(s, 'PROFESSIONAL', { linkTo: s.members[0] })]] as const;
  for (const [who, actor] of allowed) {
    await test.step(who, async () => {
      const { page } = await openAs(accounts, actor, '/workspace/account');
      const entry = id(page, W.nav);
      await expect(entry, `${who}: the entry`).toHaveText(T.nav);
      const order = (await page.getByRole('navigation').first().getByRole('link').allInnerTexts()).map(t => t.trim());
      const at = order.indexOf(T.nav);
      expect(order[at - 1], `${who}: right after ${T.navAfter} (${order.join(', ')})`).toBe(T.navAfter);
      expect(order[at + 1], `${who}: before ${T.navBefore}`).toBe(T.navBefore);
      await entry.click();
      await page.waitForURL(`**${BOOKINGS_ROUTE}**`);
      await ready(page);
    });
  }
  await test.step('an Accountant', async () => {
    const { page } = await openAs(accounts, await staffAt(s, 'ACCOUNTANT'), '/workspace/account');
    await expect(page.getByTestId(W.nav), 'no entry').toHaveCount(0);
    await page.goto(BOOKINGS_ROUTE);
    await expect(id(page, P.notPermitted), 'not permitted').toBeVisible();
    await expect(page.getByTestId(P.table)).toHaveCount(0);
  });
});

test('TC-F07-S03-002 AC-2, AC-12: today\'s bookings and holds are listed in time order, wide as a Table of When, Customer, Service, Professional, Status and Reference and narrow as rows with the same facts, under "N bookings on <day>"; a hold reads "Held · m:ss left" and counts down', async ({ accounts, identity, operator, clock }) => {
  const s = await bookingSalon(identity, operator, clock, { people: 2 });
  const { amaya, confirmed, held } = await aDay(s);
  const [m1] = s.members;
  await test.step('wide', async () => {
    const { page } = await openAs(accounts, s.owner, BOOKINGS_ROUTE, 1280);
    const rows = await bookingsOf(page);
    await expect(id(page, P.heading), 'three bookings on the day, today by default').toHaveText(T.heading(3, shownDay(s.today)));
    await expect(id(page, P.table).getByRole('columnheader'), 'the columns').toHaveText(T.columns);
    await expect(rows, 'three rows').toHaveCount(3);
    const first = rows.nth(0);
    await expect(first, 'the 10:00 booking first').toContainText(shownTime('10:00'));
    for (const fact of [amaya.name, national(amaya.mobile), s.service.name, m1.name, T.statusLabels.confirmed, confirmed.reference]) await expect(first, fact).toContainText(fact);
    const hold = rows.nth(1).getByTestId(P.statusBadge);
    await expect(hold, 'the hold counts down').toHaveText(/^Held · \d+:\d\d left$/);
    const before = await hold.innerText();
    await expect.poll(() => hold.innerText(), { message: 'a second later it reads less', timeout: 5_000 }).not.toBe(before);
    expect(T.held(left(held.holdExpiresAt!, await identity.now())), 'the shape of the countdown').toMatch(/^Held · \d+:\d\d left$/);
    await expect(rows.nth(2), 'a cancellation is on the list').toContainText(T.statusLabels.cancelled);
  });
  await test.step('narrow', async () => {
    const { page } = await openAs(accounts, s.owner, BOOKINGS_ROUTE, 390);
    const rows = await bookingsOf(page);
    await expect(page.getByTestId(P.table).filter({ visible: true }), 'no table').toHaveCount(0);
    await expect(rows).toHaveCount(3);
    for (const fact of [shownTime('10:00'), amaya.name, s.service.name, m1.name, T.statusLabels.confirmed, confirmed.reference]) await expect(rows.nth(0), fact).toContainText(fact);
  });
  await test.step('one booking reads in the singular', async () => {
    const { page } = await openAs(accounts, s.owner, `${BOOKINGS_ROUTE}?${W.query.day}=${plusDays(s.today, 1)}`);
    await ready(page);
    await expect(id(page, P.heading)).toHaveText(T.heading(1, shownDay(plusDays(s.today, 1))));
  });
});

test('TC-F07-S03-003 AC-2: a hold\'s row leaves the list without a reload when its countdown reaches zero', async ({ accounts, identity, operator, clock }) => {
  const s = await bookingSalon(identity, operator, clock, { people: 1 });
  const hold = await s.bookings.place(s.salon, s.owner.tokens, holdInput(s.members[0].id, s.service.id, s.today, '10:00'));
  await clock.set(new Date(Date.parse(hold.holdExpiresAt!) - 8_000).toISOString());
  const owner = await identity.login(s.salon, s.owner.account);
  const { page } = await openAs(accounts, owner, BOOKINGS_ROUTE);
  const rows = await bookingsOf(page);
  await expect(rows, 'the hold, seconds from its end').toHaveCount(1);
  const loads: string[] = [];
  page.on('framenavigated', f => { if (f === page.mainFrame()) loads.push(f.url()); });
  await expect(rows, 'gone at zero').toHaveCount(0, { timeout: 20_000 });
  expect(loads, 'without a reload').toEqual([]);
});

test('TC-F07-S03-004 AC-3: the search (name, mobile or reference, as typed), the day chooser with Today, the status and professional Selects narrow the list through the API; no match shows the empty state and Clear filters; more than a page uses the pager; the URL keeps the day and filters through reload and back', async ({ accounts, identity, operator, clock }) => {
  const s = await bookingSalon(identity, operator, clock, { people: 2 });
  const { amaya, kavindi, confirmed, held } = await aDay(s);
  const { page } = await openAs(accounts, s.owner, BOOKINGS_ROUTE);
  const rows = await bookingsOf(page);
  const search = id(page, P.search);
  await test.step('search by name, mobile and reference', async () => {
    // The whole name: consecutive generated people share a surname (qa/shared/people.ts), so part of it matches several.
    for (const [what, q, expected] of [['name', amaya.name, confirmed.reference], ['mobile', national(kavindi.mobile), held.reference], ['reference', confirmed.reference.replace('-', '').toLowerCase(), confirmed.reference]] as const) {
      await search.fill(q);
      await expect(rows, `${what}: one`).toHaveCount(1);
      await expect(rows.first(), what).toContainText(expected);
      expect(new URL(page.url()).searchParams.get(W.query.q), `${what}: kept in the URL`).toBe(q);
    }
    await search.fill('');
    await expect(rows).toHaveCount(3);
  });
  await test.step('status and professional', async () => {
    await id(page, P.status).click();
    await expect(page.getByRole('option').filter({ visible: true }), 'the statuses').toHaveText(T.statusOptionLabels);
    await page.getByRole('option', { name: 'Held', exact: true }).click();
    await expect(rows).toHaveCount(1);
    await expect(rows.first()).toContainText(held.reference);
    await page.reload();
    await expect(await bookingsOf(page), 'kept through a reload').toHaveCount(1);
    await id(page, P.professional).click();
    await expect(page.getByRole('option').filter({ visible: true }).first(), 'Everyone first').toHaveText(T.everyone);
    await page.getByRole('option', { name: s.members[0].name, exact: true }).click();
    await expect(id(page, P.empty), 'no hold of the first professional today').toBeVisible();
    await expect(id(page, P.empty)).toContainText(T.emptyTitle);
    await expect(id(page, P.empty)).toContainText(T.emptyHint);
    await button(page, T.clear).click();
    await expect(await bookingsOf(page)).toHaveCount(3);
  });
  await test.step('the day chooser and Today', async () => {
    await id(page, P.day).click();
    await chooseCalendarDay(id(page, P.calendar), plusDays(s.today, 1));
    await expect(id(page, P.heading)).toHaveText(T.heading(1, shownDay(plusDays(s.today, 1))));
    await page.goBack();
    await expect(id(page, P.heading), 'back restores the day').toHaveText(T.heading(3, shownDay(s.today)));
    await page.goForward();
    await id(page, P.dayToday).click();
    await expect(id(page, P.heading)).toHaveText(T.heading(3, shownDay(s.today)));
  });
  await test.step('pages', async () => {
    const day = plusDays(s.today, 3);
    const more = await bookingSalon(identity, operator, clock, { people: 6, keepClock: true });
    for (const m of more.members) for (let h = 9; h < 18; h++) await more.bookings.place(more.salon, more.owner.tokens, holdInput(m.id, more.service.id, day, `${String(h).padStart(2, '0')}:00`));
    const { page: busy } = await openAs(accounts, more.owner, `${BOOKINGS_ROUTE}?${W.query.day}=${day}`);
    await ready(busy);
    await expect(id(busy, P.heading)).toHaveText(T.heading(54, shownDay(day)));
    await expect(id(busy, P.pager), 'more than a page').toBeVisible();
    await id(busy, P.next).click();
    await expect(id(busy, P.row).or(id(busy, P.item)), 'the rest on the second page').not.toHaveCount(0);
  });
});
