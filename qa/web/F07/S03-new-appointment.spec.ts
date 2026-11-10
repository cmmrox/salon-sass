/**
 * F07-S03 the New appointment sheet: its fields and the prototype's messages, free times as chips with the reasons for
 * none, holding a time with a live countdown from the server's expiry, confirming it, choosing another, closing with a
 * hold, a double click, a time taken meanwhile and a hold that runs out.
 *
 * Expected results are the F07-S03 spec's AC-4 to AC-6 and AC-12, and the prototype's `f7New`, `f7Find`, `f7Hold`,
 * `f7HoldPaint`, `f7Confirm` and `f7Expire`. Each hold and confirmation is checked once more through the API. Prepared
 * from the approved spec during the build; test ids are `PENDING_F07_S03`, confirmed at S03's close.
 */
import type { Locator, Page } from '@playwright/test';

import { bookingSalon, holdInput, liveRowsOf, storedBooking, variantService, type BookingSalon } from '../../shared/booking';
import {
  BOOKINGS_ROUTE, PENDING_F07_S03 as W, S03_TEXT as T, button, calendarDayButton, chooseCalendarDay, firstName, id, openAs, shownDay, shownTime, test, expect,
} from '../../shared/booking-browser';
import { customer } from '../../shared/booking';
import { plusDays, week, weekdayOf } from '../../shared/salon-hours';
import { Absences, leaveInput } from '../../shared/team-leave';

test.describe.configure({ timeout: 240_000 });

const N = W.sheet;

async function openSheet(page: Page): Promise<Locator> {
  await expect(id(page, W.page.loading)).toHaveCount(0);
  await id(page, W.page.newAppointment).click();
  const sheet = id(page, N.sheet);
  await expect(sheet).toBeVisible();
  return sheet;
}
async function chooseDay(page: Page, sheet: Locator, date: string) {
  await id(sheet, N.day).click();
  await chooseCalendarDay(id(page, N.calendar), date);
}
/** Chooses an option of a Select by the option's test id and `data-id` (S03 handoff), waiting for the list to close. */
async function choose(page: Page, trigger: Locator, option: Locator) {
  await trigger.click();
  await option.click();
  await expect(page.getByRole('listbox')).toHaveCount(0);
}
/** Fills the form for a customer and searches, returning the chips; `memberId` null is No preference. */
async function search(page: Page, sheet: Locator, s: BookingSalon, date: string, who = customer(), memberId: string | null = null) {
  await id(sheet, N.name).fill(who.name);
  await id(sheet, N.mobile).fill(who.mobile);
  await chooseDay(page, sheet, date);
  await choose(page, id(sheet, N.service), page.locator(`[data-testid="${N.serviceOption}"][data-id="${s.service.id}"]`));
  await choose(page, id(sheet, N.professional), memberId ? page.locator(`[data-testid="${N.professionalOption}"][data-id="${memberId}"]`) : page.getByTestId(N.professionalAny));
  await button(sheet, T.find).click();
  return id(sheet, N.time);
}
const holdsOf = (s: BookingSalon) => s.members.flatMap(m => liveRowsOf(s.created.id, m.id)).filter(r => r.state === 'held');

test('TC-F07-S03-005 AC-4: + New appointment opens the sheet with its intro, the customer\'s name and mobile, a day of open days only, the service with its price and its variants, the professionals who do it after No preference, and How did they book with Phone first; a missing name or mobile shows the prototype\'s message on its field and searches nothing', async ({ accounts, identity, operator, clock }) => {
  const s = await bookingSalon(identity, operator, clock, { people: 2 });
  const colour = await variantService(s, [s.members[1]]);
  await s.hours.save(s.salon, s.owner.tokens, week('09:00', '19:00', [weekdayOf(plusDays(s.today, 2))]));
  const { page } = await openAs(accounts, s.owner, BOOKINGS_ROUTE);
  const sheet = await openSheet(page);
  await expect(sheet).toContainText(T.sheetIntro);
  for (const label of [T.name, T.mobile, T.day, T.service, T.professional, T.source]) await expect(sheet.getByText(label, { exact: true }).first(), label).toBeVisible();
  await expect(sheet).toContainText(T.mobileHelp);
  await test.step('the day: open days only', async () => {
    await id(sheet, N.day).click();
    const calendar = id(page, N.calendar);
    if (plusDays(s.today, 2).slice(0, 7) === s.today.slice(0, 7)) await expect(calendarDayButton(calendar, plusDays(s.today, 2)), 'a closed weekday cannot be chosen').toBeDisabled();
    // Yesterday, or the previous month when yesterday is in it: a day before today cannot be reached.
    const past = plusDays(s.today, -1).slice(0, 7) === s.today.slice(0, 7)
      ? calendarDayButton(calendar, plusDays(s.today, -1)) : calendar.getByRole('button', { name: /previous month/i });
    await expect(past, 'nor a past day').toBeDisabled();
    await page.keyboard.press('Escape');
  });
  await test.step('the service, its price and variants; the professionals who do it', async () => {
    await id(sheet, N.service).click();
    await expect(page.getByRole('option', { name: new RegExp(`^${s.service.name}`) }).filter({ visible: true }), 'with its price').toContainText('4,500');
    await page.getByRole('option', { name: new RegExp(`^${colour.name}`) }).filter({ visible: true }).click();
    await id(sheet, N.variant).click();
    await expect(page.getByRole('option').filter({ visible: true }), 'its variants').toHaveText(colour.terms.variants.map(v => new RegExp(v.name)));
    await page.keyboard.press('Escape');
    await id(sheet, N.professional).click();
    await expect(page.getByRole('option').filter({ visible: true }), 'No preference, then who does it').toHaveText([T.noPreference, s.members[1].name]);
    await page.keyboard.press('Escape');
    await id(sheet, N.source).click();
    await expect(page.getByRole('option').filter({ visible: true }), 'the sources, Phone first').toHaveText(T.sourceLabels);
    await page.keyboard.press('Escape');
  });
  await test.step('a missing name or mobile', async () => {
    const asked: string[] = [];
    page.on('request', r => { if (r.url().includes('availability')) asked.push(r.url()); });
    await button(sheet, T.find).click();
    await expect(sheet.getByText(T.nameMissing)).toBeVisible();
    await expect(sheet.getByText(T.mobileMissing)).toBeVisible();
    await id(sheet, N.name).fill('Amaya Rajapaksa');
    await id(sheet, N.mobile).fill('0112345678');
    await button(sheet, T.find).click();
    await expect(sheet.getByText(T.mobileMissing), 'a landline is not a mobile').toBeVisible();
    expect(asked, 'no search ran').toEqual([]);
  });
});

test('TC-F07-S03-006 AC-5: Find free times shows the day\'s times as chips with who would take them, under the day\'s sentence and "Each time shows who would take it." for No preference; an empty answer says why in the prototype\'s words; changing a field clears the times', async ({ accounts, identity, operator, clock }) => {
  const s = await bookingSalon(identity, operator, clock, { people: 2 });
  const date = plusDays(s.today, 3);
  const free = await s.bookings.availability(s.salon, s.owner.tokens, { serviceId: s.service.id, date });
  const { page } = await openAs(accounts, s.owner, BOOKINGS_ROUTE);
  const sheet = await openSheet(page);
  const chips = await search(page, sheet, s, date);
  await expect(id(sheet, N.timesIntro)).toHaveText(T.timesIntro(shownDay(date)));
  await expect(sheet, 'one line for No preference').toContainText(T.noPreferenceLine);
  for (const retired of T.retiredStrategyLines) await expect(sheet, `no "${retired}"`).not.toContainText(retired);
  await expect(chips, 'every free time, in order, with who would take it').toHaveText(free.times.map(t => T.chip(shownTime(t.time), firstName(t.member.name))));
  await id(sheet, N.name).fill('Kavindi Herath');
  await expect(chips, 'changing a field clears them').toHaveCount(0);
  const reasons: [string, () => Promise<void>, string][] = [
    ['closed', async () => { await s.hours.add(s.salon, s.owner.tokens, plusDays(date, 1), 'Annual stock-take'); }, T.closed(shownDay(plusDays(date, 1)))],
    ['not working', async () => { for (const m of s.members) await new Absences(s.team).addLeave(s.salon, s.owner.tokens, m.id, leaveInput(plusDays(date, 2))); }, T.nobodyWorking(shownDay(plusDays(date, 2)))],
  ];
  for (const [n, [label, arrange, words]] of reasons.entries()) {
    await test.step(label, async () => {
      await arrange();
      const day = plusDays(date, n + 1);
      await chooseDay(page, sheet, day);
      await button(sheet, T.find).click();
      await expect(id(sheet, N.reason), label).toHaveText(words);
    });
  }
});

test('TC-F07-S03-007 AC-6: choosing a time holds it and shows "Holding this time" with a countdown from the server\'s expiry and the booking\'s facts; Confirm booking confirms it, closes the sheet and says "Booked for <day> at <time> with <first name>. Reference K7M-4QX."', async ({ accounts, identity, operator, clock }) => {
  const s = await bookingSalon(identity, operator, clock, { people: 2 });
  const date = plusDays(s.today, 2);
  const who = customer();
  const { page } = await openAs(accounts, s.owner, BOOKINGS_ROUTE);
  const sheet = await openSheet(page);
  const chips = await search(page, sheet, s, date, who);
  const label = await chips.first().innerText();
  await chips.first().click();
  const hold = id(sheet, N.hold);
  await expect(hold).toContainText(T.holding);
  await expect(hold).toContainText(T.holdNote);
  const [held] = holdsOf(s);
  expect(held, 'one hold, through the API').toBeTruthy();
  const stored = storedBooking(s.created.id, held.id);
  const countdown = id(sheet, N.countdown);
  await expect(countdown, 'a timer').toHaveAttribute('role', 'timer');
  const shown = await countdown.innerText();
  const [mm, ss] = shown.match(/(\d+):(\d\d)/)!.slice(1).map(Number);
  const serverLeft = (Date.parse(stored.hold_expires_at!) - Date.parse(await identity.now())) / 1000;
  expect(Math.abs(mm * 60 + ss - serverLeft), `${shown}: the server's expiry, not a local ten minutes`).toBeLessThan(5);
  for (const fact of [who.name, s.service.name, shownDay(date), T.sourceLabels[0]]) await expect(hold, fact).toContainText(fact);
  await button(sheet, T.confirm).click();
  await expect(id(page, N.sheet), 'the sheet closes').toHaveCount(0);
  const booking = (await s.bookings.get(s.salon, s.owner.tokens, held.id)).booking;
  expect(booking.state, 'confirmed, through the API').toBe('confirmed');
  expect(booking.channel, 'the source chosen').toBe('phone');
  await expect(page.getByText(T.booked(shownDay(date), label.split(' · ')[0], firstName(booking.member.name), booking.reference))).toBeVisible();
});

test('TC-F07-S03-008 AC-6: Choose another time releases the hold and searches again; closing the sheet with a hold releases it; a double click on a time places one hold', async ({ accounts, identity, operator, clock }) => {
  const s = await bookingSalon(identity, operator, clock, { people: 1 });
  const date = plusDays(s.today, 2);
  const { page } = await openAs(accounts, s.owner, BOOKINGS_ROUTE);
  let sheet = await openSheet(page);
  let chips = await search(page, sheet, s, date, customer(), s.members[0].id);
  await chips.first().dblclick();
  await expect(id(sheet, N.hold)).toBeVisible();
  expect(holdsOf(s).length, 'a double click places one hold').toBe(1);
  const first = holdsOf(s)[0].id;
  await button(sheet, T.another).click();
  await expect(chips.first(), 'the times again').toBeVisible();
  expect(holdsOf(s).map(h => h.id), 'released').not.toContain(first);
  await chips.nth(1).click();
  await expect(id(sheet, N.hold)).toBeVisible();
  expect(holdsOf(s).length, 'the new hold').toBe(1);
  await id(sheet, N.close).click();
  // A started form may ask before it closes (AC-11's leave guard); leaving is confirmed when it does.
  const leave = id(page, N.leaveConfirm);
  if (await leave.isVisible()) await leave.click();
  await expect(id(page, N.sheet)).toHaveCount(0);
  await expect.poll(() => holdsOf(s).length, { message: 'closing releases the hold' }).toBe(0);
  sheet = await openSheet(page);
  chips = await search(page, sheet, s, date, customer(), s.members[0].id);
  await expect(chips.first()).toBeVisible();
});

test('TC-F07-S03-009 AC-6: a time taken meanwhile says "That time was just taken. Choose another." with fresh times; confirming after the hold ran out says "The hold ran out and the time was released. Find free times again."', async ({ accounts, identity, operator, clock }) => {
  const s = await bookingSalon(identity, operator, clock, { people: 1 });
  const date = plusDays(s.today, 2);
  const [m] = s.members;
  const { page } = await openAs(accounts, s.owner, BOOKINGS_ROUTE);
  const sheet = await openSheet(page);
  const chips = await search(page, sheet, s, date, customer(), m.id);
  await test.step('taken meanwhile', async () => {
    const taken = (await chips.first().innerText()).split(' · ')[0];
    await s.bookings.place(s.salon, s.owner.tokens, holdInput(m.id, s.service.id, date, taken));
    await chips.first().click();
    await expect(id(sheet, N.refusal)).toHaveText(T.taken);
    await expect(chips.first(), 'fresh times').not.toHaveText(new RegExp(`^${taken}`));
  });
  await test.step('confirming after the hold ran out', async () => {
    await chips.first().click();
    await expect(id(sheet, N.hold)).toBeVisible();
    const mine = holdsOf(s).find(h => storedBooking(s.created.id, h.id).channel === 'phone' && storedBooking(s.created.id, h.id).customer_name !== '')!;
    // The server's clock passes the expiry while the sheet still counts down from the offset it read: Confirm at once.
    await clock.set(new Date(Date.parse(storedBooking(s.created.id, mine.id).hold_expires_at!) + 1_000).toISOString());
    await button(sheet, T.confirm).click();
    await expect(id(sheet, N.refusal), 'the API\'s hold_expired, in words').toHaveText(T.ranOut);
    expect((await s.bookings.getRaw(s.salon, (await identity.login(s.salon, s.owner.account)).tokens, mine.id)).status(), 'not a booking').toBe(404);
  });
});

test('TC-F07-S03-018 AC-6: when a held time\'s countdown reaches zero in the sheet it says "The hold ran out and the time was released. Find free times again." and the time is free for others', async ({ accounts, identity, operator, clock }) => {
  // The countdown runs on real time from the server's expiry, so this waits out the shortest hold the rules allow.
  test.setTimeout(9 * 60_000);
  const s = await bookingSalon(identity, operator, clock, { people: 1, rules: { holdMinutes: 5 } as never });
  const date = plusDays(s.today, 2);
  const { page } = await openAs(accounts, s.owner, BOOKINGS_ROUTE);
  const sheet = await openSheet(page);
  const chips = await search(page, sheet, s, date, customer(), s.members[0].id);
  const time = (await chips.first().innerText()).split(' · ')[0];
  await chips.first().click();
  await expect(id(sheet, N.countdown)).toHaveText(/^[45]:\d\d/);
  await expect(id(sheet, N.refusal), 'at zero').toHaveText(T.ranOut, { timeout: 6 * 60_000 });
  const owner = await identity.login(s.salon, s.owner.account);
  await s.bookings.place(s.salon, owner.tokens, holdInput(s.members[0].id, s.service.id, date, time));
});

/**
 * Holds back the BFF's answers to the requests `matches` picks until `let go` is called, so that the person can change
 * the form while one is on its way (PR #26's review). The request itself reaches the API at once; only its answer waits.
 */
async function holdBack(page: Page, matches: (url: URL, method: string) => boolean) {
  let letGo!: () => void;
  const gate = new Promise<void>(resolve => { letGo = resolve; });
  let delivered!: () => void;
  const answered = new Promise<void>(resolve => { delivered = resolve; });
  let caught = 0;
  await page.route(url => matches(url, ''), async route => {
    if (!matches(new URL(route.request().url()), route.request().method())) return route.fallback();
    caught++;
    const response = await route.fetch();
    await gate;
    await route.fulfill({ response });
    delivered();
  });
  return { letGo: () => letGo(), answered, caught: () => caught };
}
/** For `ms`, the sheet never shows a chip: a late answer would show them within moments of landing. */
async function noChipsFor(sheet: Locator, ms: number, label: string) {
  for (const end = Date.now() + ms; Date.now() < end; await new Promise(r => setTimeout(r, 100))) {
    expect(await id(sheet, N.time).count(), label).toBe(0);
    expect(await id(sheet, N.timesIntro).count(), `${label}: nor the times' sentence`).toBe(0);
  }
}

test('TC-F07-S03-019 AC-5: times asked for one day whose answer lands after the day was changed are never shown, so no time can be held from them for the day now chosen', async ({ accounts, identity, operator, clock }) => {
  // Found by PR #26's review (BUG-F07-002): "Changing any field clears the times" must hold for an answer still on its way.
  const s = await bookingSalon(identity, operator, clock, { people: 1 });
  const first = plusDays(s.today, 2);
  const second = plusDays(s.today, 3);
  const { page } = await openAs(accounts, s.owner, BOOKINGS_ROUTE);
  const sheet = await openSheet(page);
  const late = await holdBack(page, url => url.pathname.endsWith('/booking-availability') && url.searchParams.get('date') === first);
  const chips = await search(page, sheet, s, first, customer(), s.members[0].id);
  await expect.poll(late.caught, { message: 'the first day\'s search is on its way' }).toBe(1);
  await chooseDay(page, sheet, second);
  late.letGo();
  await late.answered;
  await noChipsFor(sheet, 2_000, `${shownDay(first)}'s times are not shown while the form says ${shownDay(second)}`);
  expect(holdsOf(s), 'nothing held').toEqual([]);
  await expect(chips).toHaveCount(0);
});

test('TC-F07-S03-020 AC-6: a hold whose answer lands after the sheet was closed, or after a field was changed, is released and never kept for a form that no longer matches it', async ({ accounts, identity, operator, clock }) => {
  // Found by PR #26's review (BUG-F07-003): "Closing the sheet with a hold releases it" and "Changing any field clears the
  // times" (gives back the held time) must hold for a hold still on its way.
  const s = await bookingSalon(identity, operator, clock, { people: 1 });
  const date = plusDays(s.today, 2);
  const soft = expect.configure({ soft: true });
  const { page } = await openAs(accounts, s.owner, BOOKINGS_ROUTE);
  const newHolds = (before: Set<string>) => holdsOf(s).filter(h => !before.has(h.id));

  await test.step('the sheet closed while the hold is on its way', async () => {
    const sheet = await openSheet(page);
    const chips = await search(page, sheet, s, date, customer(), s.members[0].id);
    await expect(chips.first()).toBeVisible();
    const before = new Set(holdsOf(s).map(h => h.id));
    const late = await holdBack(page, (url, method) => url.pathname.endsWith('/booking-place') && method !== 'GET');
    await chips.first().click();
    await expect.poll(() => newHolds(before).length, { message: 'the API placed the hold; its answer is held back' }).toBe(1);
    await id(sheet, N.close).click();
    const leave = id(page, N.leaveConfirm);
    if (await leave.isVisible()) await leave.click();
    await expect(id(page, N.sheet), 'the sheet closed').toHaveCount(0);
    late.letGo();
    await late.answered;
    await soft.poll(() => newHolds(before).length, { message: 'the late hold is released, not left on the time', timeout: 10_000 }).toBe(0);
    await page.unrouteAll({ behavior: 'wait' });
  });

  await test.step('a field changed while the hold is on its way', async () => {
    const sheet = await openSheet(page);
    const chips = await search(page, sheet, s, date, customer(), s.members[0].id);
    await expect(chips.first()).toBeVisible();
    const before = new Set(holdsOf(s).map(h => h.id));
    const late = await holdBack(page, (url, method) => url.pathname.endsWith('/booking-place') && method !== 'GET');
    await chips.first().click();
    await expect.poll(() => newHolds(before).length, { message: 'the API placed the hold; its answer is held back' }).toBe(1);
    await id(sheet, N.name).fill('Nethmi Bandara');
    late.letGo();
    await late.answered;
    await soft.poll(() => newHolds(before).length, { message: 'the late hold is released, not kept for the changed form', timeout: 10_000 }).toBe(0);
    await soft(id(sheet, N.hold), 'no "Holding this time" for a form that changed after the time was chosen').toHaveCount(0);
  });
});

test('TC-F07-S03-022 AC-6: when the answer to holding a time is lost on the way back, choosing the same time again sends the same key, gets the hold already placed and reaches Confirm with one hold and no orphan; after a definite refusal (slot_taken) the next try is a new attempt with a new key', async ({ accounts, identity, operator, clock }) => {
  // BUG-F07-006; F07-S01 AC-1 (the same key returns the same booking), F07-S03 AC-6, BKG-04.
  const s = await bookingSalon(identity, operator, clock, { people: 1 });
  const [m] = s.members;
  const date = plusDays(s.today, 2);
  const { page } = await openAs(accounts, s.owner, BOOKINGS_ROUTE);
  const keys: string[] = [];
  let lose = true;
  await page.route(url => url.pathname.endsWith('/booking-place'), async route => {
    if (route.request().method() === 'GET') return route.fallback();
    keys.push((route.request().postDataJSON() as { idempotencyKey: string }).idempotencyKey);
    if (!lose) return route.fallback();
    lose = false;
    await route.fetch(); // the request reaches the API, which places the hold
    await route.abort('connectionreset'); // and its answer never reaches the browser
  });
  const sheet = await openSheet(page);
  const chips = await search(page, sheet, s, date, customer(), m.id);

  await test.step('the answer is lost: the hold exists, the desk does not know it', async () => {
    await chips.first().click();
    await expect.poll(() => holdsOf(s).length, { message: 'the API placed the hold' }).toBe(1);
    await expect(id(sheet, N.hold), 'no "Holding this time" without an answer').toHaveCount(0);
  });

  await test.step('the same time again: the same key, the same hold, Confirm', async () => {
    const placed = holdsOf(s)[0].id;
    await chips.first().click();
    await expect.poll(() => keys.length, { message: 'the second try was sent' }).toBe(2);
    expect(keys, 'the second try sent the first try\'s key').toEqual([keys[0], keys[0]]);
    await expect(id(sheet, N.hold), 'Holding this time').toBeVisible();
    expect(holdsOf(s).map(h => h.id), 'one hold, the one already placed').toEqual([placed]);
    await button(sheet, T.confirm).click();
    await expect(id(page, N.sheet), 'the sheet closes on Confirm').toHaveCount(0);
    expect((await s.bookings.get(s.salon, s.owner.tokens, placed)).booking.state, 'the hold is the booking, confirmed').toBe('confirmed');
    expect(holdsOf(s), 'no orphan hold is left').toEqual([]);
  });

  await test.step('a definite refusal (slot_taken) is the end of that attempt: the next try has a new key', async () => {
    const next = await openSheet(page);
    const times = await search(page, next, s, date, customer(), m.id);
    const taken = (await times.first().innerText()).split(' · ')[0];
    const theirs = await s.bookings.place(s.salon, s.owner.tokens, holdInput(m.id, s.service.id, date, taken));
    const before = keys.length;
    await times.first().click();
    await expect(id(next, N.refusal)).toHaveText(T.taken);
    await s.bookings.release(s.salon, s.owner.tokens, theirs.id);
    await button(next, T.find).click();
    await expect(times.first()).toHaveText(new RegExp(`^${taken}`));
    await times.first().click();
    await expect(id(next, N.hold), 'held on a new attempt').toBeVisible();
    expect(keys.length - before, 'two tries of that time').toBe(2);
    expect(keys[before + 1], 'the try after the refusal has a key of its own').not.toBe(keys[before]);
  });
});
