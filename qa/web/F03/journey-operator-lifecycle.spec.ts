/**
 * F03 journey TC-F03-001: an operator takes one salon from creation through billing, suspension,
 * support and closure in the console, and the values passing between the stages stay the same.
 * Stage rules are not repeated: each step's outcome is checked once more through the API.
 *
 * The support step uses the S04 test ids, credential shape and schemas the S04 handoff names
 * (qa/shared/operator.ts PENDING_S04).
 */
import { firstInvoice } from '../../shared/billing';
import {
  test, expect, consoleContext, consoleUrl, platformUrl,
} from '../../shared/console-browser';
import { syntheticAccount } from '../../shared/identity';
import { unzip } from '../../shared/zip';
import {
  ownerSession, operatorProblem, staffTokens, supportJson, supportTestId as tid, uniqueSlug, strongPassword,
  type SupportStart,
} from '../../shared/operator';
import { webUrl } from '../../shared/env';
import { salonUrl } from '../../shared/tenants';

test('TC-F03-001 An operator takes one salon from creation through billing, suspension, support and closure in the console', async ({ browser, operator, identity, clock, accounts }) => {
  test.setTimeout(420_000);
  await clock.set('2027-03-01T05:00:00Z');
  const admin = await operator.actor();
  const consoleSide = await consoleContext(browser, admin);
  const page = await consoleSide.newPage();

  // S01: a two-seat plan and a bank-transfer salon, defined in the console.
  const planKey = uniqueSlug('journey');
  await page.goto(consoleUrl('/console/plans'));
  await page.getByTestId('console-create-plan').click();
  await page.getByTestId('console-plan-key').fill(planKey);
  await page.getByTestId('console-plan-name').fill('QA journey plan');
  await page.getByTestId('console-staff-seats').fill('2');
  await page.getByTestId('console-branches').fill('1');
  await page.getByTestId('console-message-allowance').fill('10');
  await page.getByTestId('console-create-plan-submit').click();
  await expect(page.getByTestId('console-plan-created')).toBeVisible();
  const plan = (await operator.planPage(admin, `?q=${planKey}`)).items[0];
  expect(plan.staffSeats).toBe(2);
  const slug = uniqueSlug('journey');
  const owner = { name: 'QA Journey Owner', email: `owner-${slug}@example.invalid`, password: strongPassword() };
  await page.goto(consoleUrl('/console/salons'));
  await page.getByTestId('console-create-salon').click();
  await page.getByTestId('console-salon-name').fill(`QA Journey ${slug}`);
  await page.getByTestId('console-slug').fill(slug);
  await page.getByTestId('console-plan-id').click();
  await page.getByTestId(`console-plan-id-option-${plan.id}`).click();
  await page.getByTestId('console-monthly-amount').fill('1500');
  await page.getByTestId('console-payment-method').click();
  await page.getByTestId('console-payment-method-option-bank_transfer').click();
  await page.getByTestId('console-owner-name').fill(owner.name);
  await page.getByTestId('console-owner-email').fill(owner.email);
  await page.getByTestId('console-owner-password').fill(owner.password);
  await page.getByTestId('console-create-salon-submit').click();
  await expect(page.getByTestId('console-salon-created-hostname')).toHaveText(`${slug}.localhost`);
  const summary = (await operator.salonPage(admin, `?q=${slug}`)).items[0];
  const salon = { slug, id: summary.id, name: `QA Journey ${slug}`, brandColour: '', hostname: summary.hostname, timeZone: 'Asia/Colombo' };
  const input = { owner } as Parameters<typeof ownerSession>[2];
  const ownerSigned = await ownerSession(identity, salon, input);
  const ownerNow = staffTokens(identity, salon, ownerSigned.account, ownerSigned.tokens);

  // S02: the plan defined in S01 is the one enforced and billed.
  expect((await identity.call(salon, 'POST', '/staff', syntheticAccount('MANAGER'), await ownerNow())).status()).toBe(201);
  await operatorProblem(await identity.call(salon, 'POST', '/staff', syntheticAccount('RECEPTIONIST'), await ownerNow()), 409, 'entitlement_exceeded');
  const invoice = await firstInvoice(operator, clock, admin, salon.id);
  expect((await operator.invoice(admin, invoice.id)).planKey, 'the S01 plan is billed').toBe(planKey);
  const fresh = await consoleContext(browser, await operator.actor());
  const billingPage = await fresh.newPage();
  await billingPage.goto(consoleUrl(`/console/salons/${salon.id}/invoices`));
  await billingPage.locator(`[data-testid="console-record-transfer"][data-id="${invoice.id}"]`).click();
  await billingPage.getByTestId('console-transfer-reference').fill('QA-JOURNEY');
  await billingPage.getByTestId('console-transfer-amount').fill('1500');
  await billingPage.getByTestId('console-record-transfer-submit').click();
  await expect(billingPage.getByTestId('console-transfer-recorded')).toBeVisible();
  expect((await operator.subscription(await operator.renew(admin), salon.id)).state).toBe('active');
  const signedOwner = { salon, account: ownerSigned.account, tokens: await ownerNow(), principal: await identity.principal(salon, await ownerNow()) };
  const workspace = await accounts.context(salon, [signedOwner]);
  const ws = await workspace.newPage();
  await ws.goto('/workspace/billing');
  await expect(ws.getByTestId('billing-plan-name')).toHaveText('QA journey plan');
  await expect(ws.getByTestId('billing-invoice-status')).toContainText(/paid/i);
  await workspace.close();

  // S03: suspend and reactivate in the console.
  await billingPage.goto(consoleUrl(`/console/salons/${salon.id}`));
  await billingPage.getByTestId('console-suspend').click();
  await billingPage.getByTestId('console-suspend-reason').fill('QA journey suspension');
  await billingPage.getByTestId('console-suspend-submit').click();
  await expect(billingPage.getByTestId('console-salon-status')).toHaveAttribute('data-status', 'suspended');
  await billingPage.getByTestId('console-reactivate').click();
  await billingPage.getByTestId('console-reactivate-reason').fill('QA journey reactivation');
  await billingPage.getByTestId('console-reactivate-submit').click();
  await expect(billingPage.getByTestId('console-salon-status')).toHaveAttribute('data-status', 'active');
  expect((await operator.lifecycle(await operator.renew(admin), salon.id)).items.map(e => e.kind).sort()).toEqual(['reactivated', 'suspended']);

  // S04: Support enters through the code, reads the Owner's pages under the banner, and ends.
  const support = await operator.actor('OPERATOR_SUPPORT');
  const session = await supportJson<SupportStart>(await operator.startSupport(support, salon.id, { reason: 'QA journey support' }), 'supportStart', 201);
  const supportView = await browser.newContext();
  const view = await supportView.newPage();
  await view.goto(`${salonUrl(salon, webUrl)}/workspace/support#${session.entryCode}`);
  await expect(view.getByTestId(tid('banner'))).toBeVisible();
  await view.goto(`${salonUrl(salon, webUrl)}/workspace/billing`);
  await expect(view.getByTestId('billing-plan-name'), 'S02\'s billing page in the support view').toHaveText('QA journey plan');
  await view.getByTestId(tid('bannerEnd')).click();
  await expect(view.getByTestId(tid('banner'))).toHaveCount(0);
  await supportView.close();
  const audit = await identity.call(salon, 'GET', '/audit?size=100', undefined, await ownerNow());
  expect(JSON.stringify(await audit.json()), 'the Owner sees the support session').toContain('QA journey support');

  // S03: closure, the Owner's export link, deletion and the tombstone.
  await billingPage.goto(consoleUrl(`/console/salons/${salon.id}`));
  await billingPage.getByTestId('console-close').click();
  await billingPage.getByTestId('console-confirm-slug').fill(slug);
  await billingPage.getByTestId('console-close-reason').fill('QA journey closure');
  await billingPage.getByTestId('console-close-submit').click();
  await expect(billingPage.getByTestId('console-lifecycle-closed')).toBeVisible();
  await operator.runExports();
  const [email] = await operator.emailsOfKind(owner.email, 'export.ready', 1);
  const token = email.links[0].split('#')[1];
  const ownerPage = await (await browser.newContext()).newPage();
  await ownerPage.goto(platformUrl(`/exports#${token}`));
  await expect(ownerPage.getByTestId('exports-download')).toBeVisible();
  const archive = unzip(await (await operator.linkRaw(token)).body());
  const manifest = JSON.parse(archive.get('manifest.json')!.toString('utf8')) as { tables: { name: string; rows: number }[] };
  expect(manifest.tables.find(t => t.name === 'identity_staff')?.rows, 'the export holds the Owner and the staff created along the way').toBe(2);
  expect(manifest.tables.find(t => t.name === 'audit_entry')!.rows, 'and the audit').toBeGreaterThan(0);
  const closed = await operator.salonDetail(await operator.renew(admin), salon.id);
  await clock.set(closed.deleteAt!);
  await operator.runDeletions();
  await operatorProblem(await identity.api.getOn(salon.hostname, '/api/v1/salon'), 404, 'not_found');
  const tombstone = await consoleContext(browser, await operator.actor());
  const last = await tombstone.newPage();
  await last.goto(consoleUrl(`/console/salons/${salon.id}`));
  await expect(last.getByTestId('console-salon-status')).toHaveAttribute('data-status', 'deleted');
  await tombstone.close();
  await fresh.close();
  await consoleSide.close();
});
