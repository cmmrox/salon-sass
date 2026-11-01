/**
 * F04-S04 the Booking rules tab in the browser: the tab's place and the suggested rules marked "Not
 * saved yet", the prototype's choices in every list, "What customers see before paying" following
 * the form, the prototype's messages beside a field, two people saving at once, the History sheet,
 * who sees the tab, a support session, the loading and unavailable states, the leave guard (a link
 * and a Settings tab), every width of the matrix, keyboard use and accessibility.
 *
 * Expected results are the F04-S04 spec's (acceptance criteria, "UI and UX", "Corrections at close")
 * and the prototype (Salon web, Settings, Booking rules: `setRules`, `ruNum`, `ruText`), whose labels,
 * options, messages and sentences are written here from the prototype rather than read from the
 * product's translation file. Rules the API cases prove (every boundary, every role on every
 * endpoint) are not proved again: each case checks what the person sees, and persistence once
 * through the API. After a client-side tab change Next.js keeps the previous tab's markup hidden,
 * so elements are looked up among the visible ones (F04-S04 handoff).
 */
import type { BrowserContext, Locator, Page } from '@playwright/test';

import {
  BookingRules, SUGGESTED, distinct, prototypeSentences, rules, rulesSnapshot, stored, type RulesInput,
} from '../../shared/booking-rules';
import { DESIGN_WIDTHS } from '../../shared/design-reference';
import { webUrl } from '../../shared/env';
import { test as browserTest, expect, type AccountBrowser } from '../../shared/identity-browser';
import type { Actor, Role } from '../../shared/identity';
import { Operator, ownerSession, supportJson, type SupportStart } from '../../shared/operator';
import { drawnContrast, fit, focusIndicator, seriousViolations } from '../../shared/page-checks';
import { message } from '../../shared/strings';
import { alpha, gamma, salonUrl, type Salon } from '../../shared/tenants';

const test = browserTest.extend<{ operator: Operator; book: BookingRules }>({
  operator: async ({ identity }, use) => {
    const operator = new Operator(identity);
    await operator.resetFixtures();
    try { await use(operator); } finally { await operator.resetToBaseline(); }
  },
  book: async ({ identity }, use) => { await use(new BookingRules(identity)); },
});
test.describe.configure({ timeout: 180_000 });

const RULES = '/workspace/settings/rules';
const id = (page: Page, name: string) => page.getByTestId(name).filter({ visible: true });

/** The prototype's lists (`setRules`): the trigger's test id, its label, and its options as value and label. */
const LISTS: { testId: string; label: string; options: [string, string][] }[] = [
  { testId: 'rules-interval', label: 'Show start times every', options: [['10', '10 minutes'], ['15', '15 minutes'], ['20', '20 minutes'], ['30', '30 minutes']] },
  { testId: 'rules-notice', label: 'Customers must book at least', options: [['0', 'Any time before'], ['30', '30 minutes ahead'], ['60', '1 hour ahead'], ['120', '2 hours ahead'], ['240', '4 hours ahead'], ['1440', '1 day ahead']] },
  { testId: 'rules-horizon', label: 'Customers can book up to', options: [['14', '2 weeks ahead'], ['30', '1 month ahead'], ['60', '2 months ahead'], ['90', '3 months ahead'], ['180', '6 months ahead']] },
  { testId: 'rules-free', label: 'Free cancellation until', options: [['2', '2 hours before'], ['6', '6 hours before'], ['12', '12 hours before'], ['24', '24 hours before'], ['48', '48 hours before']] },
  { testId: 'rules-late', label: 'A later cancellation keeps', options: [['nothing', 'Nothing'], ['deposit', 'The deposit'], ['share', 'A share of the price']] },
  { testId: 'rules-noshow', label: 'A no-show keeps', options: [['deposit', 'The deposit'], ['share', 'A share of the price']] },
  { testId: 'rules-reschedule', label: 'Customers can reschedule', options: [['0', 'Not online'], ['1', 'Once'], ['2', 'Twice'], ['any', 'Any number of times']] },
  { testId: 'rules-until', label: 'Up to', options: [['2', '2 hours before'], ['6', '6 hours before'], ['12', '12 hours before'], ['24', '24 hours before']] },
];
/** The prototype's messages beside a field (`ruNum`). */
const ERRORS = { percent: 'Enter 1 to 100.', cap: 'Enter at least Rs. 500, or choose a fixed amount.', amount: 'Enter Rs. 100 to Rs. 100,000.' };
const SAVED_TOAST = 'Booking rules saved. They apply to new bookings.';
const SUMMARY_NOTE = 'Changes apply to new bookings. Existing bookings keep the policy they were made under.';
const SERVICE_DEPOSIT = 'A service can ask for a different deposit, set on the service.';

async function ready(page: Page) {
  await expect(id(page, 'rules-tab')).toBeVisible();
  await expect(id(page, 'rules-loading')).toHaveCount(0);
  await expect(id(page, 'rules-form')).toBeVisible();
}

/** Opens the tab signed in as the actor and waits until the form shows the rules. */
async function openRules(accounts: AccountBrowser, actor: Actor, options: { width?: number; salon?: Salon } = {}): Promise<{ context: BrowserContext; page: Page }> {
  const context = await accounts.context(options.salon ?? actor.salon, [actor], { width: options.width ?? 1280 });
  const page = await accounts.open(context, RULES);
  await ready(page);
  return { context, page };
}

/** Chooses an option of a shadcn Select by the trigger's test id and the option's value. */
async function choose(page: Page, trigger: string, value: string) {
  await id(page, trigger).click();
  await id(page, `${trigger}-option-${value}`).click();
  await expect(page.getByTestId(`${trigger}-option-${value}`)).toHaveCount(0);
}

/** The four sentences "What customers see before paying" shows, as a person reads them. */
const shownSentences = (page: Page) => id(page, 'rules-summary-sentence').allInnerTexts().then(all => all.map(s => s.replace(/\s+/g, ' ').trim()));

async function expectSummary(page: Page, r: RulesInput, label: string) {
  await expect.poll(() => shownSentences(page), `${label}: the prototype's sentences for these rules`).toEqual(prototypeSentences(r));
}

/** Waits until no animation runs, so a scan or a measurement reads the settled screen. */
async function settled(page: Page) {
  await page.waitForFunction(() => document.getAnimations().every(a => a.playState !== 'running'));
}

async function accessible(page: Page, label: string) {
  expect.soft(await seriousViolations(page), `${label}: no serious or critical violation`).toEqual([]);
}

/** A salon the operator creates now, with its Owner signed in. */
async function newSalon(operator: Operator, accounts: AccountBrowser) {
  const admin = await operator.actor();
  const plan = await operator.plan(admin);
  const { input, created, salon } = await operator.salon(admin, plan.id);
  const owner = await ownerSession(accounts.identity, salon, input);
  return { admin, input, created, salon, owner };
}

/** Tabs forward until the element has the focus, failing after `limit` presses. */
async function tabTo(page: Page, target: Locator, limit = 80) {
  let steps = 0;
  while (!(await target.evaluate(el => el === document.activeElement)) && steps++ < limit) await page.keyboard.press('Tab');
  await expect(target, 'reached by Tab').toBeFocused();
}

test('TC-F04-S04-016 AC-1: the Owner finds Booking rules between Hours & closures and Branding, sees the suggested rules marked "Not saved yet" with their summary, and Save booking rules saves them as version 1', async ({ accounts, operator, book }) => {
  const { salon, owner } = await newSalon(operator, accounts);
  const { page } = await openRules(accounts, owner);
  await test.step('the tab bar and the intro', async () => {
    const boxes = await Promise.all(['profile', 'hours', 'rules', 'branding'].map(t => id(page, `settings-tab-${t}`).boundingBox()));
    expect(boxes.every(Boolean), 'four tabs').toBe(true);
    expect(boxes.map(b => b!.x), 'Profile, Hours & closures, Booking rules, Branding in that order').toEqual([...boxes.map(b => b!.x)].sort((a, b) => a - b));
    await expect(id(page, 'settings-tab-rules')).toHaveText('Booking rules');
    await expect(id(page, 'settings-tab-rules')).toHaveAttribute('aria-selected', 'true');
    await expect(id(page, 'settings-intro'), 'the intro says booking rules save at once').toHaveText(message('settings.introRules'));
  });
  await test.step('the suggested rules, not saved', async () => {
    await expect(id(page, 'rules-not-saved')).toContainText('Not saved yet');
    const shown: Record<string, string> = { 'rules-interval': '15 minutes', 'rules-notice': '1 hour ahead', 'rules-horizon': '2 months ahead',
      'rules-free': '24 hours before', 'rules-late': 'The deposit', 'rules-noshow': 'The deposit', 'rules-reschedule': 'Twice', 'rules-until': '12 hours before' };
    for (const [trigger, text] of Object.entries(shown)) await expect(id(page, trigger), trigger).toHaveText(text);
    await expect(id(page, 'rules-deposit-percentage')).toHaveAttribute('data-state', 'on');
    await expect(id(page, 'rules-percent')).toHaveValue('20');
    await expect(id(page, 'rules-cap')).toHaveValue('2000');
    await expect(id(page, 'rules-service-deposit')).toHaveText(SERVICE_DEPOSIT);
    await expectSummary(page, SUGGESTED, 'suggested');
    await expect(id(page, 'rules-summary-note')).toHaveText(SUMMARY_NOTE);
    expect((await book.settings(salon, owner.tokens)).version, 'nothing is stored yet').toBe(0);
  });
  await test.step('Save booking rules', async () => {
    await id(page, 'rules-save').click();
    await expect(page.getByText(SAVED_TOAST)).toBeVisible();
    await expect(id(page, 'rules-not-saved')).toHaveCount(0);
    expect(await book.settings(salon, owner.tokens)).toEqual({ version: 1, rules: stored(SUGGESTED), currency: 'LKR' });
  });
});

test('TC-F04-S04-017 AC-2, AC-3: every list offers exactly the prototype\'s choices under the prototype\'s label, and the deposit offers No deposit, Percentage and Fixed amount', async ({ accounts, book }) => {
  const manager = await accounts.identity.staff(alpha, 'MANAGER');
  await book.save(alpha, manager.tokens, distinct({ lateCancellation: { kind: 'share', percent: 30 }, noShow: { kind: 'share', percent: 60 } }));
  const { page } = await openRules(accounts, manager);
  for (const list of LISTS) {
    await test.step(list.label, async () => {
      await expect(page.getByRole('combobox', { name: list.label, exact: true }), `${list.testId} is named by its label`).toHaveAttribute('data-testid', list.testId);
      await id(page, list.testId).click();
      const options = page.getByRole('option');
      await expect(options.first()).toBeVisible();
      expect(await options.allInnerTexts(), `${list.label}: exactly the prototype's choices, in order`).toEqual(list.options.map(o => o[1]));
      for (const [value, label] of list.options) await expect(id(page, `${list.testId}-option-${value}`)).toHaveText(label);
      await page.keyboard.press('Escape');
      await expect(options).toHaveCount(0);
    });
  }
  await test.step('the deposit kinds', async () => {
    await expect(page.getByRole('radiogroup', { name: 'Deposit', exact: true }), 'the deposit choice is named Deposit').toHaveAttribute('data-testid', 'rules-deposit');
    for (const [kind, label] of [['none', 'No deposit'], ['percentage', 'Percentage'], ['amount', 'Fixed amount']]) await expect(id(page, `rules-deposit-${kind}`)).toHaveText(label);
  });
});

test('TC-F04-S04-018 AC-4: "What customers see before paying" states the prototype\'s sentences for the form as it changes, including nothing kept without a deposit and "contact the salon" when rescheduling is not online, and saving keeps exactly what it showed', async ({ accounts, operator, book }) => {
  const { salon, owner } = await newSalon(operator, accounts);
  const { page } = await openRules(accounts, owner);
  let r = rules();
  await expectSummary(page, r, 'start');
  await test.step('no deposit: nothing is kept, and the deposit fields go', async () => {
    await id(page, 'rules-deposit-none').click();
    r = rules({ ...r, deposit: { kind: 'none' } });
    await expectSummary(page, r, 'no deposit');
    await expect(id(page, 'rules-percent')).toHaveCount(0);
    await expect(id(page, 'rules-service-deposit'), 'the per-service note only with a deposit').toHaveCount(0);
  });
  await test.step('a fixed amount', async () => {
    await id(page, 'rules-deposit-amount').click();
    await id(page, 'rules-amount').fill('1500');
    r = rules({ ...r, deposit: { kind: 'amount', amountMinor: 150000 } });
    await expectSummary(page, r, 'fixed Rs. 1,500');
  });
  await test.step('a share kept on a late cancellation and on a no-show', async () => {
    await choose(page, 'rules-late', 'share');
    await id(page, 'rules-late-percent').fill('30');
    await choose(page, 'rules-noshow', 'share');
    await id(page, 'rules-noshow-percent').fill('75');
    r = rules({ ...r, lateCancellation: { kind: 'share', percent: 30 }, noShow: { kind: 'share', percent: 75 } });
    await expectSummary(page, r, 'shares');
  });
  await test.step('free cancellation and rescheduling', async () => {
    await choose(page, 'rules-free', '6');
    await choose(page, 'rules-reschedule', 'any');
    await choose(page, 'rules-until', '2');
    r = rules({ ...r, freeCancellationHours: 6, reschedule: { limit: null, untilHours: 2 } });
    await expectSummary(page, r, 'as often as you need');
    await choose(page, 'rules-reschedule', '1');
    r = rules({ ...r, reschedule: { limit: 1, untilHours: 2 } });
    await expectSummary(page, r, 'once');
    await choose(page, 'rules-reschedule', '0');
    await expect(id(page, 'rules-until'), 'no time when not online').toHaveCount(0);
    r = rules({ ...r, reschedule: { limit: 0, untilHours: null } });
    await expectSummary(page, r, 'not online');
  });
  await test.step('a percentage deposit with its cap', async () => {
    await id(page, 'rules-deposit-percentage').click();
    await id(page, 'rules-percent').fill('35');
    await id(page, 'rules-cap').fill('12500');
    r = rules({ ...r, deposit: { kind: 'percentage', percent: 35, capMinor: 1250000 } });
    await expectSummary(page, r, 'percentage');
  });
  await test.step('the booking times change nothing customers read here', async () => {
    await choose(page, 'rules-interval', '10');
    await choose(page, 'rules-notice', '1440');
    await choose(page, 'rules-horizon', '180');
    r = rules({ ...r, slotIntervalMinutes: 10, minimumNoticeMinutes: 1440, bookingHorizonDays: 180 });
    await expectSummary(page, r, 'times');
  });
  await id(page, 'rules-save').click();
  await expect(page.getByText(SAVED_TOAST)).toBeVisible();
  expect((await book.settings(salon, owner.tokens)).rules, 'the saved rules are those the summary showed').toEqual(stored(r));
});

test('TC-F04-S04-019 AC-3: a percentage, cap, amount or share that breaks its rule shows the prototype\'s message beside the field, Save is disabled and nothing is saved', async ({ accounts, operator, book }) => {
  const { salon, owner } = await newSalon(operator, accounts);
  await book.save(salon, owner.tokens, SUGGESTED, 0);
  const { page } = await openRules(accounts, owner);
  const before = rulesSnapshot(salon.id);
  const rows: { label: string; arrange?: () => Promise<void>; field: string; value: string; message: string }[] = [
    { label: 'percent 0', field: 'rules-percent', value: '0', message: ERRORS.percent },
    { label: 'percent 101', field: 'rules-percent', value: '101', message: ERRORS.percent },
    { label: 'cap Rs. 499', field: 'rules-cap', value: '499', message: ERRORS.cap },
    { label: 'amount Rs. 99', arrange: () => id(page, 'rules-deposit-amount').click(), field: 'rules-amount', value: '99', message: ERRORS.amount },
    { label: 'amount Rs. 100,001', field: 'rules-amount', value: '100001', message: ERRORS.amount },
    { label: 'late share 0%', arrange: () => choose(page, 'rules-late', 'share'), field: 'rules-late-percent', value: '0', message: ERRORS.percent },
    { label: 'no-show share 101%', arrange: () => choose(page, 'rules-noshow', 'share'), field: 'rules-noshow-percent', value: '101', message: ERRORS.percent },
  ];
  for (const row of rows) {
    await test.step(row.label, async () => {
      await row.arrange?.();
      const summary = await shownSentences(page);
      await id(page, row.field).fill(row.value);
      await expect(id(page, `${row.field}-error`), `${row.label}: the message beside the field`).toHaveText(row.message);
      const describedBy = await id(page, row.field).getAttribute('aria-describedby') ?? '';
      const errorId = await id(page, `${row.field}-error`).getAttribute('id');
      expect(describedBy.split(' '), `${row.label}: the message is tied to the field`).toContain(errorId);
      await expect(id(page, row.field)).toHaveAttribute('aria-invalid', 'true');
      await expect(id(page, 'rules-save'), `${row.label}: Save is disabled`).toBeDisabled();
      expect(await shownSentences(page), `${row.label}: the summary stays on the last valid value`).toEqual(summary);
      await id(page, row.field).fill(row.field === 'rules-amount' ? '1000' : row.field === 'rules-cap' ? '2000' : '50');
      await expect(id(page, `${row.field}-error`)).toHaveCount(0);
    });
  }
  expect(rulesSnapshot(salon.id), 'nothing was saved').toBe(before);
});

test('TC-F04-S04-020 AC-5, AC-10: when someone else saved first, the screen says so and Reload shows their rules; the next save is a new version', async ({ accounts, operator, book }) => {
  const { salon, owner } = await newSalon(operator, accounts);
  await book.save(salon, owner.tokens, SUGGESTED, 0);
  const { page } = await openRules(accounts, owner);
  const theirs = rules({ freeCancellationHours: 48, deposit: { kind: 'amount', amountMinor: 250000 } });
  await book.save(salon, owner.tokens, theirs, 1);
  await choose(page, 'rules-free', '2');
  await id(page, 'rules-save').click();
  await expect(id(page, 'rules-conflict'), 'the screen says someone else changed the rules').toBeVisible();
  await expect(id(page, 'rules-conflict')).toContainText('Someone else changed the booking rules');
  expect((await book.settings(salon, owner.tokens)).rules, 'the other person\'s rules stand').toEqual(stored(theirs));
  await id(page, 'rules-conflict-reload').click();
  await expect(id(page, 'rules-free')).toHaveText('48 hours before');
  await expect(id(page, 'rules-deposit-amount')).toHaveAttribute('data-state', 'on');
  await expect(id(page, 'rules-amount')).toHaveValue('2500');
  await expectSummary(page, theirs, 'after Reload');
  await choose(page, 'rules-free', '12');
  await id(page, 'rules-save').click();
  await expect(page.getByText(SAVED_TOAST)).toBeVisible();
  const after = await book.settings(salon, owner.tokens);
  expect(after.version).toBe(3);
  expect(after.rules?.freeCancellationHours).toBe(12);
});

test('TC-F04-S04-021 AC-7: History opens a sheet listing the versions newest first with "Current", the author as "You" or the staff member, and the rules each changed, paged by ten; Escape closes it and returns the focus', async ({ accounts, operator, book }) => {
  const { salon, owner } = await newSalon(operator, accounts);
  await book.save(salon, owner.tokens, SUGGESTED, 0);
  // A second author: a Manager of this salon, seeded and signed in through the local hook.
  const seeded = await accounts.identity.staff(salon, 'MANAGER');
  for (let n = 0; n < 10; n++) await book.save(salon, n === 9 ? seeded.tokens : owner.tokens, distinct());
  await book.save(salon, owner.tokens, distinct({ slotIntervalMinutes: 20 }));
  const history = await book.history(salon, owner.tokens, '?size=100');
  expect(history.total, 'twelve versions').toBe(12);
  const { page } = await openRules(accounts, owner);
  await id(page, 'rules-history').click();
  const sheet = id(page, 'rules-history-sheet');
  await expect(sheet).toBeVisible();
  await expect(id(page, 'rules-history-loading')).toHaveCount(0);
  await test.step('the first page: ten versions, newest first', async () => {
    const rows = sheet.getByTestId('rules-history-row');
    await expect(rows).toHaveCount(10);
    expect(await rows.evaluateAll(els => els.map(e => Number(e.getAttribute('data-version')))), 'newest first').toEqual([12, 11, 10, 9, 8, 7, 6, 5, 4, 3]);
    await expect(rows.first().getByTestId('rules-history-current')).toBeVisible();
    await expect(sheet.getByTestId('rules-history-current'), '"Current" only on the newest').toHaveCount(1);
    await expect(rows.first().getByTestId('rules-history-by')).toContainText('You');
    await expect(rows.first().getByTestId('rules-history-changed')).toContainText('start times');
    await expect(rows.first().getByTestId('rules-history-changed')).toContainText('deposit');
    const byManager = sheet.locator('[data-testid="rules-history-row"][data-version="11"]').getByTestId('rules-history-by');
    await expect(byManager, 'another staff member by the label the staff list shows').toContainText(seeded.account.email);
    await expect(byManager).not.toContainText('You');
    await expect(id(page, 'rules-history-page')).toContainText('1');
    await expect(id(page, 'rules-history-page')).toContainText('2');
  });
  await test.step('Older shows the rest', async () => {
    await id(page, 'rules-history-older').click();
    const rows = sheet.getByTestId('rules-history-row');
    await expect.poll(() => rows.evaluateAll(els => els.map(e => Number(e.getAttribute('data-version'))))).toEqual([2, 1]);
    const first = sheet.locator('[data-testid="rules-history-row"][data-version="1"]').getByTestId('rules-history-changed');
    for (const name of ['start times', 'notice', 'how far ahead', 'deposit', 'free cancellation', 'late cancellation', 'no-show', 'rescheduling']) await expect(first, `version 1 changed ${name}`).toContainText(name);
    await id(page, 'rules-history-newer').click();
    await expect(sheet.getByTestId('rules-history-row')).toHaveCount(10);
  });
  await test.step('Escape closes the sheet and returns the focus to History', async () => {
    await page.keyboard.press('Escape');
    await expect(page.getByTestId('rules-history-sheet')).toHaveCount(0);
    await expect(id(page, 'rules-history')).toBeFocused();
  });
});

test('TC-F04-S04-022 AC-7, AC-8: the Receptionist, Professional and Accountant see no Booking rules tab and are told "no access"; a support session sees the tab and the history read-only with every control disabled', async ({ accounts, operator, book, browser }) => {
  for (const role of ['RECEPTIONIST', 'PROFESSIONAL', 'ACCOUNTANT'] as Role[]) {
    await test.step(role, async () => {
      const actor = await accounts.identity.staff(alpha, role);
      const context = await accounts.context(alpha, [actor]);
      const page = await accounts.open(context, RULES);
      await expect(id(page, 'account-denied'), `${role}: no access by address`).toBeVisible();
      await expect(page.getByTestId('rules-form'), `${role}: no rules form`).toHaveCount(0);
      await expect(page.getByTestId('settings-tab-rules'), `${role}: no Booking rules tab`).toHaveCount(0);
      await context.close();
    });
  }
  await test.step('a support session', async () => {
    const { created, salon, owner } = await newSalon(operator, accounts);
    const saved = rules({ deposit: { kind: 'amount', amountMinor: 120000 } });
    await book.save(salon, owner.tokens, saved, 0);
    const support = await operator.actor('OPERATOR_SUPPORT');
    const session = await supportJson<SupportStart>(await operator.startSupport(support, created.id, { reason: 'QA F04 support reads the booking rules' }), 'supportStart', 201);
    const context = await browser.newContext({ viewport: { width: 1280, height: 1000 }, locale: 'en' });
    try {
      const entry = await context.newPage();
      await entry.goto(`${salonUrl(salon, webUrl)}/workspace/support#${session.entryCode}`);
      await expect(entry.getByTestId('support-banner')).toBeVisible();
      const page = await context.newPage();
      await page.goto(`${salonUrl(salon, webUrl)}${RULES}`);
      await ready(page);
      await expect(id(page, 'rules-read-only')).toBeVisible();
      await expect(id(page, 'rules-amount'), 'the saved rules are shown').toHaveValue('1200');
      for (const control of [...LISTS.map(l => l.testId), 'rules-deposit-none', 'rules-deposit-percentage', 'rules-deposit-amount', 'rules-amount', 'rules-save']) {
        if (await id(page, control).count() === 0) continue; // `rules-until` and others shown only for some choices
        await expect(id(page, control), `${control} disabled`).toBeDisabled();
      }
      await id(page, 'rules-history').click();
      await expect(id(page, 'rules-history-sheet').getByTestId('rules-history-row'), 'the history is readable').toHaveCount(1);
    } finally {
      await context.close();
    }
  });
});

test('TC-F04-S04-023 AC-10: the tab shows its loading state, says an unreachable service plainly and recovers on Try again, and a save that cannot reach the service keeps the edits and saves nothing', async ({ accounts, operator, book }) => {
  const { salon, owner } = await newSalon(operator, accounts);
  await book.save(salon, owner.tokens, SUGGESTED, 0);
  const context = await accounts.context(salon, [owner]);
  await test.step('loading, then the form', async () => {
    let release!: () => void;
    const held = new Promise<void>(resolve => { release = resolve; });
    await context.route('**/identity-web/staff/booking-rules', async route => { await held; await route.continue(); });
    const page = await accounts.open(context, RULES);
    await expect(id(page, 'rules-loading')).toBeVisible();
    release();
    await ready(page);
    await context.unroute('**/identity-web/staff/booking-rules');
    await page.close();
  });
  await test.step('unreachable on opening, then Try again', async () => {
    await context.route('**/identity-web/staff/booking-rules', route => route.abort('connectionfailed'));
    const page = await accounts.open(context, RULES);
    await expect(id(page, 'rules-unavailable')).toBeVisible();
    await context.unroute('**/identity-web/staff/booking-rules');
    await id(page, 'rules-retry').click();
    await ready(page);
    await page.close();
  });
  await test.step('unreachable on saving: nothing saved, the edits kept, then saved', async () => {
    const page = await accounts.open(context, RULES);
    await ready(page);
    const before = rulesSnapshot(salon.id);
    await choose(page, 'rules-free', '48');
    await context.route('**/identity-web/staff/booking-rules-save', route => route.abort('connectionfailed'));
    await id(page, 'rules-save').click();
    await expect(id(page, 'rules-unavailable')).toContainText('Nothing was saved');
    await expect(id(page, 'rules-free'), 'the edit is kept').toHaveText('48 hours before');
    expect(rulesSnapshot(salon.id), 'nothing saved').toBe(before);
    await context.unroute('**/identity-web/staff/booking-rules-save');
    await id(page, 'rules-save').click();
    await expect(page.getByText(SAVED_TOAST)).toBeVisible();
    expect((await book.settings(salon, owner.tokens)).rules?.freeCancellationHours).toBe(48);
  });
});

test('TC-F04-S04-024 AC-10: leaving with unsaved edits by a link or another Settings tab asks first; Stay keeps the edits, Leave goes, and closing the page asks the browser', async ({ accounts, operator, book }) => {
  const { salon, owner } = await newSalon(operator, accounts);
  await book.save(salon, owner.tokens, SUGGESTED, 0);
  const { context, page } = await openRules(accounts, owner);
  await choose(page, 'rules-interval', '30');
  await test.step('a navigation link', async () => {
    await id(page, 'workspace-account').click();
    await expect(id(page, 'rules-leave-dialog')).toBeVisible();
    await id(page, 'rules-leave-stay').click();
    await expect(page).toHaveURL(new RegExp(`${RULES}$`));
    await expect(id(page, 'rules-interval'), 'the edit is kept').toHaveText('30 minutes');
  });
  await test.step('another Settings tab', async () => {
    await id(page, 'settings-tab-hours').click();
    await expect(id(page, 'rules-leave-dialog')).toBeVisible();
    await id(page, 'rules-leave-stay').click();
    await expect(page).toHaveURL(new RegExp(`${RULES}$`));
    await expect(id(page, 'settings-tab-rules')).toHaveAttribute('aria-selected', 'true');
    await expect(id(page, 'rules-interval')).toHaveText('30 minutes');
    await id(page, 'settings-tab-hours').click();
    await id(page, 'rules-leave-confirm').click();
    await expect(page).toHaveURL(/\/workspace\/settings\/hours$/);
    await expect(id(page, 'hours-tab')).toBeVisible();
    expect((await book.settings(salon, owner.tokens)).rules?.slotIntervalMinutes, 'leaving saved nothing').toBe(15);
  });
  await test.step('closing the page with unsaved edits asks the browser to confirm', async () => {
    const again = await accounts.open(context, RULES);
    await ready(again);
    await choose(again, 'rules-horizon', '14');
    const dialog = again.waitForEvent('dialog');
    await again.close({ runBeforeUnload: true });
    const shown = await dialog;
    expect(shown.type()).toBe('beforeunload');
    await shown.dismiss();
    await expect(id(again, 'rules-horizon'), 'dismissing keeps the page and the edit').toHaveText('2 weeks ahead');
  });
});

test('TC-F04-S04-025 AC-10: at every width of the matrix the tab and the History sheet fit with no sideways scrolling and every control reachable, with the summary above the form on phones and beside it from 900px', async ({ accounts, operator, book }) => {
  const { salon, owner } = await newSalon(operator, accounts);
  await book.save(salon, owner.tokens, rules({ lateCancellation: { kind: 'share', percent: 30 }, noShow: { kind: 'share', percent: 100 } }), 0);
  for (let n = 0; n < 11; n++) await book.save(salon, owner.tokens, distinct({ lateCancellation: { kind: 'share', percent: 30 }, noShow: { kind: 'share', percent: 100 } }));
  const { page } = await openRules(accounts, owner, { width: 360 });
  for (const viewport of DESIGN_WIDTHS) {
    await test.step(`the tab at ${viewport.width}`, async () => {
      await page.setViewportSize(viewport);
      await settled(page);
      // The Settings tab rail scrolls sideways on phones, as the prototype's does (.brtabs): the page must not.
      const result = await fit(page);
      expect(result.counted).toBeGreaterThan(0);
      expect(result.pageScrolls, `${viewport.width}: no sideways scroll`).toBe(false);
      // A tab beyond the rail's edge is scrolled to, not outside the page; each tab is proved reachable below.
      expect(result.outside.filter(c => !c.startsWith('settings-tab-')), `${viewport.width}: nothing outside the viewport`).toEqual([]);
      expect(result.clipped.filter(c => !c.startsWith('settings-tabs ')), `${viewport.width}: nothing clipped`).toEqual([]);
      for (const control of ['settings-tab-profile', 'settings-tab-hours', 'settings-tab-rules', 'settings-tab-branding', ...LISTS.map(l => l.testId), 'rules-deposit-none', 'rules-deposit-percentage', 'rules-deposit-amount', 'rules-percent', 'rules-cap',
        'rules-late-percent', 'rules-noshow-percent', 'rules-save', 'rules-history']) {
        const element = id(page, control);
        await element.scrollIntoViewIfNeeded();
        await expect(element, `${control} at ${viewport.width}`).toBeVisible();
      }
      const summary = (await id(page, 'rules-summary').boundingBox())!;
      const form = (await id(page, 'rules-form').boundingBox())!;
      if (viewport.width < 900) {
        expect(summary.y + summary.height, `${viewport.width}: the summary is above the form`).toBeLessThanOrEqual(form.y + 1);
      } else {
        expect(summary.x, `${viewport.width}: the summary is beside the form, on the right`).toBeGreaterThanOrEqual(form.x + form.width - 1);
      }
    });
    await test.step(`the History sheet at ${viewport.width}`, async () => {
      await id(page, 'rules-history').click();
      await expect(id(page, 'rules-history-sheet').getByTestId('rules-history-row')).toHaveCount(10);
      await settled(page);
      const result = await fit(page);
      expect(result.counted).toBeGreaterThan(0);
      expect(result.pageScrolls, `sheet ${viewport.width}: no sideways scroll`).toBe(false);
      expect(result.outside.filter(c => !c.startsWith('settings-tab-')), `sheet ${viewport.width}: nothing outside the viewport`).toEqual([]);
      await id(page, 'rules-history-older').scrollIntoViewIfNeeded();
      await expect(id(page, 'rules-history-older'), `Older at ${viewport.width}`).toBeVisible();
      await page.keyboard.press('Escape');
      await expect(page.getByTestId('rules-history-sheet')).toHaveCount(0);
    });
  }
});

test('TC-F04-S04-026 AC-10: the tab has no serious accessibility violation in its not-saved, field-error, saved, history and conflict states, every select and the deposit choice have their names, the rules are changed and saved by keyboard alone, and it reads well on a dark, the default and a light brand', async ({ accounts, operator, book }) => {
  const { salon, owner } = await newSalon(operator, accounts);
  const { page } = await openRules(accounts, owner);
  await test.step('names: each select by its label, the deposit by its heading, each share kept by its context', async () => {
    await choose(page, 'rules-late', 'share');
    await choose(page, 'rules-noshow', 'share');
    for (const list of LISTS) await expect(page.getByRole('combobox', { name: list.label, exact: true }), list.label).toHaveAttribute('data-testid', list.testId);
    await expect(page.getByRole('radiogroup', { name: 'Deposit', exact: true })).toHaveAttribute('data-testid', 'rules-deposit');
    const late = await id(page, 'rules-late-percent').evaluate(el => (el as HTMLInputElement).labels?.[0]?.textContent + '|' + el.getAttribute('aria-label'));
    const noShow = await id(page, 'rules-noshow-percent').evaluate(el => (el as HTMLInputElement).labels?.[0]?.textContent + '|' + el.getAttribute('aria-label'));
    expect(late, 'the two "Share kept" fields have different names').not.toBe(noShow);
    await expect(page.getByRole('textbox', { name: /Share kept.*late cancellation/ })).toHaveAttribute('data-testid', 'rules-late-percent');
    await expect(page.getByRole('textbox', { name: /Share kept.*no-show/ })).toHaveAttribute('data-testid', 'rules-noshow-percent');
  });
  await test.step('no serious violation: not saved yet, a field error, an open list, the history', async () => {
    await accessible(page, 'not saved yet');
    await id(page, 'rules-percent').fill('101');
    await expect(id(page, 'rules-percent-error')).toBeVisible();
    await accessible(page, 'field error');
    await id(page, 'rules-percent').fill('20');
    await id(page, 'rules-save').click();
    await expect(page.getByText(SAVED_TOAST)).toBeVisible();
    await accessible(page, 'saved');
    await id(page, 'rules-history').click();
    await expect(id(page, 'rules-history-sheet').getByTestId('rules-history-row')).toHaveCount(1);
    await settled(page);
    await accessible(page, 'history sheet');
    await page.keyboard.press('Escape');
    await expect(page.getByTestId('rules-history-sheet')).toHaveCount(0);
    await book.save(salon, owner.tokens, distinct(), 1);
    await choose(page, 'rules-free', '2');
    await id(page, 'rules-save').click();
    await expect(id(page, 'rules-conflict')).toBeVisible();
    await accessible(page, 'conflict');
  });
  await test.step('keyboard alone: a list by Enter and arrows, the deposit kind by arrows, Save by Enter, with a visible focus', async () => {
    await page.reload();
    await ready(page);
    await page.locator('body').click({ position: { x: 1, y: 1 } });
    await tabTo(page, id(page, 'rules-interval'));
    const ring = await focusIndicator(id(page, 'rules-interval'));
    expect(ring.focusVisible, 'the focus is visible on the list').toBe(true);
    await page.keyboard.press('Enter');
    await expect(page.getByRole('listbox')).toBeVisible();
    await expect(page.getByRole('option', { name: '15 minutes', exact: true }), 'the open list starts on the current choice').toBeFocused();
    await page.keyboard.press('ArrowDown');
    await expect(page.getByRole('option', { name: '20 minutes', exact: true }), 'the arrow moves to the next choice').toBeFocused();
    await page.keyboard.press('Enter');
    await expect(page.getByRole('listbox')).toHaveCount(0);
    await expect(id(page, 'rules-interval'), 'the next choice by the arrow').toHaveText('20 minutes');
    await tabTo(page, page.getByRole('radio', { checked: true }).and(page.locator('[data-testid^="rules-deposit-"]')));
    await page.keyboard.press('ArrowRight');
    // The roving focus moves after the key press (Radix moves it on the next task): wait for it before choosing.
    await expect(id(page, 'rules-deposit-amount'), 'the arrow moves to Fixed amount').toBeFocused();
    await page.keyboard.press('Space');
    await expect(id(page, 'rules-deposit-amount'), 'Fixed amount by the arrow').toHaveAttribute('data-state', 'on');
    await tabTo(page, id(page, 'rules-amount'));
    await page.keyboard.press('ControlOrMeta+a');
    await page.keyboard.type('2500');
    await tabTo(page, id(page, 'rules-save'));
    await page.keyboard.press('Enter');
    await expect(page.getByText(SAVED_TOAST)).toBeVisible();
    const saved = (await book.settings(salon, owner.tokens)).rules!;
    expect(saved.slotIntervalMinutes).toBe(20);
    expect(saved.deposit).toEqual({ kind: 'amount', amountMinor: 250000 });
  });
  await test.step('on the default pink and a light brand: no serious violation, and the chosen deposit chip and Save are readable', async () => {
    // docs/design/ui-ux-standards.md: every salon screen with a dark and a light brand (the salon above is the neutral dark one).
    for (const salonOf of [alpha, gamma]) {
      const manager = await accounts.identity.staff(salonOf, 'MANAGER');
      const { page: branded } = await openRules(accounts, manager);
      await accessible(branded, `${salonOf.slug}'s brand`);
      const chosen = branded.locator('[data-testid^="rules-deposit-"][data-state="on"]').filter({ visible: true });
      await expect(chosen).toHaveCount(1);
      expect(await drawnContrast(chosen), `${salonOf.slug}: the chosen deposit chip is readable`).toBeGreaterThanOrEqual(4.5);
      await choose(branded, 'rules-free', (await id(branded, 'rules-free').innerText()).startsWith('2 ') ? '6' : '2');
      await expect(id(branded, 'rules-save')).toBeEnabled();
      expect(await drawnContrast(id(branded, 'rules-save')), `${salonOf.slug}: Save is readable`).toBeGreaterThanOrEqual(4.5);
    }
  });
});
