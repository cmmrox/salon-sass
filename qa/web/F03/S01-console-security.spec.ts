/**
 * F03-S01 console security in the browser: host isolation of the web routes, Origin and Fetch
 * Metadata on every BFF mutation, host-only HttpOnly credentials, concurrent tabs, a browser
 * without Web Locks, and the console with the API stopped.
 * Cases TC-F03-S01-005 (web rows), -030, -031, -038 (cases.md).
 *
 * BFF actions are the S01 handoff's `/operator-web/{action}` allowlist. -038 stops and starts the
 * local API through infra/scripts/local-service.sh and always starts it again.
 */
import { randomUUID } from 'node:crypto';

import {
  test, expect, consoleAction, consoleContext, consoleUrl, operatorCookies, platformUrl, signInByKeyboard,
} from '../../shared/console-browser';
import { apiUrl, webUrl } from '../../shared/env';
import { apiProcess, localService } from '../../shared/identity-runtime';
import { operatorAccount, planInput } from '../../shared/operator';
import { alpha, onHostname, salonUrl } from '../../shared/tenants';

const id = (n: number) => `TC-F03-S01-${String(n).padStart(3, '0')}`;
const LIMIT_MS = 5_000;

test(`${id(5)} Console and operator BFF routes on any other host answer 404 (web rows)`, async ({ request }) => {
  for (const host of [alpha.hostname, 'localhost', 'admin.localhost', 'console.localhost.example.com', 'a.console.localhost']) {
    for (const route of ['/console', '/console/salons', '/console/sign-in', '/operator-web/principal']) {
      const r = await request.get(`${onHostname(webUrl, host)}${route}`, { maxRedirects: 0, failOnStatusCode: false });
      expect(r.status(), `${route} on ${host}`).toBe(404);
    }
  }
  const workspace = await request.get(consoleUrl('/workspace/sign-in'), { maxRedirects: 0, failOnStatusCode: false });
  expect(workspace.status(), 'no workspace on the console host').toBe(404);
  expect((await request.get(platformUrl('/'), { failOnStatusCode: false })).status(), 'platform status still answers').toBe(200);
  expect((await request.get(`${salonUrl(alpha, webUrl)}/workspace/sign-in`, { failOnStatusCode: false })).status(), 'the salon workspace still answers').toBe(200);
});

test(`${id(30)} Console mutations reject missing or foreign Origin and keep credentials in host-only cookies`, async ({ browser, operator }) => {
  const account = operatorAccount('OPERATOR_ADMIN');
  await operator.seed(account);
  const context = await consoleContext(browser);
  const page = await context.newPage();
  const urls: string[] = [];
  page.on('request', r => urls.push(r.url()));
  await signInByKeyboard(page, operator, account);
  const cookies = (await context.cookies(consoleUrl(''))).filter(c => c.name.startsWith('salon_operator_'));
  expect(cookies.length, 'operator cookies were set').toBeGreaterThan(0);
  for (const cookie of cookies) {
    expect(operatorCookies, `${cookie.name} is a documented operator cookie`).toContain(cookie.name);
    expect(cookie.domain, `${cookie.name} is host-only`).toBe('console.localhost');
    expect(cookie.httpOnly, `${cookie.name} HttpOnly`).toBe(true);
    expect(cookie.sameSite, `${cookie.name} SameSite`).toBe('Lax');
  }
  const secrets = cookies.filter(c => /access|refresh|challenge/.test(c.name)).map(c => c.value).filter(v => v.length > 16);
  expect(secrets.length, 'credential cookie values to look for').toBeGreaterThan(0);
  const exposed = await page.evaluate(() => [document.cookie, JSON.stringify({ ...localStorage }), JSON.stringify({ ...sessionStorage }), document.documentElement.outerHTML].join('\n'));
  for (const value of secrets) {
    expect(exposed.includes(value), 'no credential in document.cookie, storage or HTML').toBe(false);
    expect(urls.some(u => u.includes(value)), 'no credential in a URL').toBe(false);
  }
  const admin = await operator.actor();
  const before = (await operator.planPage(admin, '?size=1')).total;
  const body = { ...planInput(), idempotencyKey: randomUUID() };
  for (const [label, headers] of [
    ['no Origin', { Origin: '' }], ['foreign Origin', { Origin: 'http://evil.example' }], ['a salon Origin', { Origin: salonUrl(alpha, webUrl) }],
    ['cross-site fetch metadata', { 'Sec-Fetch-Site': 'cross-site' }],
  ] as const) {
    const r = await consoleAction(context, 'plan-create', body, headers);
    expect(r.status(), `${label}: refused`).toBe(403);
  }
  const anonymous = await consoleContext(browser);
  const signIn = await consoleAction(anonymous, 'sign-in', { email: account.email, password: account.password, deviceLabel: 'QA' }, { Origin: 'http://evil.example' });
  expect(signIn.status(), 'sign-in is a mutation too').toBe(403);
  expect((await anonymous.cookies()).filter(c => c.name.startsWith('salon_operator_')), 'no challenge issued').toEqual([]);
  expect((await operator.planPage(admin, '?size=1')).total, 'nothing created').toBe(before);
  await anonymous.close();
  await context.close();
});

test(`${id(31)} Concurrent console tabs and a browser without Web Locks stay safe`, async ({ browser, operator }) => {
  const account = operatorAccount('OPERATOR_ADMIN');
  await operator.seed(account);
  const context = await consoleContext(browser);
  const first = await context.newPage();
  await signInByKeyboard(first, operator, account);
  const second = await context.newPage();
  await second.goto(consoleUrl('/console/plans'));
  await expect(second.getByTestId('console-plans'), 'the second tab shares the session').toBeVisible();
  await first.getByTestId('console-sign-out').click();
  await first.waitForURL('**/console/sign-in');
  await second.goto(consoleUrl('/console/salons'));
  await second.waitForURL('**/console/sign-in');
  expect((await context.cookies(consoleUrl(''))).filter(c => c.name.startsWith('salon_operator_') && c.value), 'sign-out cleared the cookies').toEqual([]);
  await context.close();
  const unsupported = await consoleContext(browser, undefined, { unsupported: true });
  const page = await unsupported.newPage();
  await page.goto(consoleUrl('/console/sign-in'));
  await expect(page.getByTestId('console-browser-support'), 'a translated message, not a broken sign-in').toBeVisible();
  await expect(page.getByTestId('console-sign-in-submit')).toBeDisabled();
  await unsupported.close();
});

test(`${id(38)} A stopped or silent API is reported by the console within five seconds and recovers`, async ({ browser, operator }) => {
  test.setTimeout(300_000);
  const admin = await operator.actor();
  const context = await consoleContext(browser, admin);
  const page = await context.newPage();
  await page.goto(consoleUrl('/console/salons'));
  await expect(page.getByTestId('console-salons')).toBeVisible();
  for (const mode of ['stopped', 'silent'] as const) {
    await test.step(mode, async () => {
      // Silent: the API process is frozen (SIGSTOP), so connections open and nothing answers.
      const pid = mode === 'silent' ? Number(apiProcess().split('\n')[0]) : 0;
      try {
        if (mode === 'stopped') localService('stop', 'api'); else process.kill(pid, 'SIGSTOP');
        const started = Date.now();
        await page.goto(consoleUrl('/console/salons'), { timeout: 30_000 });
        await expect(page.getByTestId('console-error'), `${mode}: the unavailable state`).toBeVisible({ timeout: LIMIT_MS });
        expect(Date.now() - started, `${mode}: reported within the five-second deadline`).toBeLessThanOrEqual(LIMIT_MS);
      } finally {
        if (mode === 'stopped') localService('start', 'api'); else process.kill(pid, 'SIGCONT');
      }
      await expect.poll(async () => (await fetch(`${apiUrl}/actuator/health`, { signal: AbortSignal.timeout(2_000) }).then(r => r.status, () => 0)),
        { timeout: 120_000, intervals: [100] }).toBe(200);
      const back = Date.now();
      await expect.poll(async () => {
        await page.goto(consoleUrl('/console/salons'));
        return page.getByTestId('console-salons').isVisible();
      }, { timeout: LIMIT_MS, intervals: [250] }).toBe(true);
      expect(Date.now() - back, `${mode}: recovered within five seconds of the API`).toBeLessThanOrEqual(LIMIT_MS);
    });
  }
  await context.close();
});
