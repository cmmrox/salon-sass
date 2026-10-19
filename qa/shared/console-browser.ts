/**
 * F03 browser fixtures: the operator console on its own hostname and the workspace of provisioned
 * salons, both arranged with real API-issued authority (the cookie bridge F02 uses), and the
 * screen checks every F03 browser case shares. Test ids are those the S01-S03 handoffs name
 * (`console-*`, `billing-*`, `workspace-billing-banner`, `exports-*`).
 */
import type { Browser, BrowserContext, Locator, Page } from '@playwright/test';

import { AccountBrowser } from './identity-browser';
import { DESIGN_WIDTHS } from './design-reference';
import { webUrl } from './env';
import { fit, seriousViolations } from './page-checks';
import { CONTRACT, CONSOLE_HOST, PLATFORM_HOST, expect, test as operatorTest, type Operator, type OperatorAccount, type OperatorActor } from './operator';
import { onHostname } from './tenants';

export const consoleUrl = (route = '/console') => `${onHostname(webUrl, CONSOLE_HOST)}${route}`;
export const platformUrl = (route = '/') => `${onHostname(webUrl, PLATFORM_HOST)}${route}`;
export const WIDTHS = DESIGN_WIDTHS;

/** A console context; with an actor, its real API-issued credentials are bridged into the BFF cookies. */
export async function consoleContext(browser: Browser, actor?: OperatorActor, options: { width?: number; unsupported?: boolean } = {}) {
  const context = await browser.newContext({ baseURL: consoleUrl(''), viewport: { width: options.width ?? 1280, height: 1000 }, locale: 'en' });
  if (options.unsupported) await context.addInitScript(() => Object.defineProperty(navigator, 'locks', { value: undefined, configurable: true }));
  if (actor) {
    const values: Record<string, string> = { access: actor.tokens.accessToken, refresh: actor.tokens.refreshToken,
      expires: actor.tokens.accessExpiresAt, session: actor.tokens.sessionId };
    await context.addCookies(Object.entries(values).map(([name, value]) => ({ name: `salon_operator_${name}`, value, url: `${consoleUrl('')}/`,
      httpOnly: true, sameSite: 'Lax' as const, secure: false })));
  }
  return context;
}

/** Signs in through the console screens, by keyboard: password, then authenticator code (enrolling first when needed). */
export async function signInByKeyboard(page: Page, operator: Operator, account: OperatorAccount) {
  await page.goto(consoleUrl('/console/sign-in'));
  await expect(page.getByTestId('console-sign-in-card')).toBeVisible();
  await page.getByTestId('console-email').focus();
  await page.keyboard.type(account.email);
  await page.keyboard.press('Tab');
  await expect(page.getByTestId('console-password')).toBeFocused();
  await page.keyboard.type(account.password);
  await page.keyboard.press('Enter');
  await expect(page.getByTestId('console-code')).toBeVisible();
  if (!account.totpSecret) {
    await expect(page.getByTestId('console-setup-key'), 'the transient authenticator setup').toBeVisible();
    account.totpSecret = (await page.getByTestId('console-setup-key').textContent())!.replace(/\s+/g, '');
  }
  await page.getByTestId('console-code').focus();
  await page.keyboard.type(await operator.code(account.totpSecret));
  await page.keyboard.press('Enter');
  await page.waitForURL('**/console/salons');
  // The router keeps the sign-in page's shell in the DOM, hidden (display: none), after navigating.
  await expect(page.getByTestId('console-shell').filter({ visible: true })).toHaveCount(1);
}

/** No sideways scroll, nothing outside the viewport or clipped, having first found elements to measure. */
export async function expectFits(page: Page, label: string) {
  const result = await fit(page);
  expect(result.counted, `${label}: elements to measure`).toBeGreaterThan(0);
  expect(result.pageScrolls, `${label}: no horizontal scroll`).toBe(false);
  expect(result.outside, `${label}: nothing outside the viewport`).toEqual([]);
  expect(result.clipped, `${label}: nothing clipped`).toEqual([]);
}

export async function expectAccessible(page: Page, label: string) {
  expect(await seriousViolations(page), `${label}: no serious or critical violation`).toEqual([]);
}

/** A sheet opened from a button: focus moves inside, Tab stays inside, Escape closes it and returns focus. */
export async function expectSheetFocus(page: Page, opener: Locator, sheet: Locator, tabs = 12) {
  await opener.focus();
  await page.keyboard.press('Enter');
  await expect(sheet).toBeVisible();
  for (let n = 0; n < tabs; n++) {
    await page.keyboard.press('Tab');
    expect(await sheet.evaluate(el => el.contains(document.activeElement)), `focus stays in the sheet after ${n + 1} Tab`).toBe(true);
  }
  await page.keyboard.press('Escape');
  await expect(sheet).toBeHidden();
  await expect(opener, 'focus returns to the opener').toBeFocused();
}

/** BFF calls made as the browser makes them, for Origin and cookie rows. */
export function consoleAction(context: BrowserContext, action: string, body?: unknown, headers: Record<string, string> = {}) {
  return context.request.fetch(consoleUrl(`/operator-web/${action}`), {
    method: body === undefined ? 'GET' : 'POST',
    headers: { ...(body === undefined ? {} : { Origin: consoleUrl(''), 'Content-Type': 'application/json' }), ...headers },
    data: body === undefined ? undefined : JSON.stringify(body), maxRedirects: 0,
  });
}

export const operatorCookies = CONTRACT.cookies;

export const test = operatorTest.extend<{ accounts: AccountBrowser }>({
  accounts: async ({ browser, identity }, use) => {
    const accounts = new AccountBrowser(browser, identity);
    try { await use(accounts); } finally { await accounts.close(); }
  },
});
export { expect };
