/**
 * F03-S04 support screens: the BFF entry with Origin checks, the code kept out of URLs and logs,
 * the read-only support view under its banner, leaving the view when the session ends, the
 * Owner's Support access page, and the screens' states, widths and accessibility.
 * Cases TC-F03-S04-003 (BFF rows), -005, -006, -007 (controls), -010, -012 (page), -015 to -017.
 *
 * Routes, cookie names and codes are the S04 spec's; test ids, the entry BFF action and the
 * credential shape come from its handoff (qa/shared/operator.ts PENDING_S04).
 */
import type { BrowserContext, Page } from '@playwright/test';

import {
  test, expect, WIDTHS, consoleContext, consoleUrl, expectAccessible, expectFits, expectSheetFocus,
} from '../../shared/console-browser';
import { webUrl } from '../../shared/env';
import { logOffsets, logsSince } from '../../shared/product-logs';
import {
  SUPPORT, ownerSession, pendingS04, supportJson, supportTestId as tid,
  type Operator, type OperatorActor, type SupportStart,
} from '../../shared/operator';
import { salonUrl, type Salon } from '../../shared/tenants';

const id = (n: number) => `TC-F03-S04-${String(n).padStart(3, '0')}`;
const REASON = 'QA support reason in the browser';

async function arrange(operator: Operator) {
  const admin = await operator.actor();
  const plan = await operator.plan(admin);
  const target = await operator.salon(admin, plan.id);
  const support = await operator.actor('OPERATOR_SUPPORT');
  return { admin, support, target };
}

async function start(operator: Operator, support: OperatorActor, salonId: string, durationMinutes = 30) {
  return supportJson<SupportStart>(await operator.startSupport(support, salonId, { reason: REASON, durationMinutes }), 'supportStart', 201);
}

/** Opens the entry URL the console gives, on the local web port (the URL names https and no port). */
async function enterView(context: BrowserContext, salon: Salon, code: string): Promise<Page> {
  const page = await context.newPage();
  await page.goto(`${salonUrl(salon, webUrl)}/workspace/support#${code}`);
  await expect(page.getByTestId(tid('banner'))).toBeVisible();
  return page;
}

const supportCookies = async (context: BrowserContext, salon: Salon) =>
  (await context.cookies(salonUrl(salon, webUrl))).filter(c => SUPPORT.cookies.includes(c.name as never) && c.value);

test(`${id(3)} The entry BFF refuses a missing or foreign Origin and cross-site metadata (browser rows)`, async ({ browser, operator }) => {
  const { support, target } = await arrange(operator);
  const session = await start(operator, support, target.created.id);
  const context = await browser.newContext();
  const action = `${salonUrl(target.salon, webUrl)}/identity-web/${pendingS04('entryBffAction')}`;
  for (const [label, headers] of [['no Origin', {}], ['foreign Origin', { Origin: 'http://evil.example' }],
    ['console Origin', { Origin: consoleUrl('') }], ['cross-site metadata', { Origin: salonUrl(target.salon, webUrl), 'Sec-Fetch-Site': 'cross-site' }]] as const) {
    const r = await context.request.post(action, { headers: { 'Content-Type': 'application/json', ...headers }, data: JSON.stringify({ code: session.entryCode }), maxRedirects: 0 });
    expect(r.status(), `${label}: refused`).toBe(403);
    expect(await supportCookies(context, target.salon), `${label}: no support cookie`).toEqual([]);
  }
  const page = await enterView(context, target.salon, session.entryCode);
  expect((await supportCookies(context, target.salon)).length, 'the refused attempts left the code usable once').toBeGreaterThan(0);
  await page.close();
  await context.close();
});

test(`${id(5)} The entry code never reaches a server log or a request URL`, async ({ browser, operator }) => {
  const { support, target } = await arrange(operator);
  const session = await start(operator, support, target.created.id);
  const offsets = logOffsets();
  const context = await browser.newContext();
  const urls: string[] = [];
  context.on('request', r => { urls.push(r.url()); urls.push(r.headers().referer ?? ''); });
  const page = await enterView(context, target.salon, session.entryCode);
  expect(new URL(page.url()).hash, 'the code leaves the address bar at once').toBe('');
  expect(urls.filter(u => u.includes(session.entryCode)), 'no request URL or referrer carries the code').toEqual([]);
  const logs = logsSince(offsets);
  expect(logs.length, 'the product logged the entry').toBeGreaterThan(0);
  expect(logs.includes(session.entryCode), 'the code is in no log').toBe(false);
  await context.close();
});

test(`${id(6)} The support view shows what the Owner can read under a persistent banner and no secrets`, async ({ browser, operator, identity }) => {
  const { support, target } = await arrange(operator);
  await ownerSession(identity, target.salon, target.input);
  const session = await start(operator, support, target.created.id);
  const context = await browser.newContext();
  const page = await enterView(context, target.salon, session.entryCode);
  for (const route of ['/workspace/account', '/workspace/staff', '/workspace/audit', '/workspace/billing']) {
    await page.goto(`${salonUrl(target.salon, webUrl)}${route}`);
    await expect(page.getByTestId('workspace-main'), `${route}: an Owner-readable page`).toBeVisible();
    await expect(page.getByTestId(tid('banner')), `${route}: the banner`).toBeVisible();
    await expect(page.getByTestId(tid('banner')), 'the banner names the end time').toContainText(/\d{1,2}:\d{2}/);
    const html = await page.content();
    for (const secret of [target.input.owner.password, session.entryCode]) expect(html.includes(secret), `${route}: no secret shown`).toBe(false);
    expect(html, `${route}: no MFA material`).not.toMatch(/otpauth:\/\//);
  }
  const access = (await supportCookies(context, target.salon)).find(c => c.name === 'salon_support_access');
  expect(access, 'the support credential is in its host-only cookie').toBeTruthy();
  expect(access!.httpOnly).toBe(true);
  const me = await identity.api.send('GET', '/api/v1/identity/me', { headers: { Host: target.salon.hostname, Authorization: `Bearer ${access!.value}` } });
  expect(me.status()).toBe(200);
  const principal = await me.json() as { kind: string };
  expect(principal.kind, 'a support principal').toBe('support');
  await context.close();
});

test(`${id(7)} Every mutating control in the support view is disabled with the reason`, async ({ browser, operator }) => {
  const { support, target } = await arrange(operator);
  const session = await start(operator, support, target.created.id);
  const context = await browser.newContext();
  const page = await enterView(context, target.salon, session.entryCode);
  await page.goto(`${salonUrl(target.salon, webUrl)}/workspace/staff`);
  await expect(page.getByTestId('workspace-main')).toBeVisible();
  const ids = await page.locator('button[data-testid]').evaluateAll(buttons => buttons.map(b => b.getAttribute('data-testid') ?? '')
    // Actions only: the add-staff form's own fields (account-staff-new-*, including its Role picker)
    // are inputs, not mutations; its submit (account-staff-create) is an action and is listed.
    .filter(id => /create|remove|change|revoke|save|submit|logout-all|role/.test(id) && !/-new-/.test(id)));
  expect(ids.length, 'mutating controls are present to inspect').toBeGreaterThan(0);
  for (const testId of ids) await expect(page.getByTestId(testId).first(), `${testId} disabled in the support view`).toBeDisabled();
  await context.close();
});

test(`${id(10)} The workspace leaves the support view when the session ends`, async ({ browser, operator, identity }) => {
  const { support, target } = await arrange(operator);
  const owner = await ownerSession(identity, target.salon, target.input);
  const first = await start(operator, support, target.created.id);
  const context = await browser.newContext();
  const page = await enterView(context, target.salon, first.entryCode);
  const revoked = await identity.api.send('DELETE', `${SUPPORT.salonSessions}/${first.id}`, { headers: { Host: target.salon.hostname, Authorization: `Bearer ${owner.tokens.accessToken}` } });
  expect(revoked.status(), 'the Owner revokes').toBe(204);
  // The open page may notice the end itself and redirect to the ended notice at the same instant,
  // which aborts this navigation; either way the next page must be the ended notice.
  await page.goto(`${salonUrl(target.salon, webUrl)}/workspace/staff`).catch((error: Error) => { if (!/ERR_ABORTED/.test(error.message)) throw error; });
  await expect(page.getByTestId(tid('entryExpired')), 'the next navigation leaves the support view').toBeVisible();
  await expect(page.getByTestId(tid('banner'))).toHaveCount(0);
  const second = await start(operator, await operator.renew(support), target.created.id);
  const again = await enterView(context, target.salon, second.entryCode);
  await again.getByTestId(tid('bannerEnd')).click();
  await expect(again.getByTestId(tid('banner'))).toHaveCount(0);
  expect(await supportCookies(context, target.salon), 'End session clears the support cookies').toEqual([]);
  await context.close();
});

test(`${id(12)} The Owner's Support access page lists sessions and offers Revoke only on the active one`, async ({ operator, identity, accounts }) => {
  const { support, target } = await arrange(operator);
  const ended = await start(operator, support, target.created.id);
  expect((await operator.endSupport(support, ended.id)).status()).toBe(204);
  const active = await start(operator, support, target.created.id);
  const owner = await ownerSession(identity, target.salon, target.input);
  const context = await accounts.context(target.salon, [owner]);
  const page = await context.newPage();
  await page.goto('/workspace/support-access');
  await expect(page.getByTestId(tid('accessPage'))).toBeVisible();
  await expect(page.getByTestId(tid('accessRow'))).toHaveCount(2);
  await expect(page.getByTestId(tid('accessPage'))).toContainText(REASON);
  await expect(page.getByTestId(tid('accessPage'))).toContainText(support.account.displayName);
  await expect(page.getByTestId(tid('accessPage')), 'no operator email').not.toContainText(support.account.email);
  await expect(page.getByTestId(tid('accessRevoke')), 'Revoke only on the active session').toHaveCount(1);
  await page.getByTestId(tid('accessRevoke')).click();
  await page.getByTestId(tid('accessConfirm')).click();
  await expect(page.getByTestId(tid('accessRevoke'))).toHaveCount(0);
  const list = await supportJson<{ items: { id: string; state: string }[] }>(await operator.supportList(await operator.renew(support)), 'supportSessionPage');
  expect(list.items.find(s => s.id === active.id)?.state, 'revoked through the page').not.toBe('active');
  await context.close();
});

test(`${id(15)} Support screens show every state and the banner is announced and never hidden`, async ({ browser, operator, clock }) => {
  const { support, target } = await arrange(operator);
  const consoleSide = await consoleContext(browser, support);
  const page = await consoleSide.newPage();
  await page.goto(consoleUrl(`/console/salons/${target.created.id}`));
  await page.getByTestId(tid('startOpen')).click();
  await expect(page.getByTestId(tid('startSheet'))).toBeVisible();
  await page.getByTestId(tid('startReason')).fill('too short');
  await page.getByTestId(tid('startSubmit')).click();
  await expect(page.getByTestId(tid('startReason')), 'validation').toHaveAttribute('aria-invalid', 'true');
  await page.getByTestId(tid('startReason')).fill(REASON);
  await page.getByTestId(tid('startSubmit')).click();
  await expect(page.getByTestId(tid('startResult')), 'success with Open workspace').toBeVisible();
  await expect(page.getByTestId(tid('openWorkspace'))).toBeVisible();
  await page.goto(consoleUrl('/console/support'));
  await expect(page.getByTestId(tid('sessionRow'))).toHaveCount(1);
  await consoleSide.close();
  const session = await start(operator, await operator.actor('OPERATOR_SUPPORT'), target.created.id);
  const context = await browser.newContext();
  const view = await enterView(context, target.salon, session.entryCode);
  const banner = view.getByTestId(tid('banner'));
  expect(await banner.getAttribute('role'), 'the banner is announced').toMatch(/^(status|alert)$/);
  expect(await banner.locator('button').filter({ hasText: /dismiss|close/i }).count(), 'no dismiss control').toBe(0);
  await view.mouse.wheel(0, 4000);
  await expect(banner, 'still visible after scrolling').toBeInViewport();
  const used = await context.newPage();
  await used.goto(`${salonUrl(target.salon, webUrl)}/workspace/support#${session.entryCode}`);
  await expect(used.getByTestId(tid('entryRefused')), 'a used code').toBeVisible();
  const late = await start(operator, await operator.actor('OPERATOR_SUPPORT'), target.created.id);
  await clock.advance(61_000);
  const expired = await (await browser.newContext()).newPage();
  await expired.goto(`${salonUrl(target.salon, webUrl)}/workspace/support#${late.entryCode}`);
  await expect(expired.getByTestId(tid('entryRefused')).or(expired.getByTestId(tid('entryExpired'))), 'an expired code').toBeVisible();
  await context.close();
});

test(`${id(16)} Support screens fit every width without clipping or horizontal scroll`, async ({ browser, operator, identity, accounts }) => {
  test.setTimeout(240_000);
  const { admin, support, target } = await arrange(operator);
  // Support lists only its own sessions: it gets one, on a second salon so this salon's Start stays offered.
  const second = await operator.salon(admin, (await operator.plan(admin)).id);
  await start(operator, support, second.created.id);
  for (let n = 0; n < 6; n++) {
    const extra = await operator.actor('OPERATOR_SUPPORT');
    const s = await start(operator, extra, target.created.id);
    if (n % 2) expect((await operator.endSupport(extra, s.id)).status()).toBe(204);
  }
  const owner = await ownerSession(identity, target.salon, target.input);
  for (const viewport of WIDTHS) {
    const consoleSide = await consoleContext(browser, await operator.renew(support), { width: viewport.width });
    const page = await consoleSide.newPage();
    await page.goto(consoleUrl(`/console/salons/${target.created.id}`));
    await page.getByTestId(tid('startOpen')).click();
    await expect(page.getByTestId(tid('startSheet'))).toBeVisible();
    await expectFits(page, `start sheet at ${viewport.width}`);
    await page.keyboard.press('Escape');
    await page.goto(consoleUrl('/console/support'));
    await expect(page.getByTestId(tid('sessionList'))).toBeVisible();
    await expectFits(page, `session list at ${viewport.width}`);
    await consoleSide.close();
    // The Admin's list holds every session, a longer list.
    const adminSide = await consoleContext(browser, await operator.renew(admin), { width: viewport.width });
    const adminPage = await adminSide.newPage();
    await adminPage.goto(consoleUrl('/console/support'));
    await expect(adminPage.getByTestId(tid('sessionRow')).first()).toBeVisible();
    expect(await adminPage.getByTestId(tid('sessionRow')).count(), 'several sessions listed for the Admin').toBeGreaterThan(1);
    await expectFits(adminPage, `Admin session list at ${viewport.width}`);
    await adminSide.close();
    const session = await start(operator, await operator.actor('OPERATOR_SUPPORT'), target.created.id);
    const viewContext = await browser.newContext({ viewport: { width: viewport.width, height: 900 } });
    const view = await enterView(viewContext, target.salon, session.entryCode);
    await expect(view.getByTestId(tid('banner'))).toBeInViewport({ ratio: 1 });
    await expectFits(view, `support view with banner at ${viewport.width}`);
    await viewContext.close();
    const ownerContext = await accounts.context(target.salon, [owner], { width: viewport.width });
    const access = await ownerContext.newPage();
    await access.goto('/workspace/support-access');
    await expect(access.getByTestId(tid('accessPage'))).toBeVisible();
    await expectFits(access, `Support access at ${viewport.width}`);
    await ownerContext.close();
  }
});

test(`${id(17)} Support screens pass the automated scan and keyboard-only use`, async ({ browser, operator, identity, accounts }) => {
  const { support, target } = await arrange(operator);
  const consoleSide = await consoleContext(browser, support);
  const page = await consoleSide.newPage();
  await page.goto(consoleUrl(`/console/salons/${target.created.id}`));
  await expectSheetFocus(page, page.getByTestId(tid('startOpen')), page.getByTestId(tid('startSheet')));
  await page.getByTestId(tid('startOpen')).focus();
  await page.keyboard.press('Enter');
  await expectAccessible(page, 'start sheet');
  await page.getByTestId(tid('startReason')).focus();
  await page.keyboard.type(REASON);
  await page.getByTestId(tid('startSubmit')).focus();
  await page.keyboard.press('Enter');
  await expect(page.getByTestId(tid('startResult'))).toBeVisible();
  await page.goto(consoleUrl('/console/support'));
  await expectAccessible(page, 'session list');
  await page.getByTestId(tid('sessionEnd')).first().focus();
  await page.keyboard.press('Enter');
  // End asks for confirmation in an Alert Dialog (S04 handoff); confirm it by keyboard too.
  await page.getByTestId('console-support-confirm-end').focus();
  await page.keyboard.press('Enter');
  await expect(page.getByTestId('console-support-confirm-end')).toHaveCount(0);
  // Read through the API with the still-valid access token: renewing would rotate the refresh token
  // the browser holds. The end request is asynchronous, so wait for it.
  await expect.poll(async () => (await supportJson<{ total: number }>(await operator.supportList(support, { salonId: target.created.id, state: 'active' }), 'supportSessionPage')).total,
    { message: 'the session ended by keyboard', timeout: 10_000 }).toBe(0);
  await consoleSide.close();
  const session = await start(operator, await operator.actor('OPERATOR_SUPPORT'), target.created.id);
  const viewContext = await browser.newContext();
  const view = await enterView(viewContext, target.salon, session.entryCode);
  await expectAccessible(view, 'support view');
  await view.getByTestId(tid('bannerEnd')).focus();
  await view.keyboard.press('Enter');
  await expect(view.getByTestId(tid('banner'))).toHaveCount(0);
  await viewContext.close();
  const owner = await ownerSession(identity, target.salon, target.input);
  await start(operator, await operator.actor('OPERATOR_SUPPORT'), target.created.id);
  const ownerContext = await accounts.context(target.salon, [owner]);
  const access = await ownerContext.newPage();
  await access.goto('/workspace/support-access');
  await expectAccessible(access, 'Support access');
  await access.getByTestId(tid('accessRevoke')).focus();
  await access.keyboard.press('Enter');
  await access.getByTestId(tid('accessConfirm')).focus();
  await access.keyboard.press('Enter');
  await expect(access.getByTestId(tid('accessRevoke'))).toHaveCount(0);
  await ownerContext.close();
});

