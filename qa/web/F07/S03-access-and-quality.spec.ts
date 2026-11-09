/**
 * F07-S03 who sees and does what on the Bookings screens, and how they hold up: each role and scope, a support session,
 * keyboard-only use with focus kept in and returned from the sheets, the countdown announced at most once a minute, the
 * leave guard on a started form, every width down to 360 pixels, the accessibility check, the formats and words, a test
 * id on every control, and the web routes never reaching another salon.
 *
 * Expected results are the F07-S03 spec's AC-10 to AC-12 and "Risks and test focus" (the BFF never lets a request reach
 * another salon), and decision 108 (b). What the API refuses for each role is F07-S01's (TC-F07-S01-025 to -027); here the
 * screens offer exactly what each role may do. Prepared during the build; test ids are `PENDING_F07_S03`.
 */
import type { Page } from '@playwright/test';

import { bookingSalon, holdInput, staffAt } from '../../shared/booking';
import {
  BOOKINGS_ROUTE, DESIGN_WIDTHS, PENDING_F07_S03 as W, S03_TEXT as T, button, id, openAs, settled, supportBrowser, test, expect,
} from '../../shared/booking-browser';
import { fit, pressables, seriousViolations, stringsShown } from '../../shared/page-checks';
import { plusDays } from '../../shared/salon-hours';

test.describe.configure({ timeout: 300_000 });

const rowsOf = (page: Page) => id(page, W.page.row).or(id(page, W.page.item));
async function ready(page: Page) {
  await expect(id(page, W.page.loading)).toHaveCount(0);
  await expect(id(page, W.page.heading)).toBeVisible();
}

test('TC-F07-S03-013 AC-10: a Professional sees only their own bookings with no professional filter and no New appointment and can only mark arrived and start; granted, they see the salon\'s and still cannot book or cancel; a support session sees everything read-only', async ({ accounts, browser, identity, operator, clock }) => {
  const s = await bookingSalon(identity, operator, clock, { people: 2 });
  const [mine, theirs] = await Promise.all(s.members.map(async m => s.bookings.confirm(s.salon, s.owner.tokens, (await s.bookings.place(s.salon, s.owner.tokens, holdInput(m.id, s.service.id, s.today, '10:00'))).id)));
  await test.step('own scope', async () => {
    const { page } = await openAs(accounts, await staffAt(s, 'PROFESSIONAL', { linkTo: s.members[0] }), BOOKINGS_ROUTE);
    await ready(page);
    await expect(rowsOf(page), 'only their own').toHaveCount(1);
    await expect(rowsOf(page).first()).toContainText(mine.reference);
    await expect(page.getByTestId(W.page.professional), 'no professional filter').toHaveCount(0);
    await expect(button(page, T.newAppointment), 'no New appointment').toHaveCount(0);
    await rowsOf(page).first().click();
    const sheet = id(page, W.appointment.sheet);
    for (const a of ['cancel', 'no-show', 'undo-no-show', 'release']) await expect(sheet.getByTestId(W.appointment.action(a)), `no ${a}`).toHaveCount(0);
    await sheet.getByTestId(W.appointment.action('arrive')).click();
    await expect(page.getByText(T.done.arrive)).toBeVisible();
    await sheet.getByTestId(W.appointment.action('start')).click();
    await expect(page.getByText(T.done.start)).toBeVisible();
  });
  await test.step('granted the salon\'s calendar', async () => {
    const { page } = await openAs(accounts, await staffAt(s, 'PROFESSIONAL', { granted: true }), BOOKINGS_ROUTE);
    await ready(page);
    await expect(rowsOf(page), 'the salon\'s').toHaveCount(2);
    await expect(button(page, T.newAppointment)).toHaveCount(0);
    await rowsOf(page).filter({ hasText: theirs.reference }).click();
    const sheet = id(page, W.appointment.sheet);
    for (const a of ['cancel', 'no-show', 'release', 'arrive', 'start']) await expect(sheet.getByTestId(W.appointment.action(a)), `no ${a} on another's`).toHaveCount(0);
  });
  await test.step('a support session', async () => {
    const context = await supportBrowser(browser, operator, s.created, s.salon);
    const page = await context.newPage();
    await page.goto(BOOKINGS_ROUTE);
    await ready(page);
    await expect(rowsOf(page), 'everything').toHaveCount(2);
    await expect(button(page, T.newAppointment)).toHaveCount(0);
    await rowsOf(page).filter({ hasText: theirs.reference }).click();
    for (const a of ['arrive', 'start', 'cancel', 'no-show', 'release']) await expect(id(page, W.appointment.sheet).getByTestId(W.appointment.action(a)), `read-only: no ${a}`).toHaveCount(0);
    await context.close();
  });
});

test('TC-F07-S03-014 AC-11: by keyboard alone the desk opens New appointment, fills it, finds and holds a time and confirms; focus stays in the sheet and returns to its opener; the countdown is a timer announced at most once a minute; leaving a started form asks first', async ({ accounts, identity, operator, clock }) => {
  const s = await bookingSalon(identity, operator, clock, { people: 1 });
  const { page } = await openAs(accounts, s.owner, BOOKINGS_ROUTE);
  await ready(page);
  const opener = button(page, T.newAppointment);
  await opener.focus();
  await page.keyboard.press('Enter');
  const sheet = id(page, W.sheet.sheet);
  await expect(sheet).toBeVisible();
  await test.step('focus stays in the sheet', async () => {
    for (let n = 0; n < 40; n++) {
      await page.keyboard.press('Tab');
      expect(await sheet.evaluate(el => el.contains(document.activeElement)), `Tab ${n + 1}: still in the sheet`).toBe(true);
    }
  });
  await test.step('the form by keys', async () => {
    await id(sheet, W.sheet.name).focus();
    await page.keyboard.type('Sewwandi Perera');
    await page.keyboard.press('Tab');
    await page.keyboard.type('0771239876');
    for (const field of [W.sheet.service, W.sheet.professional]) {
      await id(sheet, field).focus();
      await page.keyboard.press('Enter');
      await page.keyboard.press('ArrowDown');
      await page.keyboard.press('Enter');
    }
    await id(sheet, W.sheet.day).focus();
    await page.keyboard.press('Enter');
    await page.keyboard.press('ArrowRight');
    await page.keyboard.press('Enter');
    await button(sheet, T.find).focus();
    await page.keyboard.press('Enter');
    await id(sheet, W.sheet.time).first().focus();
    await page.keyboard.press('Enter');
    await expect(id(sheet, W.sheet.hold)).toBeVisible();
  });
  await test.step('the countdown is a polite timer, not read out every second', async () => {
    const timer = id(sheet, W.sheet.countdown);
    await expect(timer).toHaveAttribute('role', 'timer');
    const live = await timer.evaluate(el => el.getAttribute('aria-live') ?? (el.closest('[aria-live]')?.getAttribute('aria-live') ?? 'off'));
    expect(['off', 'polite'], 'not assertive').toContain(live);
    const announced: string[] = [];
    await page.exposeFunction('qaAnnounced', (text: string) => { announced.push(text); });
    await page.evaluate(() => {
      const regions = Array.from(document.querySelectorAll('[aria-live="polite"],[aria-live="assertive"],[role="status"],[role="alert"]'));
      for (const r of regions) new MutationObserver(() => (window as unknown as { qaAnnounced: (t: string) => void }).qaAnnounced(r.textContent ?? '')).observe(r, { childList: true, subtree: true, characterData: true });
    });
    // The wait is the observation: a countdown read out every second would announce about five times in it.
    await page.waitForTimeout(5_000);
    expect(announced.filter(t => /\d+:\d\d/.test(t)).length, 'no announcement in five seconds of countdown').toBe(0);
  });
  await test.step('confirm by keys, focus returns', async () => {
    await button(sheet, T.confirm).focus();
    await page.keyboard.press('Enter');
    await expect(sheet).toHaveCount(0);
    await expect(opener, 'focus returns to New appointment').toBeFocused();
  });
  await test.step('leaving a started form asks first', async () => {
    await opener.click();
    await id(id(page, W.sheet.sheet), W.sheet.name).fill('Thilini Jayasuriya');
    await page.getByRole('link', { name: 'Team' }).filter({ visible: true }).first().click();
    await expect(id(page, W.sheet.leaveDialog)).toBeVisible();
    await id(page, W.sheet.leaveStay).click();
    await expect(id(page, W.sheet.sheet), 'kept').toBeVisible();
    await expect(id(id(page, W.sheet.sheet), W.sheet.name)).toHaveValue('Thilini Jayasuriya');
  });
});

test('TC-F07-S03-015 AC-11: the page, the New appointment sheet and the appointment sheet fit every width of the design standards without sideways scrolling, and pass the accessibility check with no serious or critical violation', async ({ accounts, identity, operator, clock }) => {
  const s = await bookingSalon(identity, operator, clock, { people: 2 });
  const booking = await s.bookings.confirm(s.salon, s.owner.tokens, (await s.bookings.place(s.salon, s.owner.tokens, holdInput(s.members[0].id, s.service.id, s.today, '10:00', { customer: { name: 'Nanayakkara Wanigasekara Basnayake Dharmasena', mobile: '0771239876' } }))).id);
  for (const viewport of DESIGN_WIDTHS) {
    await test.step(`${viewport.width} pixels`, async () => {
      const { page } = await openAs(accounts, s.owner, BOOKINGS_ROUTE, viewport.width);
      await ready(page);
      const check = async (where: string) => {
        await settled(page);
        const overflow = await fit(page);
        expect(overflow.pageScrolls, `${viewport.width}, ${where}: no sideways scrolling`).toBe(false);
        expect(overflow.clipped, `${viewport.width}, ${where}: nothing clipped`).toEqual([]);
        if ([360, 768, 1280].includes(viewport.width)) expect(await seriousViolations(page), `${viewport.width}, ${where}: accessibility`).toEqual([]);
      };
      await check('the page');
      await rowsOf(page).filter({ hasText: booking.reference }).click();
      await expect(id(page, W.appointment.sheet)).toBeVisible();
      await check('the appointment sheet');
      await page.keyboard.press('Escape');
      await button(page, T.newAppointment).click();
      await expect(id(page, W.sheet.sheet)).toBeVisible();
      await check('New appointment');
    });
  }
});

test('TC-F07-S03-016 AC-12: words have curly apostrophes and the right singulars, times and dates use the workspace\'s formats and mobiles the national one, and every control a person can press or type into has a test id', async ({ accounts, identity, operator, clock }) => {
  const s = await bookingSalon(identity, operator, clock, { people: 1 });
  await s.bookings.place(s.salon, s.owner.tokens, holdInput(s.members[0].id, s.service.id, s.today, '10:00'));
  const { page } = await openAs(accounts, s.owner, BOOKINGS_ROUTE);
  await ready(page);
  const surfaces: [string, () => Promise<void>][] = [
    ['the page', async () => undefined],
    ['the appointment sheet', async () => { await rowsOf(page).first().click(); await expect(id(page, W.appointment.sheet)).toBeVisible(); }],
    ['New appointment', async () => { await page.keyboard.press('Escape'); await button(page, T.newAppointment).click(); await expect(id(page, W.sheet.sheet)).toBeVisible(); }],
  ];
  for (const [where, open] of surfaces) {
    await test.step(where, async () => {
      await open();
      const shown = await stringsShown(page);
      expect(shown.filter(t => /\w'\w/.test(t)), `${where}: no straight apostrophe in a word`).toEqual([]);
      expect(shown.filter(t => /\b1 bookings\b/.test(t)), `${where}: singulars`).toEqual([]);
      expect(shown.filter(t => /\b\d{1,2}:\d\d\s?(AM|PM)\b/i.test(t)), `${where}: the 24-hour clock (decision 103)`).toEqual([]);
      const missing = (await pressables(page)).filter(p => !p.testId);
      expect(missing, `${where}: every control has a test id`).toEqual([]);
    });
  }
});

test('TC-F07-S03-017 AC-10: a page of one salon, its signed-in session aimed at another salon\'s booking ids and web address, shows and changes nothing of the other salon', async ({ accounts, identity, operator, clock }) => {
  const a = await bookingSalon(identity, operator, clock, { people: 1 });
  const b = await bookingSalon(identity, operator, clock, { people: 1, keepClock: true });
  const theirs = await b.bookings.confirm(b.salon, b.owner.tokens, (await b.bookings.place(b.salon, b.owner.tokens, holdInput(b.members[0].id, b.service.id, plusDays(a.today, 1), '10:00'))).id);
  const { context, page } = await openAs(accounts, a.owner, `${BOOKINGS_ROUTE}?${W.query.day}=${plusDays(a.today, 1)}&${W.query.q}=${theirs.reference}`);
  await ready(page);
  await expect(rowsOf(page), 'the other salon\'s booking is not on this salon\'s page').toHaveCount(0);
  const received: string[] = [];
  page.on('response', async r => { if (new URL(r.url()).pathname.includes('booking')) received.push(await r.text().catch(() => '')); });
  await page.goto(`${BOOKINGS_ROUTE}?${W.query.booking}=${theirs.id}`);
  await ready(page);
  expect(received.join('\n'), 'nothing of the other salon reached the browser').not.toContain(theirs.reference);
  const cookies = await context.cookies();
  expect(cookies.every(c => !c.domain.includes(b.salon.hostname)), 'no session for the other salon').toBe(true);
  expect((await b.bookings.get(b.salon, b.owner.tokens, theirs.id)).booking.state, 'unchanged').toBe('confirmed');
});
