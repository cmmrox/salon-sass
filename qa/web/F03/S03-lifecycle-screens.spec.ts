/**
 * F03-S03 lifecycle screens: a suspended salon's workspace, the console lifecycle card, sheets,
 * history and export card, and the Owner's download page on the platform host; widths,
 * accessibility and keyboard use. Cases TC-F03-S03-002, -015 to -017 (cases.md).
 *
 * Test ids are the S03 handoff's (`console-lifecycle-*`, `console-{suspend,reactivate,close}*`,
 * `console-confirm-slug`, `console-export-*`, `exports-*`, `console-salon-status[data-status]`).
 */
import type { Page } from '@playwright/test';

import {
  test, expect, WIDTHS, consoleContext, consoleUrl, expectAccessible, expectFits, expectSheetFocus, platformUrl,
} from '../../shared/console-browser';
import { webUrl } from '../../shared/env';
import { localService } from '../../shared/identity-runtime';
import { ownerSession, operatorJson, type Operator, type OperatorActor, type SalonDetail } from '../../shared/operator';
import { salonUrl } from '../../shared/tenants';

const id = (n: number) => `TC-F03-S03-${String(n).padStart(3, '0')}`;

async function salonWithHistory(operator: Operator, admin: OperatorActor, cycles = 0) {
  const plan = await operator.plan(admin);
  const made = await operator.salon(admin, plan.id);
  for (let n = 0; n < cycles; n++) {
    await operatorJson(await operator.suspend(admin, made.created.id, await operator.version(admin, made.created.id), `QA cycle ${n} suspension`), 'salon');
    await operatorJson(await operator.reactivate(admin, made.created.id, await operator.version(admin, made.created.id), `QA cycle ${n} reactivation`), 'salon');
  }
  return made;
}

async function readyExportToken(operator: Operator, admin: OperatorActor, created: { id: string; slug: string }, ownerEmail: string) {
  await operatorJson<SalonDetail>(await operator.close(admin, created.id, await operator.version(admin, created.id), created.slug, 'QA closure in the browser'), 'salon');
  await operator.runExports();
  const [email] = await operator.emailsOfKind(ownerEmail, 'export.ready', 1);
  return email.links[0].split('#')[1];
}

async function detail(page: Page, salonId: string) {
  await page.goto(consoleUrl(`/console/salons/${salonId}`));
  await expect(page.getByTestId('console-lifecycle-card')).toBeVisible();
}

test(`${id(2)} A suspended salon's workspace shows temporarily unavailable`, async ({ operator, identity, accounts }) => {
  const admin = await operator.actor();
  const { created, input, salon } = await salonWithHistory(operator, admin);
  const owner = await ownerSession(identity, salon, input);
  await operatorJson(await operator.suspend(admin, created.id, await operator.version(admin, created.id)), 'salon');
  const context = await accounts.context(salon, [owner]);
  for (const route of ['/workspace/sign-in', '/workspace/account']) {
    const page = await context.newPage();
    const response = await page.goto(`${salonUrl(salon, webUrl)}${route}`);
    expect(response?.status(), `${route}: 503`).toBe(503);
    await expect(page.getByTestId('salon-unavailable'), `${route}: temporarily unavailable`).toBeVisible();
    expect(await page.content(), 'no salon data').not.toContain(input.name);
  }
  await context.close();
});

test(`${id(15)} Console lifecycle actions and the download page show every state and make closure visibly irreversible`, async ({ browser, operator }) => {
  test.setTimeout(180_000);
  const admin = await operator.actor();
  const { created, input } = await salonWithHistory(operator, admin);
  const context = await consoleContext(browser, admin);
  const page = await context.newPage();
  await detail(page, created.id);
  await expect(page.getByTestId('console-salon-status')).toHaveAttribute('data-status', 'active');
  await expect(page.getByTestId('console-reactivate'), 'actions by state').toHaveCount(0);
  // Suspend: validation, then success with a history row.
  await page.getByTestId('console-suspend').click();
  await page.getByTestId('console-suspend-reason').fill('too short');
  await page.getByTestId('console-suspend-submit').click();
  await expect(page.getByTestId('console-suspend-reason')).toHaveAttribute('aria-invalid', 'true');
  await page.getByTestId('console-suspend-reason').fill('QA browser suspension reason');
  await page.getByTestId('console-suspend-submit').click();
  await expect(page.getByTestId('console-lifecycle-suspended')).toBeVisible();
  await expect(page.getByTestId('console-salon-status')).toHaveAttribute('data-status', 'suspended');
  await expect(page.locator('[data-testid="console-lifecycle-row"][data-kind="suspended"]')).toHaveCount(1);
  // Conflict: the salon changed in another tab before reactivation.
  await page.getByTestId('console-reactivate').click();
  await page.getByTestId('console-reactivate-reason').fill('QA browser reactivation reason');
  await operatorJson(await operator.reactivate(admin, created.id, await operator.version(admin, created.id), 'QA reactivated elsewhere'), 'salon');
  await page.getByTestId('console-reactivate-submit').click();
  await expect(page.getByTestId('console-error')).toHaveAttribute('data-code', /conflict|invalid_state/);
  await page.keyboard.press('Escape');
  // Close: consequences, the 30-day date, the slug and a reason; a wrong slug is refused.
  await detail(page, created.id);
  await page.getByTestId('console-close').click();
  await expect(page.getByTestId('console-close-sheet')).toBeVisible();
  await expect(page.getByTestId('console-close-consequences'), 'closure is stated to be irreversible').toBeVisible();
  await expect(page.getByTestId('console-close-consequences')).toContainText(created.hostname);
  await expect(page.getByTestId('console-close-deletion-date')).toBeVisible();
  await page.getByTestId('console-confirm-slug').fill(created.slug.toUpperCase());
  await page.getByTestId('console-close-reason').fill('QA browser closure reason');
  await page.getByTestId('console-close-submit').click();
  await expect(page.getByTestId('console-confirm-slug')).toHaveAttribute('aria-invalid', 'true');
  expect((await operator.salonDetail(admin, created.id)).status, 'the wrong slug closed nothing').toBe('active');
  await page.getByTestId('console-confirm-slug').fill(created.slug);
  await page.getByTestId('console-close-submit').click();
  await expect(page.getByTestId('console-lifecycle-closed')).toBeVisible();
  await expect(page.getByTestId('console-suspend'), 'no action after closure').toHaveCount(0);
  await expect(page.getByTestId('console-export-status')).toHaveAttribute('data-status', 'pending');
  await operator.runExports();
  await page.getByTestId('console-export-refresh').click();
  await expect(page.getByTestId('console-export-status')).toHaveAttribute('data-status', 'ready');
  const view = await operator.exportStatus(admin, created.id);
  await expect(page.getByTestId('console-export-sha256')).toHaveText(view.sha256!);
  const download = page.waitForEvent('download');
  await page.getByTestId('console-export-download').click();
  expect((await download).suggestedFilename()).toMatch(/\.zip$/);
  // Unavailable: the API stopped while the export card loads.
  try {
    localService('stop', 'api');
    await page.goto(consoleUrl(`/console/salons/${created.id}`));
    await expect(page.getByTestId('console-error')).toBeVisible({ timeout: 5_000 });
  } finally {
    localService('start', 'api');
  }
  await context.close();
  // The Owner's page on the platform host: ready, then an unknown link.
  const [email] = await operator.emailsOfKind(input.owner.email, 'export.ready', 1);
  const token = email.links[0].split('#')[1];
  const owner = await browser.newContext({ viewport: { width: 390, height: 900 } });
  const ownerPage = await owner.newPage();
  await ownerPage.goto(platformUrl(`/exports#${token}`));
  await expect(ownerPage.getByTestId('exports-download')).toBeVisible();
  expect(new URL(ownerPage.url()).hash, 'the token leaves the address bar at once').toBe('');
  await expect(ownerPage.getByTestId('exports-delete-at')).toBeVisible();
  await expect(ownerPage.getByTestId('exports-sha256')).toHaveText(view.sha256!);
  await ownerPage.goto(platformUrl(`/exports#${token.slice(0, -2)}xx`));
  await expect(ownerPage.getByTestId('exports-unknown')).toBeVisible();
  await ownerPage.goto(platformUrl('/exports'));
  await expect(ownerPage.getByTestId('exports-no-link')).toBeVisible();
  await owner.close();
});

test(`${id(16)} Lifecycle screens and the download page fit every width`, async ({ browser, operator }) => {
  test.setTimeout(300_000);
  const admin = await operator.actor();
  const active = await salonWithHistory(operator, admin, 6);
  const closed = await salonWithHistory(operator, admin);
  const token = await readyExportToken(operator, admin, closed.created, closed.input.owner.email);
  for (const viewport of WIDTHS) {
    const context = await consoleContext(browser, await operator.actor(), { width: viewport.width });
    const page = await context.newPage();
    await detail(page, active.created.id);
    await expect(page.getByTestId('console-lifecycle-row').first()).toBeVisible();
    await expectFits(page, `long history at ${viewport.width}`);
    for (const sheet of ['console-suspend', 'console-close']) {
      await page.getByTestId(sheet).click();
      await expect(page.getByTestId(`${sheet}-sheet`)).toBeVisible();
      await expectFits(page, `${sheet} at ${viewport.width}`);
      await page.keyboard.press('Escape');
    }
    await detail(page, closed.created.id);
    await expect(page.getByTestId('console-export-card')).toBeVisible();
    await expectFits(page, `export card at ${viewport.width}`);
    await context.close();
    const anonymous = await browser.newContext({ viewport: { width: viewport.width, height: 900 } });
    const ownerPage = await anonymous.newPage();
    await ownerPage.goto(platformUrl(`/exports#${token}`));
    await expect(ownerPage.getByTestId('exports-download')).toBeVisible();
    await expectFits(ownerPage, `download page at ${viewport.width}`);
    await anonymous.close();
  }
});

test(`${id(17)} Lifecycle screens pass the automated scan and keyboard use with focus containment`, async ({ browser, operator }) => {
  const admin = await operator.actor();
  const target = await salonWithHistory(operator, admin, 1);
  const context = await consoleContext(browser, admin);
  const page = await context.newPage();
  await detail(page, target.created.id);
  await expectAccessible(page, 'lifecycle card and history');
  for (const sheet of ['console-suspend', 'console-close']) await expectSheetFocus(page, page.getByTestId(sheet), page.getByTestId(`${sheet}-sheet`));
  // Close by keyboard.
  await page.getByTestId('console-close').focus();
  await page.keyboard.press('Enter');
  await expectAccessible(page, 'close sheet');
  await page.getByTestId('console-confirm-slug').focus();
  await page.keyboard.type(target.created.slug);
  await page.getByTestId('console-close-reason').focus();
  await page.keyboard.type('QA keyboard closure reason');
  await page.getByTestId('console-close-submit').focus();
  await page.keyboard.press('Enter');
  await expect(page.getByTestId('console-lifecycle-closed')).toBeVisible();
  expect((await operator.salonDetail(admin, target.created.id)).status).toBe('closed');
  await operator.runExports();
  await page.getByTestId('console-export-refresh').click();
  await expect(page.getByTestId('console-export-download')).toBeVisible();
  await page.getByTestId('console-export-download').focus();
  await expect(page.getByTestId('console-export-download'), 'download reachable by keyboard').toBeFocused();
  await expectAccessible(page, 'export card');
  await context.close();
  const [email] = await operator.emailsOfKind(target.input.owner.email, 'export.ready', 1);
  const anonymous = await browser.newContext();
  const ownerPage = await anonymous.newPage();
  await ownerPage.goto(platformUrl(`/exports#${email.links[0].split('#')[1]}`));
  await expect(ownerPage.getByTestId('exports-download')).toBeVisible();
  await expectAccessible(ownerPage, 'download page');
  await anonymous.close();
});
