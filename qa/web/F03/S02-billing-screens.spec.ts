/**
 * F03-S02 billing screens: the workspace banner and Plan and billing page by role and state, the
 * console subscription card, invoice list and sheets with their states, the width matrix,
 * accessibility and keyboard use, and a stopped or silent database.
 * Cases TC-F03-S02-021, -024 to -027 (cases.md).
 *
 * States are reached through the test clock and the billing run-job hook; staff and operator
 * authority is real and API-issued, bridged into the browser's cookies. Test ids are the S02
 * handoff's (`console-*`, `billing-*`, `workspace-billing-banner` with `data-notice`).
 */
import { execFileSync } from 'node:child_process';
import type { BrowserContext, Page } from '@playwright/test';

import { billAt, crossBoundary, firstInvoice } from '../../shared/billing';
import {
  test, expect, WIDTHS, consoleContext, consoleUrl, expectAccessible, expectFits, expectSheetFocus,
} from '../../shared/console-browser';
import type { SignedStaff } from '../../shared/identity';
import { localService } from '../../shared/identity-runtime';
import { DAY, SECOND, ownerSession, plusMs, type Operator, type TestClock } from '../../shared/operator';
import type { Identity } from '../../shared/identity';
import type { AccountBrowser } from '../../shared/identity-browser';

const id = (n: number) => `TC-F03-S02-${String(n).padStart(3, '0')}`;
const LIMIT_MS = 5_000;

async function billedSalon(operator: Operator, clock: TestClock, createdAt: string, amount = 250050) {
  await clock.set(createdAt);
  const admin = await operator.actor();
  const plan = await operator.plan(admin, { staffSeats: 6 });
  const made = await operator.salon(admin, plan.id, { subscription: { monthlyAmount: amount, paymentMethod: 'bank_transfer' } });
  return { admin, plan, ...made };
}

async function workspacePage(accounts: AccountBrowser, actor: SignedStaff, route: string, width = 1280): Promise<{ context: BrowserContext; page: Page }> {
  const context = await accounts.context(actor.salon, [actor], { width });
  const page = await context.newPage();
  await page.goto(route);
  return { context, page };
}

/** The banner on two workspace pages for one signed-in staff member, or its absence. */
async function bannerOn(accounts: AccountBrowser, actor: SignedStaff, notice: string | null) {
  for (const route of ['/workspace/account', '/workspace/staff']) {
    const { context, page } = await workspacePage(accounts, actor, route);
    await expect(page.getByTestId('workspace-main')).toBeVisible();
    if (notice === null) await expect(page.getByTestId('workspace-billing-banner'), `${route}: no banner`).toHaveCount(0);
    else await expect(page.getByTestId('workspace-billing-banner'), `${route}: ${notice} banner`).toHaveAttribute('data-notice', notice);
    await context.close();
  }
}

async function signedIn(identity: Identity, owner: Awaited<ReturnType<typeof ownerSession>>, receptionist: { account: SignedStaff['account'] }) {
  return { owner: await identity.login(owner.salon, owner.account), receptionist: await identity.login(owner.salon, receptionist.account) };
}

test(`${id(21)} Every staff member sees the state banner and the Owner sees Plan and billing`, async ({ operator, identity, clock, accounts }) => {
  test.setTimeout(300_000);
  const { admin, created, input, salon, plan } = await billedSalon(operator, clock, '2027-01-04T07:00:00Z');
  const owner = await ownerSession(identity, salon, input);
  const receptionist = await identity.staff(salon, 'RECEPTIONIST');
  const trialEnd = (await operator.subscription(admin, created.id)).trialEndsAt;
  await clock.set(plusMs(trialEnd, -3 * DAY - SECOND));
  let actors = await signedIn(identity, owner, receptionist);
  for (const actor of [actors.owner, actors.receptionist]) await bannerOn(accounts, actor, null);
  await clock.set(plusMs(trialEnd, -3 * DAY + 60 * SECOND));
  actors = await signedIn(identity, owner, receptionist);
  for (const actor of [actors.owner, actors.receptionist]) await bannerOn(accounts, actor, 'trial_ending');
  const invoice = await firstInvoice(operator, clock, admin, created.id);
  await crossBoundary(operator, clock, admin, invoice, 'due');
  actors = await signedIn(identity, owner, receptionist);
  for (const actor of [actors.owner, actors.receptionist]) await bannerOn(accounts, actor, 'past_due');
  await crossBoundary(operator, clock, admin, invoice, 'read_only');
  actors = await signedIn(identity, owner, receptionist);
  for (const actor of [actors.owner, actors.receptionist]) await bannerOn(accounts, actor, 'read_only');
  const { context: staffContext, page: staffPage } = await workspacePage(accounts, actors.owner, '/workspace/staff');
  await expect(staffPage.getByTestId('account-read-only'), 'mutating actions disabled with the reason').toBeVisible();
  await expect(staffPage.getByTestId('workspace-billing-banner')).toHaveAttribute('role', 'alert');
  await staffContext.close();
  const { context, page } = await workspacePage(accounts, actors.owner, '/workspace/billing');
  await expect(page.getByTestId('billing-page')).toBeVisible();
  await expect(page.getByTestId('billing-plan-name')).toHaveText(plan.name);
  await expect(page.getByTestId('billing-state')).toHaveAttribute('data-state', 'read_only');
  await expect(page.getByTestId('billing-staff-seats')).toContainText('2');
  await expect(page.getByTestId('billing-staff-seats')).toContainText('6');
  await expect(page.getByTestId('billing-invoice-row'), 'the Owner sees the invoice').toHaveCount(1);
  await context.close();
  const { context: other, page: refused } = await workspacePage(accounts, actors.receptionist, '/workspace/billing');
  await expect(refused.getByTestId('billing-page'), 'the Receptionist is refused Plan and billing').toHaveCount(0);
  await expect(refused.getByTestId('workspace-billing'), 'and has no navigation item').toHaveCount(0);
  await other.close();
});

test(`${id(24)} Console and workspace billing screens show every state with correct money formatting`, async ({ browser, operator, identity, clock, accounts }) => {
  test.setTimeout(240_000);
  const { admin, created, input, salon } = await billedSalon(operator, clock, '2027-01-05T07:00:00Z');
  const exceeded = await operator.plan(admin, { staffSeats: 1 });
  await identity.seed(salon, [{ email: `seat-${Date.now()}@example.invalid`, mobile: '', password: 'QA seat password long', role: 'MANAGER' }]);
  const legacy = await operator.unbilledSalon();
  const context = await consoleContext(browser, admin);
  const page = await context.newPage();
  // Unbilled: no subscription, Start subscription offered.
  await page.goto(consoleUrl(`/console/salons/${legacy.id}`));
  await expect(page.getByTestId('console-subscription-none')).toBeVisible();
  await expect(page.getByTestId('console-start-subscription')).toBeVisible();
  // Trialing card with formatted money and an empty invoice list.
  await page.goto(consoleUrl(`/console/salons/${created.id}`));
  await expect(page.getByTestId('console-subscription-card')).toBeVisible();
  await expect(page.getByTestId('console-subscription-state')).toHaveAttribute('data-state', 'trialing');
  await expect(page.getByTestId('console-subscription-card'), 'money in the salon currency and locale').toContainText('Rs. 2,500.50');
  await page.goto(consoleUrl(`/console/salons/${created.id}/invoices`));
  await expect(page.getByTestId('console-invoices-empty')).toBeVisible();
  // Plan move refused above the limit.
  await page.goto(consoleUrl(`/console/salons/${created.id}`));
  await page.getByTestId('console-move-plan').click();
  await page.getByTestId('console-move-plan-id').click();
  await page.getByTestId(`console-move-plan-id-option-${exceeded.id}`).click();
  await page.getByTestId('console-move-plan-submit').click();
  await expect(page.getByTestId('console-move-plan-exceeded'), 'the entitlement refusal').toBeVisible();
  await page.keyboard.press('Escape');
  // Conflict: the subscription changed in another tab.
  await page.getByTestId('console-change-subscription').click();
  await expect(page.getByTestId('console-change-subscription-sheet')).toBeVisible();
  const subscription = await operator.subscription(admin, created.id);
  expect((await operator.changeSubscription(admin, created.id, { version: subscription.version, monthlyAmount: 260000 })).status()).toBe(200);
  await page.getByTestId('console-monthly-amount').fill('2700');
  await page.getByTestId('console-change-subscription-submit').click();
  await expect(page.getByTestId('console-error')).toHaveAttribute('data-code', 'conflict');
  await page.keyboard.press('Escape');
  // Invoice issued: an open invoice, a refused and then an accepted transfer.
  const invoice = await firstInvoice(operator, clock, admin, created.id);
  await page.goto(consoleUrl(`/console/salons/${created.id}/invoices`));
  const row = page.locator(`[data-testid="console-invoice-row"][data-id="${invoice.id}"]`);
  await expect(row.getByTestId('console-invoice-status')).toHaveAttribute('data-status', 'open');
  await expect(row.getByTestId('console-invoice-amount')).toHaveText(/Rs\. 2,600/);
  await page.locator(`[data-testid="console-record-transfer"][data-id="${invoice.id}"]`).click();
  await page.getByTestId('console-transfer-reference').fill('QA-UI-REF');
  await page.getByTestId('console-transfer-amount').fill('1');
  await page.getByTestId('console-record-transfer-submit').click();
  await expect(page.getByTestId('console-transfer-amount'), 'validation names the amount').toHaveAttribute('aria-invalid', 'true');
  await page.getByTestId('console-transfer-amount').fill('2600');
  await page.getByTestId('console-record-transfer-submit').click();
  await expect(page.getByTestId('console-transfer-recorded')).toBeVisible();
  await expect(row.getByTestId('console-invoice-status')).toHaveAttribute('data-status', 'paid');
  expect((await operator.invoice(await operator.renew(admin), invoice.id)).status, 'the transfer is visible through the API').toBe('paid');
  await context.close();
  // Workspace Plan and billing (English only: decision 051).
  const owner = await identity.login(salon, (await ownerSession(identity, salon, input)).account);
  const { context: ws, page: billing } = await workspacePage(accounts, owner, '/workspace/billing');
  await expect(billing.getByTestId('billing-invoice-row')).toHaveCount(1);
  await expect(billing.getByTestId('billing-invoice-status')).toContainText(/paid/i);
  await expect(billing.getByTestId('billing-invoice-amount')).toHaveText(/Rs\. 2,600/);
  await ws.close();
});

test(`${id(25)} Billing screens fit every width without clipping or horizontal scroll`, async ({ browser, operator, identity, clock, accounts }) => {
  test.setTimeout(300_000);
  const { admin, created, input, salon } = await billedSalon(operator, clock, '2027-01-06T07:00:00Z');
  await firstInvoice(operator, clock, admin, created.id);
  for (let n = 0; n < 5; n++) await billAt(operator, clock, (await operator.subscription(await operator.renew(admin), created.id)).nextInvoiceAt);
  await operator.renew(admin);
  const legacy = await operator.unbilledSalon();
  const owner = await identity.login(salon, (await ownerSession(identity, salon, input)).account);
  for (const viewport of WIDTHS) {
    const context = await consoleContext(browser, await operator.actor(), { width: viewport.width });
    const page = await context.newPage();
    await page.goto(consoleUrl(`/console/salons/${created.id}`));
    await expect(page.getByTestId('console-subscription-card')).toBeVisible();
    await expectFits(page, `subscription card at ${viewport.width}`);
    for (const sheet of ['console-change-subscription', 'console-move-plan']) {
      await page.getByTestId(sheet).click();
      await expect(page.getByTestId(`${sheet}-sheet`)).toBeVisible();
      await expectFits(page, `${sheet} at ${viewport.width}`);
      await page.keyboard.press('Escape');
    }
    await page.goto(consoleUrl(`/console/salons/${created.id}/invoices`));
    await expect(page.getByTestId('console-invoice-row').first()).toBeVisible();
    await expectFits(page, `long invoice list at ${viewport.width}`);
    await page.getByTestId('console-record-transfer').first().click();
    await expect(page.getByTestId('console-record-transfer-sheet')).toBeVisible();
    await expectFits(page, `record transfer at ${viewport.width}`);
    await page.keyboard.press('Escape');
    await page.goto(consoleUrl(`/console/salons/${legacy.id}/invoices`));
    await expect(page.getByTestId('console-invoices-empty')).toBeVisible();
    await expectFits(page, `empty invoice list at ${viewport.width}`);
    await context.close();
    const { context: ws, page: billing } = await workspacePage(accounts, owner, '/workspace/billing', viewport.width);
    await expect(billing.getByTestId('billing-page')).toBeVisible();
    await expect(billing.getByTestId('workspace-billing-banner')).toBeVisible();
    await expectFits(billing, `Plan and billing with banner at ${viewport.width}`);
    await ws.close();
  }
});

test(`${id(26)} Billing screens pass the automated scan and keyboard-only use`, async ({ browser, operator, identity, clock, accounts }) => {
  test.setTimeout(180_000);
  const { admin, created, input, salon } = await billedSalon(operator, clock, '2027-01-07T07:00:00Z');
  const larger = await operator.plan(admin, { staffSeats: 12 });
  const invoice = await firstInvoice(operator, clock, admin, created.id);
  const context = await consoleContext(browser, admin);
  const page = await context.newPage();
  await page.goto(consoleUrl(`/console/salons/${created.id}`));
  await expect(page.getByTestId('console-subscription-card')).toBeVisible();
  await expectAccessible(page, 'subscription card');
  for (const sheet of ['console-change-subscription', 'console-move-plan']) {
    await expectSheetFocus(page, page.getByTestId(sheet), page.getByTestId(`${sheet}-sheet`));
  }
  // A plan move by keyboard.
  await page.getByTestId('console-move-plan').focus();
  await page.keyboard.press('Enter');
  await page.getByTestId('console-move-plan-id').focus();
  await page.keyboard.press('Enter');
  await page.getByTestId(`console-move-plan-id-option-${larger.id}`).focus();
  await page.keyboard.press('Enter');
  await page.getByTestId('console-move-plan-submit').focus();
  await page.keyboard.press('Enter');
  await expect(page.getByTestId('console-move-plan-sheet')).toBeHidden();
  expect((await operator.salonDetail(admin, created.id)).plan!.id, 'moved by keyboard').toBe(larger.id);
  // A transfer by keyboard.
  await page.goto(consoleUrl(`/console/salons/${created.id}/invoices`));
  await expectAccessible(page, 'invoice list');
  await page.locator(`[data-testid="console-record-transfer"][data-id="${invoice.id}"]`).focus();
  await page.keyboard.press('Enter');
  await expectAccessible(page, 'record transfer sheet');
  await page.getByTestId('console-transfer-reference').focus();
  await page.keyboard.type('QA-KEYBOARD');
  await page.getByTestId('console-transfer-amount').focus();
  await page.keyboard.type('2500.50');
  await page.getByTestId('console-record-transfer-submit').focus();
  await page.keyboard.press('Enter');
  await expect(page.getByTestId('console-transfer-recorded')).toBeVisible();
  await context.close();
  const owner = await identity.login(salon, (await ownerSession(identity, salon, input)).account);
  const { context: ws, page: billing } = await workspacePage(accounts, owner, '/workspace/billing');
  await expect(billing.getByTestId('billing-page')).toBeVisible();
  await expectAccessible(billing, 'Plan and billing');
  await ws.close();
});

test(`${id(27)} A stopped or silent database is reported on billing screens within five seconds and recovers`, async ({ browser, operator, identity, clock, accounts }) => {
  test.setTimeout(300_000);
  const { admin, created, input, salon } = await billedSalon(operator, clock, '2027-01-08T07:00:00Z');
  const owner = await identity.login(salon, (await ownerSession(identity, salon, input)).account);
  const consoleSide = await consoleContext(browser, admin);
  const card = await consoleSide.newPage();
  const { context: ws, page: billing } = await workspacePage(accounts, owner, '/workspace/billing');
  await card.goto(consoleUrl(`/console/salons/${created.id}`));
  await expect(card.getByTestId('console-subscription-card')).toBeVisible();
  await expect(billing.getByTestId('billing-page')).toBeVisible();
  const accepting = () => { try { execFileSync('docker', ['exec', 'salon-postgres', 'pg_isready', '-q'], { timeout: 3_000 }); return true; } catch { return false; } };
  for (const mode of ['down', 'silent'] as const) {
    await test.step(mode, async () => {
      try {
        localService(mode === 'down' ? 'stop' : 'pause', 'postgres');
        for (const [page, route, error] of [[card, consoleUrl(`/console/salons/${created.id}`), 'console-error'], [billing, '/workspace/billing', 'billing-error']] as const) {
          const started = Date.now();
          await page.goto(route, { timeout: 30_000 });
          await expect(page.getByTestId(error), `${mode}: ${route} reports unavailable`).toBeVisible({ timeout: LIMIT_MS });
          expect(Date.now() - started, `${mode}: within five seconds`).toBeLessThanOrEqual(LIMIT_MS);
        }
      } finally {
        localService(mode === 'down' ? 'start' : 'resume', 'postgres');
      }
      await expect.poll(accepting, { timeout: 60_000, intervals: [100] }).toBe(true);
      const back = Date.now();
      await expect.poll(async () => { await card.goto(consoleUrl(`/console/salons/${created.id}`)); return card.getByTestId('console-subscription-card').isVisible(); },
        { timeout: LIMIT_MS, intervals: [250] }).toBe(true);
      await expect.poll(async () => { await billing.goto('/workspace/billing'); return billing.getByTestId('billing-page').isVisible(); },
        { timeout: LIMIT_MS, intervals: [250] }).toBe(true);
      expect(Date.now() - back, `${mode}: recovered within five seconds of the database`).toBeLessThanOrEqual(2 * LIMIT_MS);
    });
  }
  await consoleSide.close();
  await ws.close();
});

