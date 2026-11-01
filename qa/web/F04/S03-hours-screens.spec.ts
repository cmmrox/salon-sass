/**
 * F04-S03 the Hours & closures tab, the Add a closure sheet and the console's Holiday calendar in the
 * browser: the suggested week and Save hours, the field error beside a day, reviewing and accepting a
 * published year, adding and reopening a closure, importing, refusing and publishing a year, who
 * sees what, the loading, saved, conflict, unavailable and empty states, every width of the matrix,
 * keyboard use and accessibility.
 *
 * Expected results are the F04-S03 spec's (acceptance criteria, "UI and UX", "Corrections at close")
 * and docs/design/ui-ux-standards.md; words come from the translation file and elements from the
 * test ids the handoff names. Rules the API cases prove (every validation row, every role on every
 * endpoint, priority, time-zone edges) are not proved again here: each case checks what the person
 * sees and checks persistence once through the API. The screens take "today" from the browser's
 * clock in the salon's zone, so a case that sets the API's test clock sets the browser's to the
 * same instant.
 */
import type { BrowserContext, Locator, Page } from '@playwright/test';

import { consoleContext, consoleUrl, expectFits, expectSheetFocus } from '../../shared/console-browser';
import { DESIGN_WIDTHS } from '../../shared/design-reference';
import { webUrl } from '../../shared/env';
import { test as browserTest, expect, type AccountBrowser } from '../../shared/identity-browser';
import type { Actor, Role } from '../../shared/identity';
import { Operator, TestClock, ownerSession, supportJson, type SupportStart } from '../../shared/operator';
import { drawnContrast, fit, seriousViolations } from '../../shared/page-checks';
import {
  Calendar, DAYS, Hours, SHARED, UNPUBLISHED_YEAR, csv, hoursSnapshot, plusDays, stored, unique, week, type Day,
} from '../../shared/salon-hours';
import { message } from '../../shared/strings';
import { alpha, beta, salonUrl, type Salon } from '../../shared/tenants';

const test = browserTest.extend<{ operator: Operator; clock: TestClock; hours: Hours; calendar: Calendar }>({
  operator: async ({ identity }, use) => {
    const operator = new Operator(identity);
    await operator.resetFixtures();
    try { await use(operator); } finally { await operator.resetToBaseline(); }
  },
  clock: async ({ identity }, use) => {
    const clock = new TestClock(identity);
    try { await use(clock); } finally { await clock.real(); }
  },
  hours: async ({ identity }, use) => { await use(new Hours(identity)); },
  calendar: async ({ identity }, use) => {
    const calendar = new Calendar(identity);
    try { await use(calendar); } finally { await calendar.dispose(); }
  },
});
test.describe.configure({ timeout: 180_000 });

const HOURS = '/workspace/settings/hours';
const id = (page: Page, name: string) => page.getByTestId(name);

/** A translation with its {placeholders} and plural forms filled, as next-intl shows it in English. */
function words(key: string, values: Record<string, string | number> = {}): string {
  let text = message(key);
  text = text.replace(/\{(\w+), plural,((?:\s*(?:=\d+|one|other)\s*\{[^{}]*\})+)\s*\}/g, (_, name: string, forms: string) => {
    const n = Number(values[name]);
    const options = Object.fromEntries([...forms.matchAll(/(=\d+|one|other)\s*\{([^{}]*)\}/g)].map(m => [m[1], m[2]]));
    return (options[`=${n}`] ?? (n === 1 ? options.one : undefined) ?? options.other).replace(/#/g, String(n));
  });
  return text.replace(/\{(\w+)\}/g, (_, name: string) => String(values[name]));
}

/**
 * A Calendar day button by its date, as calendar-labels.ts names it (the date written out in the
 * configured locale, "Today, " before today and ", selected" after the chosen day).
 */
function calendarDay(scope: Locator, date: string): Locator {
  const written = new Intl.DateTimeFormat('en-LK', { weekday: 'long', day: 'numeric', month: 'long', year: 'numeric', timeZone: 'UTC' })
    .format(new Date(`${date}T00:00:00Z`));
  return scope.getByRole('button', { name: new RegExp(`^(Today, )?${written}(, selected)?$`) });
}

/** Every scan of a case is reported, not only the first that fails (each is a separate screen state). */
async function accessible(page: Page, label: string) {
  // Scan the settled screen, not a frame of a transition (a toast fading in measures as low contrast).
  await page.waitForFunction(() => document.getAnimations().every(a => a.playState !== 'running'));
  expect.soft(await seriousViolations(page), `${label}: no serious or critical violation`).toEqual([]);
}

/** Waits until no animation runs, so a scan or a measurement reads the settled screen, not a frame of a transition. */
async function settled(page: Page) {
  await page.waitForFunction(() => document.getAnimations().every(a => a.playState !== 'running'));
}

/** The UTC instant of a Colombo local time on a date (UTC+05:30). */
const colombo = (date: string, time = '10:00') => {
  const [h, m] = time.split(':').map(Number);
  return new Date(Date.parse(`${date}T00:00:00Z`) + ((h * 60 + m) - 330) * 60_000).toISOString();
};

/** Opens the tab signed in as the actor, the browser's clock at `at` when given, and waits for it to load. */
async function openHours(accounts: AccountBrowser, actor: Actor, options: { width?: number; salon?: Salon; at?: string } = {}): Promise<{ context: BrowserContext; page: Page }> {
  const context = await accounts.context(options.salon ?? actor.salon, [actor], { width: options.width ?? 1280 });
  if (options.at) await context.clock.setSystemTime(new Date(options.at));
  const page = await accounts.open(context, HOURS);
  await ready(page);
  return { context, page };
}

async function ready(page: Page) {
  await expect(id(page, 'hours-tab')).toBeVisible();
  await expect(id(page, 'hours-loading')).toHaveCount(0);
}

/** Chooses an option of a shadcn Select by the trigger's and the option's test ids. */
async function choose(page: Page, trigger: string, option: string) {
  await id(page, trigger).click();
  await id(page, option).click();
  await expect(id(page, option)).toHaveCount(0);
}

/** A salon the operator creates now, with its Owner signed in. */
async function newSalon(operator: Operator, accounts: AccountBrowser) {
  const admin = await operator.actor();
  const plan = await operator.plan(admin);
  const { input, created, salon } = await operator.salon(admin, plan.id);
  const owner = await ownerSession(accounts.identity, salon, input);
  return { admin, created, salon, owner };
}

/** The shared published year (shared/salon-hours.ts), the API's clock at 10:00 in Colombo on its "today". */
async function sharedYear(operator: Operator, calendar: Calendar, clock: TestClock) {
  const at = colombo(SHARED.today);
  await clock.set(at);
  await calendar.shared(await operator.actor());
  return { ...SHARED, at };
}

test('TC-F04-S03-022 AC-1: the Owner finds Hours & closures between Profile and Branding, sees the suggested week marked "Not saved yet", and Save hours stores the week at once', async ({ accounts, operator, hours }) => {
  const { salon, owner } = await newSalon(operator, accounts);
  const { page } = await openHours(accounts, owner);
  await test.step('the tab bar and the intro', async () => {
    const boxes = await Promise.all(['profile', 'hours', 'branding'].map(t => id(page, `settings-tab-${t}`).boundingBox()));
    expect(boxes.every(Boolean), 'three tabs').toBe(true);
    expect(boxes[0]!.x < boxes[1]!.x && boxes[1]!.x < boxes[2]!.x, 'Profile, Hours & closures, Branding in that order').toBe(true);
    await expect(id(page, 'settings-tab-hours')).toHaveText(message('settings.tabs.hours'));
    await expect(id(page, 'settings-tab-hours')).toHaveAttribute('aria-selected', 'true');
    await expect(id(page, 'settings-intro'), 'the intro says hours save at once').toHaveText(message('settings.introHours'));
  });
  await test.step('the suggested week, not saved', async () => {
    await expect(id(page, 'hours-not-saved')).toHaveText(message('settings.hours.notSaved'));
    for (const day of DAYS) {
      const open = day !== 'sunday';
      await expect(id(page, `hours-${day}-open`), `${day} toggle`).toHaveAttribute('aria-pressed', String(open));
      if (open) {
        await expect(id(page, `hours-${day}-opens`)).toContainText('09:00');
        await expect(id(page, `hours-${day}-closes`)).toContainText(day === 'saturday' ? '19:00' : '18:00');
      } else {
        await expect(id(page, `hours-${day}-closed`)).toBeVisible();
      }
    }
    expect((await hours.settings(salon, owner.tokens)).weekly, 'nothing is stored until the first save').toBeNull();
  });
  await choose(page, 'hours-monday-opens', 'hours-monday-opens-option-0830');
  await choose(page, 'hours-friday-closes', 'hours-friday-closes-option-2045');
  await id(page, 'hours-wednesday-open').click();
  await expect(id(page, 'hours-wednesday-closed')).toBeVisible();
  await id(page, 'hours-save').click();
  await expect(page.getByText(message('settings.hoursTab.toast.hoursSaved'))).toBeVisible();
  await expect(id(page, 'hours-not-saved'), 'saved').toHaveCount(0);
  const expected = stored(week('09:00', '18:00', ['sunday', 'wednesday'], { monday: ['08:30', '18:00'], friday: ['09:00', '20:45'], saturday: ['09:00', '19:00'] }));
  expect((await hours.settings(salon, owner.tokens)).weekly, 'the week is stored').toEqual(expected);
  expect((await hours.published(salon)).weekly, 'and public at once').toEqual(expected);
  await page.reload();
  await ready(page);
  await expect(id(page, 'hours-monday-opens'), 'a reload shows the saved week').toContainText('08:30');
  await expect(id(page, 'hours-wednesday-open')).toHaveAttribute('aria-pressed', 'false');
});

test('TC-F04-S03-023 AC-1: a closing time before the opening time is shown beside that day and nothing is saved', async ({ accounts, hours }) => {
  const manager = await accounts.identity.staff(alpha, 'MANAGER');
  await hours.save(alpha, manager.tokens, week());
  const { page } = await openHours(accounts, manager);
  await choose(page, 'hours-tuesday-closes', 'hours-tuesday-closes-option-0800');
  const before = hoursSnapshot(alpha.id);
  await id(page, 'hours-save').click();
  const error = id(page, 'hours-tuesday-error');
  await expect(error).toBeVisible();
  await expect(error).toHaveText(message('settings.hours.closesAfterOpens'));
  const row = (await id(page, 'hours-day-tuesday').boundingBox())!;
  const shown = (await error.boundingBox())!;
  expect(shown.y >= row.y - 1 && shown.y + shown.height <= row.y + row.height + 1, 'the message is inside Tuesday\'s row').toBe(true);
  await expect(id(page, 'hours-monday-error'), 'no message on another day').toHaveCount(0);
  expect(hoursSnapshot(alpha.id), 'nothing saved').toBe(before);
});

test('TC-F04-S03-024 AC-4, AC-5: a published year shows "To review" with its coming days; choosing Closed, Open as usual and Special hours and pressing Accept these choices shows "Reviewed", and a later choice saves at once', async ({ accounts, operator, calendar, clock, hours }) => {
  const { year, at, past, days } = await sharedYear(operator, calendar, clock);
  const [closed, open, special, untouched] = days;
  const { salon, owner } = await newSalon(operator, accounts);
  const { page } = await openHours(accounts, owner, { at });
  await expect(id(page, 'holidays-card')).toHaveAttribute('data-year', String(year));
  await expect(id(page, 'holidays-card')).toContainText(words('settings.holidays.title', { year }));
  await expect(id(page, 'holidays-status')).toHaveText(message('settings.holidays.toReview'));
  await expect(id(page, `holiday-${past.date}`), 'a past day is not listed').toHaveCount(0);
  for (const day of days) {
    await expect(id(page, `holiday-${day.date}`)).toContainText(day.name);
    await expect(id(page, `holiday-${day.date}`)).toContainText(message(`settings.holidays.kinds.${day.kind}`));
  }
  await choose(page, `holiday-${closed.date}-choice`, `holiday-${closed.date}-choice-closed`);
  await choose(page, `holiday-${open.date}-choice`, `holiday-${open.date}-choice-open`);
  await choose(page, `holiday-${special.date}-choice`, `holiday-${special.date}-choice-special`);
  await choose(page, `holiday-${special.date}-opens`, `holiday-${special.date}-opens-option-1000`);
  await choose(page, `holiday-${special.date}-closes`, `holiday-${special.date}-closes-option-1400`);
  await id(page, 'holidays-accept').click();
  await expect(page.getByText(message('settings.hoursTab.toast.holidaysAccepted'))).toBeVisible();
  await expect(id(page, 'holidays-status')).toHaveText(message('settings.holidays.reviewed'));
  const seen = await hours.year(salon, owner.tokens, year);
  expect(seen.reviewed).toBe(true);
  expect(seen.days.filter(d => !d.past).map(d => [d.date, d.choice, d.opens, d.closes]), 'the choices made, and Closed where none was changed').toEqual([
    [closed.date, 'closed', null, null], [open.date, 'open', null, null], [special.date, 'special', '10:00', '14:00'], [untouched.date, 'closed', null, null],
  ]);
  await choose(page, `holiday-${open.date}-choice`, `holiday-${open.date}-choice-closed`);
  await expect(page.getByText(message('settings.hoursTab.toast.holidaySaved'))).toBeVisible();
  expect((await hours.year(salon, owner.tokens, year)).days.find(d => d.date === open.date)!.choice, 'the later choice is saved').toBe('closed');
});

test('TC-F04-S03-025 AC-7: + Add closure opens the sheet with past days disabled and "No bookings on this day"; Close the salon this day lists the closure, a second closure on that day is refused beside the date, and Reopen removes it', async ({ accounts, operator, clock, hours }) => {
  const today = '2027-04-10';
  await clock.set(colombo(today));
  const { salon, owner } = await newSalon(operator, accounts);
  const { page } = await openHours(accounts, owner, { at: colombo(today) });
  await expect(id(page, 'closures-empty')).toBeVisible();
  const date = plusDays(today, 3);
  const reason = unique('Staff training');
  const pick = async (d: string) => {
    await id(page, 'closure-date').click();
    await expect(id(page, 'closure-calendar')).toBeVisible();
    const day = calendarDay(id(page, 'closure-calendar'), d);
    // Choosing the day already chosen would clear it (the sheet keeps the last day it was given).
    if (!/selected$/.test(await day.getAttribute('aria-label') ?? await day.innerText())) await day.click();
    else await page.keyboard.press('Escape');
  };
  await test.step('the sheet: past days disabled, the bookings on the day', async () => {
    await id(page, 'closure-add').click();
    await expect(id(page, 'closure-sheet')).toBeVisible();
    await expect(id(page, 'closure-confirm'), 'nothing to close before a day is chosen').toBeDisabled();
    await id(page, 'closure-date').click();
    await expect(calendarDay(id(page, 'closure-calendar'), plusDays(today, -1)), 'yesterday is disabled').toBeDisabled();
    await expect(calendarDay(id(page, 'closure-calendar'), today), 'today can be chosen').toBeEnabled();
    await page.keyboard.press('Escape');
    await pick(date);
    await expect(id(page, 'closure-no-bookings')).toHaveText(message('settings.closures.noBookings'));
    await id(page, 'closure-reason').fill(reason);
    await id(page, 'closure-confirm').click();
    await expect(page.getByText(message('settings.hoursTab.toast.closed'))).toBeVisible();
    await expect(id(page, 'closure-sheet')).toBeHidden();
  });
  const row = page.locator(`[data-testid="closure-row"][data-date="${date}"]`);
  await expect(row).toBeVisible();
  await expect(row, 'the team sees the reason').toContainText(reason);
  expect((await hours.closures(salon, owner.tokens)).map(c => [c.date, c.reason]), 'stored').toEqual([[date, reason]]);
  await test.step('a second closure on the same day is refused beside the date', async () => {
    await id(page, 'closure-add').click();
    await pick(date);
    await id(page, 'closure-confirm').click();
    await expect(id(page, 'closure-date-error')).toHaveText(message('settings.closures.taken'));
    await expect(id(page, 'closure-sheet'), 'the sheet stays open').toBeVisible();
    await page.keyboard.press('Escape');
    await expect(id(page, 'closure-sheet')).toBeHidden();
    expect((await hours.closures(salon, owner.tokens)).length, 'still one closure').toBe(1);
  });
  await test.step('Reopen removes the closure', async () => {
    await row.getByTestId('closure-reopen').click();
    await expect(page.getByText(message('settings.hoursTab.toast.reopened'))).toBeVisible();
    await expect(row).toHaveCount(0);
    await expect(id(page, 'closures-empty')).toBeVisible();
    expect(await hours.closures(salon, owner.tokens), 'removed').toEqual([]);
  });
});

test('TC-F04-S03-026 AC-3, AC-4, AC-6: in the console the Admin sees a refused CSV line by line, imports a year, publishes it after the prototype\'s confirmation and sees a revision\'s counts; Support sees no control to change it', async ({ accounts, browser, operator, calendar }) => {
  const admin = await operator.actor();
  const year = await calendar.freshYear(admin);
  const context = await consoleContext(browser, admin);
  const page = await context.newPage();
  await page.goto(consoleUrl('/console/holidays'));
  await expect(id(page, 'console-holidays-title')).toHaveText(message('console.holidays.title'));
  const row = page.locator(`[data-testid="console-holiday-year-row"][data-year="${year}"]`);
  await expect(row).toBeVisible();
  await expect(row.getByTestId('console-holiday-state')).toHaveAttribute('data-state', 'draft');
  await page.goto(consoleUrl(`/console/holidays/${year}`));
  await expect(id(page, 'console-holiday-year-title')).toHaveText(words('console.holidays.yearTitle', { year }));
  const upload = (name: string, body: string) => id(page, 'console-holiday-file').setInputFiles({ name, mimeType: 'text/csv', buffer: Buffer.from(body) });
  await test.step('a file with bad lines is refused, line by line, and nothing changes', async () => {
    await upload('bad.csv', `date,name,kind\n${year}-01-13,Duruthu Poya,poya\n${year + 1}-01-01,Outside,public\n${year}-02-04,Independence Day,festival\n`);
    await expect(id(page, 'console-holiday-import-refused')).toContainText(message('console.holidays.importRefused'));
    await expect(id(page, 'console-holiday-bad-line')).toHaveCount(2);
    expect(await id(page, 'console-holiday-bad-line').evaluateAll(els => els.map(e => e.getAttribute('data-line'))), 'lines 3 and 4').toEqual(['3', '4']);
    expect((await calendar.year(admin, year)).days.map(d => d.date), 'the draft is as it was').toEqual([`${year}-12-31`]);
  });
  const days: Day[] = [
    { date: `${year}-01-13`, name: 'Duruthu Full Moon Poya Day', kind: 'poya' },
    { date: `${year}-02-04`, name: 'Independence Day', kind: 'public' },
    { date: `${year}-04-14`, name: 'Sinhala and Tamil New Year Day', kind: 'mercantile' },
  ];
  await test.step('a valid file replaces the draft', async () => {
    await upload('good.csv', csv(days));
    await expect(id(page, 'console-holiday-done')).toHaveText(words('console.holidays.imported', { days: 3 }));
    await expect(id(page, 'console-holiday-import-refused')).toHaveCount(0);
    await expect(id(page, 'console-holiday-day')).toHaveCount(3);
    for (const day of days) await expect(page.locator(`[data-testid="console-holiday-day"][data-date="${day.date}"]`)).toContainText(day.name);
  });
  await test.step('Publish asks in the prototype\'s words; Cancel publishes nothing, Publish to salons publishes', async () => {
    await id(page, 'console-holiday-publish').click();
    const dialog = id(page, 'console-holiday-publish-dialog');
    await expect(dialog).toContainText(words('console.holidays.publishTitle', { year }));
    await expect(dialog).toContainText(message('console.holidays.publishBody'));
    await expect(id(page, 'console-holiday-publish-confirm')).toHaveText(message('console.holidays.publishConfirm'));
    await id(page, 'console-holiday-publish-cancel').click();
    await expect(dialog).toHaveCount(0);
    expect((await calendar.year(admin, year)).revision, 'cancelled: not published').toBe(0);
    await id(page, 'console-holiday-publish').click();
    await id(page, 'console-holiday-publish-confirm').click();
    await expect(id(page, 'console-holiday-done')).toHaveText(message('console.holidays.published'));
    expect((await calendar.year(admin, year)).revision, 'published').toBe(1);
  });
  await test.step('a revision shows what it adds, changes and withdraws', async () => {
    await calendar.change(admin, year, days[1].date, { name: 'Independence Day (revised)', kind: 'public' });
    await calendar.remove(admin, year, days[2].date);
    await calendar.add(admin, year, { date: `${year}-05-12`, name: 'Vesak Full Moon Poya Day', kind: 'poya' });
    await page.reload();
    await expect(id(page, 'console-holiday-counts')).toHaveText(words('console.holidays.counts', { added: 1, changed: 1, withdrawn: 1 }));
    await expect(page.locator(`[data-testid="console-holiday-day"][data-date="${year}-05-12"]`).getByTestId('console-holiday-day-change')).toHaveText(message('console.holidays.changes.added'));
    await expect(page.locator(`[data-testid="console-holiday-day"][data-date="${days[1].date}"]`).getByTestId('console-holiday-day-change')).toHaveText(message('console.holidays.changes.changed'));
    await expect(id(page, 'console-holiday-withdrawn')).toContainText(days[2].name);
  });
  await context.close();
  await test.step('Support reads the year with no control to change it', async () => {
    const support = await operator.actor('OPERATOR_SUPPORT');
    const supportContext = await consoleContext(browser, support);
    const read = await supportContext.newPage();
    await read.goto(consoleUrl(`/console/holidays/${year}`));
    await expect(id(read, 'console-holiday-day')).toHaveCount(3);
    for (const control of ['console-holiday-choose-file', 'console-holiday-file', 'console-holiday-add', 'console-holiday-day-edit', 'console-holiday-day-remove', 'console-holiday-publish']) {
      await expect(id(read, control), `${control} is not offered to Support`).toHaveCount(0);
    }
    await supportContext.close();
  });
});

test('TC-F04-S03-027 AC-10: the Receptionist, Professional and Accountant see no Hours & closures tab and are told "no access"; a support session sees the tab read-only with every control disabled', async ({ accounts, operator, browser, hours, calendar, clock }) => {
  for (const role of ['RECEPTIONIST', 'PROFESSIONAL', 'ACCOUNTANT'] as Role[]) {
    await test.step(role, async () => {
      const context = await accounts.context(alpha, [await accounts.identity.staff(alpha, role)]);
      const page = await accounts.open(context, HOURS);
      await expect(id(page, 'account-denied')).toBeVisible();
      await expect(id(page, 'hours-tab')).toHaveCount(0);
      await expect(id(page, 'settings-tab-hours')).toHaveCount(0);
      await context.close();
    });
  }
  await test.step('support session', async () => {
    const { at, today, days } = await sharedYear(operator, calendar, clock);
    const { created, salon, owner } = await newSalon(operator, accounts);
    await hours.save(salon, owner.tokens, week());
    await hours.add(salon, owner.tokens, plusDays(today, 5), null);
    const support = await operator.actor('OPERATOR_SUPPORT');
    const session = await supportJson<SupportStart>(await operator.startSupport(support, created.id, { reason: 'QA F04 support reads the hours' }), 'supportStart', 201);
    const context = await browser.newContext({ viewport: { width: 1280, height: 1000 }, locale: 'en' });
    await context.clock.setSystemTime(new Date(at));
    try {
      const entry = await context.newPage();
      await entry.goto(`${salonUrl(salon, webUrl)}/workspace/support#${session.entryCode}`);
      await expect(entry.getByTestId('support-banner')).toBeVisible();
      const page = await context.newPage();
      await page.goto(`${salonUrl(salon, webUrl)}${HOURS}`);
      await ready(page);
      await expect(id(page, 'hours-read-only')).toBeVisible();
      for (const day of DAYS) await expect(id(page, `hours-${day}-open`), `${day} toggle disabled`).toBeDisabled();
      await expect(id(page, 'hours-monday-opens')).toBeDisabled();
      await expect(id(page, 'hours-save')).toBeDisabled();
      await expect(id(page, `holiday-${days[0].date}-choice`)).toBeDisabled();
      await expect(id(page, 'holidays-accept')).toBeDisabled();
      await expect(id(page, 'closure-add')).toBeDisabled();
      await expect(id(page, 'closure-reopen')).toBeDisabled();
    } finally {
      await context.close();
    }
  });
});

test('TC-F04-S03-028 AC-12: the tab shows its loading state, says an unreachable service plainly and recovers on Try again, explains a conflict with Reload, and shows the empty states', async ({ accounts, operator, clock, hours }) => {
  const manager = await accounts.identity.staff(alpha, 'MANAGER');
  await hours.save(alpha, manager.tokens, week());
  const context = await accounts.context(alpha, [manager]);
  const route = '**/identity-web/staff/hours';
  await test.step('loading, then the tab', async () => {
    let release!: () => void;
    const held = new Promise<void>(resolve => { release = resolve; });
    await context.route(route, async r => { await held; await r.continue(); });
    const page = await accounts.open(context, HOURS);
    await expect(id(page, 'hours-loading')).toBeVisible();
    release();
    await ready(page);
    await context.unroute(route);
    await page.close();
  });
  await test.step('unreachable on opening, then Try again', async () => {
    await context.route(route, r => r.abort('connectionfailed'));
    const page = await accounts.open(context, HOURS);
    await expect(id(page, 'hours-unavailable')).toBeVisible();
    await context.unroute(route);
    await id(page, 'hours-retry').click();
    await ready(page);
    await expect(id(page, 'hours-unavailable')).toHaveCount(0);
    await page.close();
  });
  await test.step('unreachable on saving: nothing saved', async () => {
    const page = await accounts.open(context, HOURS);
    await ready(page);
    const before = hoursSnapshot(alpha.id);
    await context.route('**/identity-web/staff/hours-save', r => r.abort('connectionfailed'));
    await choose(page, 'hours-thursday-closes', 'hours-thursday-closes-option-1700');
    await id(page, 'hours-save').click();
    await expect(id(page, 'hours-unavailable')).toBeVisible();
    expect(hoursSnapshot(alpha.id)).toBe(before);
    await context.unroute('**/identity-web/staff/hours-save');
    await page.close();
  });
  await test.step('a conflict: someone else saved first; Reload shows their week', async () => {
    const page = await accounts.open(context, HOURS);
    await ready(page);
    const other = await accounts.identity.staff(alpha, 'MANAGER');
    await hours.save(alpha, other.tokens, week('07:15', '18:00'));
    const before = hoursSnapshot(alpha.id);
    await choose(page, 'hours-thursday-closes', 'hours-thursday-closes-option-1700');
    await id(page, 'hours-save').click();
    await expect(id(page, 'hours-conflict')).toBeVisible();
    expect(hoursSnapshot(alpha.id), 'the later save changed nothing').toBe(before);
    await id(page, 'hours-conflict-reload').click();
    await ready(page);
    await expect(id(page, 'hours-conflict')).toHaveCount(0);
    await expect(id(page, 'hours-monday-opens')).toContainText('07:15');
    await page.close();
  });
  await test.step('empty: no closures planned, and no year published', async () => {
    const at = colombo(`${UNPUBLISHED_YEAR}-01-10`);
    await clock.set(at);
    const { owner } = await newSalon(operator, accounts);
    const { page } = await openHours(accounts, owner, { at });
    await expect(id(page, 'closures-empty')).toContainText(message('settings.closures.none'));
    await expect(id(page, 'holidays-none')).toHaveText(message('settings.holidays.none'));
  });
});

/** The tab populated in every card: a year to review, a saved week and a closure, on a new salon. */
async function fullTab(accounts: AccountBrowser, operator: Operator, calendar: Calendar, clock: TestClock, hours: Hours) {
  const { at, today, days } = await sharedYear(operator, calendar, clock);
  const { salon, owner } = await newSalon(operator, accounts);
  await hours.save(salon, owner.tokens, week());
  await hours.add(salon, owner.tokens, plusDays(today, 2), 'A sixty character reason for the narrowest screen of all....');
  return { at, today, days, salon, owner };
}

test('TC-F04-S03-029 AC-12: at every width of the matrix the tab, the closure sheet and the console\'s Holiday calendar fit with no sideways scrolling and every control reachable', async ({ accounts, operator, calendar, clock, hours, browser }) => {
  const { at, days, owner } = await fullTab(accounts, operator, calendar, clock, hours);
  const { page } = await openHours(accounts, owner, { at, width: 360 });
  await choose(page, `holiday-${days[0].date}-choice`, `holiday-${days[0].date}-choice-special`);
  for (const viewport of DESIGN_WIDTHS) {
    await test.step(`the tab at ${viewport.width}`, async () => {
      await page.setViewportSize(viewport);
      await settled(page);
      // The Settings tab rail scrolls sideways on phones, as the prototype's does (.brtabs): the page must not. A tab past
      // the rail's edge is scrolled to, not outside the page; each tab is proved reachable below.
      const result = await fit(page);
      expect(result.counted).toBeGreaterThan(0);
      expect(result.pageScrolls, `tab ${viewport.width}: no sideways scroll`).toBe(false);
      expect(result.outside.filter(c => !c.startsWith('settings-tab-')), `tab ${viewport.width}: nothing outside the viewport`).toEqual([]);
      expect(result.clipped.filter(c => !c.startsWith('settings-tabs ')), `tab ${viewport.width}: nothing clipped`).toEqual([]);
      for (const control of ['settings-tab-profile', 'settings-tab-hours', 'settings-tab-rules', 'settings-tab-branding', 'hours-monday-open', 'hours-monday-opens', 'hours-monday-closes', 'hours-save', `holiday-${days[0].date}-choice`,
        `holiday-${days[0].date}-opens`, 'holidays-accept', 'closure-add', 'closure-reopen']) {
        const element = id(page, control);
        await element.scrollIntoViewIfNeeded();
        await expect(element, `${control} at ${viewport.width}`).toBeVisible();
      }
    });
    await test.step(`the sheet at ${viewport.width}`, async () => {
      await id(page, 'closure-add').click();
      await expect(id(page, 'closure-sheet')).toBeVisible();
      await id(page, 'closure-date').click();
      await expect(id(page, 'closure-calendar')).toBeVisible();
      await settled(page);
      const result = await fit(page);
      expect(result.counted).toBeGreaterThan(0);
      expect(result.pageScrolls, `sheet ${viewport.width}: no sideways scroll`).toBe(false);
      expect(result.outside.filter(c => !c.startsWith('settings-tab-')), `sheet ${viewport.width}: nothing outside the viewport`).toEqual([]);
      await page.keyboard.press('Escape');
      await expect(id(page, 'closure-calendar')).toHaveCount(0);
      await settled(page);
      for (const control of ['closure-date', 'closure-reason', 'closure-confirm']) await expect(id(page, control)).toBeVisible();
      await page.keyboard.press('Escape');
      await expect(id(page, 'closure-sheet')).toBeHidden();
    });
  }
  const admin = await operator.actor();
  const year = SHARED.year;
  const context = await consoleContext(browser, admin, { width: 360 });
  const consolePage = await context.newPage();
  await consolePage.goto(consoleUrl(`/console/holidays/${year}`));
  await expect(id(consolePage, 'console-holiday-day')).toHaveCount(SHARED.days.length + 1);
  await id(consolePage, 'console-holiday-file').setInputFiles({ name: 'bad.csv', mimeType: 'text/csv', buffer: Buffer.from(`date,name,kind\n${year + 1}-01-01,Outside the year with a long name,public\n`) });
  await expect(id(consolePage, 'console-holiday-import-refused')).toBeVisible();
  for (const viewport of DESIGN_WIDTHS) {
    await test.step(`the console year at ${viewport.width}`, async () => {
      await consolePage.setViewportSize(viewport);
      await expectFits(consolePage, `console ${viewport.width}`);
      for (const control of ['console-holiday-choose-file', 'console-holiday-add', 'console-holiday-day-edit', 'console-holiday-day-remove', 'console-holiday-publication', 'console-holiday-import-refused']) {
        // Each day has its own Edit and Remove: the first day's stand for them.
        await id(consolePage, control).first().scrollIntoViewIfNeeded();
        await expect(id(consolePage, control).first(), `${control} at ${viewport.width}`).toBeVisible();
      }
    });
  }
  await test.step('the console years at 360', async () => {
    await consolePage.setViewportSize(DESIGN_WIDTHS[0]);
    await consolePage.goto(consoleUrl('/console/holidays'));
    await expect(id(consolePage, 'console-holiday-years')).toBeVisible();
    await expectFits(consolePage, 'console years 360');
  });
  await context.close();
});

test('TC-F04-S03-030 AC-12: the tab, the sheets and the console pages have no serious accessibility violation, every day toggle and time select has its name, the date popover is named and its days readable, and the week is saved and the sheet used by keyboard alone', async ({ accounts, operator, calendar, clock, hours, browser }) => {
  const { at, today, days, owner } = await fullTab(accounts, operator, calendar, clock, hours);
  const { page } = await openHours(accounts, owner, { at });
  await test.step('names: each weekday toggle by its day, each time select by day and edge, each holiday select by the holiday', async () => {
    for (const day of DAYS) {
      const name = message(`settings.hours.days.${day}`);
      await expect(page.getByRole('button', { name, exact: true }), `${day} toggle`).toHaveAttribute('data-testid', `hours-${day}-open`);
      if (day !== 'sunday') {
        await expect(page.getByRole('combobox', { name: words('settings.hours.opens', { day: name }), exact: true })).toHaveAttribute('data-testid', `hours-${day}-opens`);
        await expect(page.getByRole('combobox', { name: words('settings.hours.closes', { day: name }), exact: true })).toHaveAttribute('data-testid', `hours-${day}-closes`);
      }
    }
    const label = await id(page, `holiday-${days[0].date}-choice`).getAttribute('aria-label');
    expect(label, 'the holiday select names its holiday').toContain(days[0].name);
  });
  await test.step('no serious violation: loaded, field error, sheet with its calendar', async () => {
    await accessible(page, 'tab loaded');
    await choose(page, 'hours-tuesday-closes', 'hours-tuesday-closes-option-0800');
    await id(page, 'hours-save').click();
    await expect(id(page, 'hours-tuesday-error')).toBeVisible();
    await accessible(page, 'tab with a field error');
    await page.reload();
    await ready(page);
    await id(page, 'closure-add').click();
    await id(page, 'closure-date').click();
    await expect(id(page, 'closure-calendar')).toBeVisible();
    await settled(page);
    await accessible(page, 'sheet with its calendar');
    await page.keyboard.press('Escape');
    await expect(id(page, 'closure-calendar')).toHaveCount(0);
    await settled(page);
    await page.keyboard.press('Escape');
    await expect(id(page, 'closure-sheet')).toBeHidden();
  });
  await test.step('keyboard: Space closes a day, Save hours by Enter', async () => {
    await page.reload();
    await ready(page);
    await page.locator('body').click({ position: { x: 1, y: 1 } });
    let steps = 0;
    while (!(await id(page, 'hours-thursday-open').evaluate(el => el === document.activeElement)) && steps++ < 80) await page.keyboard.press('Tab');
    await expect(id(page, 'hours-thursday-open'), 'Thursday is reached by Tab').toBeFocused();
    await page.keyboard.press('Space');
    await expect(id(page, 'hours-thursday-open')).toHaveAttribute('aria-pressed', 'false');
    steps = 0;
    while (!(await id(page, 'hours-save').evaluate(el => el === document.activeElement)) && steps++ < 80) await page.keyboard.press('Tab');
    await expect(id(page, 'hours-save'), 'Save hours is reached by Tab').toBeFocused();
    await page.keyboard.press('Enter');
    await expect(page.getByText(message('settings.hoursTab.toast.hoursSaved'))).toBeVisible();
    expect((await hours.settings(owner.salon, owner.tokens)).weekly!.find(d => d.day === 'thursday')!.open, 'Thursday saved closed').toBe(false);
  });
  await test.step('the sheet keeps focus inside and returns it to + Add closure on Escape', async () => {
    await expectSheetFocus(page, id(page, 'closure-add'), id(page, 'closure-sheet'), 8);
  });
  await test.step('the date popover is named "Choose a date", and today and the chosen day are readable on the default and a dark brand', async () => {
    // axe counts text on a translucent tint as "incomplete", so the drawn contrast is measured (WCAG 1.4.3, 4.5:1).
    const darkManager = await accounts.identity.staff(beta, 'MANAGER');
    for (const [label, opened] of [['default brand', page], ['beta\'s dark brand', (await openHours(accounts, darkManager, { at })).page]] as const) {
      await opened.reload();
      await ready(opened);
      await id(opened, 'closure-add').click();
      await id(opened, 'closure-date').click();
      await expect(id(opened, 'closure-calendar')).toBeVisible();
      await expect(opened.getByRole('dialog', { name: 'Choose a date', exact: true }), `${label}: the popover is named`).toBeVisible();
      await settled(opened);
      const todayDay = calendarDay(id(opened, 'closure-calendar'), today);
      expect(await drawnContrast(todayDay), `${label}: today is readable`).toBeGreaterThanOrEqual(4.5);
      const chosen = plusDays(today, 4);
      await calendarDay(id(opened, 'closure-calendar'), chosen).click();
      await id(opened, 'closure-date').click();
      await expect(id(opened, 'closure-calendar')).toBeVisible();
      await settled(opened);
      expect(await drawnContrast(calendarDay(id(opened, 'closure-calendar'), chosen)), `${label}: the chosen day is readable`).toBeGreaterThanOrEqual(4.5);
      await opened.keyboard.press('Escape');
      await expect(id(opened, 'closure-calendar')).toHaveCount(0);
      await settled(opened);
      await opened.keyboard.press('Escape');
      await expect(id(opened, 'closure-sheet')).toBeHidden();
    }
  });
  await test.step('the console: years, a refused import and the publication dialog', async () => {
    const admin = await operator.actor();
    const year = await calendar.freshYear(admin);
    const context = await consoleContext(browser, admin);
    const consolePage = await context.newPage();
    await consolePage.goto(consoleUrl('/console/holidays'));
    await expect(id(consolePage, 'console-holiday-years')).toBeVisible();
    await accessible(consolePage, 'console years');
    await consolePage.goto(consoleUrl(`/console/holidays/${year}`));
    await id(consolePage, 'console-holiday-file').setInputFiles({ name: 'bad.csv', mimeType: 'text/csv', buffer: Buffer.from(`date,name,kind\n${year}-02-30,No such day,public\n`) });
    await expect(id(consolePage, 'console-holiday-import-refused')).toBeVisible();
    await accessible(consolePage, 'console refused import');
    await id(consolePage, 'console-holiday-add').click();
    await expect(id(consolePage, 'console-holiday-add-sheet')).toBeVisible();
    await id(consolePage, 'console-holiday-day-date').click();
    await expect(id(consolePage, 'console-holiday-day-calendar')).toBeVisible();
    await settled(consolePage);
    await accessible(consolePage, 'console add-day sheet with its calendar');
    await expect(consolePage.getByRole('dialog', { name: 'Choose a date', exact: true }), 'the console popover is named').toBeVisible();
    await consolePage.keyboard.press('Escape');
    await expect(id(consolePage, 'console-holiday-day-calendar')).toHaveCount(0);
    await settled(consolePage);
    await consolePage.keyboard.press('Escape');
    await expect(id(consolePage, 'console-holiday-add-sheet')).toHaveCount(0);
    await id(consolePage, 'console-holiday-publish').click();
    await expect(id(consolePage, 'console-holiday-publish-dialog')).toBeVisible();
    await settled(consolePage);
    await accessible(consolePage, 'console publication dialog');
    await context.close();
  });
});

