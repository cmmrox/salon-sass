/**
 * F01-S01: the web shell's status page when the API is stopped, hangs, answers wrongly or is slow,
 * and when it comes back. Expected results are AC-7 ("With the API stopped the page shows a plain
 * 'service unavailable' state and no error overlay, stack trace or blank page"), the three states
 * in the spec's "UI and UX" section, and docs/design/ui-ux-standards.md.
 *
 * The real API is stopped for the whole file, through the developer's script, and started again
 * at the end however the tests end. Where a test needs the API to misbehave in a particular way,
 * something stands in for it on the API's port for that test only.
 */
import type http from 'node:http';

import type { ConsoleMessage, Page } from '@playwright/test';

import type { Meta } from '@qa/api-client';
import { apiUrl } from '@qa/env';
import { expect, test } from '@qa/fixtures';
import { internalsIn } from '@qa/leaks';
import { fit, focusIndicator, pressables, seriousViolations, stringsShown, TEST_ID_NAME, testIds, WIDTHS } from '@qa/page-checks';
import { apiAnswers, metaAnswer, standInForApi, startApi, stopApi, type StandIn } from '@qa/services';
import { allMessages, message } from '@qa/strings';

/** QA's own bound on "does not hang": the spec gives no number, so this is deliberately generous. */
const UNAVAILABLE_WITHIN_MS = 10_000;

let standIn: StandIn | undefined;

test.beforeAll(async () => {
  await stopApi();
});

test.beforeEach(async () => {
  // Every test here is about the API being absent or replaced: prove the real one is not there.
  expect(await apiAnswers(), 'the real API is stopped').toBe(false);
});

test.afterEach(async () => {
  await standIn?.close();
  standIn = undefined;
});

test.afterAll(async () => {
  await standIn?.close();
  if (!(await apiAnswers())) {
    await startApi();
  }
});

/** Records what the browser itself reports as broken while a page is open. */
function watch(page: Page): { errors: string[] } {
  const errors: string[] = [];
  page.on('pageerror', (error) => errors.push(`page error: ${error.message}`));
  page.on('console', (entry: ConsoleMessage) => {
    if (entry.type() === 'error') {
      errors.push(`console error: ${entry.text()}`);
    }
  });
  return { errors };
}

async function expectUnavailable(page: Page, label: string): Promise<void> {
  const state = page.getByTestId('status-unavailable');
  await expect(state, `${label}: the unavailable state`).toBeVisible({ timeout: UNAVAILABLE_WITHIN_MS });
  await expect(state.getByRole('heading', { name: message('status.unavailable.title') }), label).toBeVisible();
  await expect(page.getByTestId('status-unavailable-body'), label).toHaveText(message('status.unavailable.body'));
  await expect(page.getByRole('link', { name: message('status.unavailable.retry') }), label).toBeVisible();
  await expect(page.getByTestId('status-loaded'), `${label}: the loaded state`).toHaveCount(0);
  await expect(page.getByTestId('status-loading'), `${label}: the loading state`).toHaveCount(0);
  await expect(page.getByTestId('status-api-service'), label).toHaveCount(0);
  await expect(page.getByTestId('status-api-version'), label).toHaveCount(0);
}

test('TC-F01-S01-041 with the API stopped the home page shows a plain service-unavailable state: no overlay, no stack trace, not blank', async ({ page }) => {
  const seen = watch(page);

  const response = await page.goto('/');

  await expectUnavailable(page, 'API stopped');
  // Not blank: the frame of the page is still there around the message.
  await expect(page.getByTestId('app-name')).toHaveText(message('app.name'));
  await expect(page.getByRole('heading', { level: 1, name: message('status.heading') })).toBeVisible();
  // No error overlay of the framework, in the page or in a portal beside it.
  await expect(page.locator('nextjs-portal, [data-nextjs-dialog], [data-nextjs-toast], [data-next-badge-root], #__next-build-watcher')).toHaveCount(0);
  expect((await page.locator('body').innerText()).trim().length, 'the page has words on it').toBeGreaterThan(40);
  expect(internalsIn(await page.locator('body').innerText()), 'internals in what the page says').toEqual([]);
  expect(seen.errors, 'errors the browser reported').toEqual([]);
  expect(response?.status() ?? 0, 'the page itself is served, not failed').toBeLessThan(500);
});

test('TC-F01-S01-042 with the API stopped nothing the browser receives shows how the product is built or where the API is', async ({ page }) => {
  const response = await page.goto('/');
  await expectUnavailable(page, 'API stopped');

  // The whole document as received, scripts and data included, not only what is drawn.
  const html = await page.content();
  expect(html.length, 'there is a document to inspect').toBeGreaterThan(500);
  const { host } = new URL(apiUrl);
  expect(html.includes(host), `the API's address (${host}) appears in the document`).toBe(false);
  expect(html.includes('SALON_API_BASE_URL'), 'the name of the setting appears in the document').toBe(false);
  for (const [what, pattern] of [
    ['a connection error code', /E(?:CONNREFUSED|CONNRESET|TIMEDOUT|NOTFOUND)/],
    ['the failure of the request', /fetch failed|TimeoutError|AbortError|TypeError/],
    ['a stack frame', /\bat\s+\S+\s+\((?:file:|node:|\/)[^)]*:\d+:\d+\)/],
    ['a path on the server', /\/Users\/|\/home\/|apps\/web\/src|\.next\/server/],
  ] as const) {
    expect(pattern.exec(html)?.[0], `${what} in the document`).toBeUndefined();
  }
  expect(response?.headers()['x-powered-by'], 'the X-Powered-By header').toBeUndefined();
});

test('TC-F01-S01-043 when the API accepts a connection and never answers, the page shows the unavailable state instead of hanging', async ({ page }) => {
  standIn = await standInForApi(() => {
    // Accept the request and say nothing, for ever.
  });
  const started = Date.now();

  await page.goto('/', { waitUntil: 'commit' });

  // Something is on screen at once, and it is the loading state, not a blank page.
  await expect(page.getByRole('heading', { level: 1, name: message('status.heading') })).toBeVisible({ timeout: 3_000 });
  await expectUnavailable(page, 'API hanging');
  expect(Date.now() - started, 'time until the page said the service is unavailable').toBeLessThan(UNAVAILABLE_WITHIN_MS);
  expect(standIn.requests(), 'the web app did ask the stand-in').toBeGreaterThanOrEqual(1);
});

const WRONG_ANSWERS: Array<{ name: string; respond: (request: http.IncomingMessage, response: http.ServerResponse) => void }> = [
  { name: 'a 500 with an HTML error page', respond: (_q, r) => { r.writeHead(500, { 'content-type': 'text/html' }); r.end('<html><body><h1>Internal Server Error</h1><pre>java.lang.IllegalStateException at com.example.Thing(Thing.java:1)</pre></body></html>'); } },
  { name: 'a 503 as problem details', respond: (_q, r) => { r.writeHead(503, { 'content-type': 'application/problem+json' }); r.end(JSON.stringify({ title: 'Service Unavailable', status: 503, code: 'service_unavailable' })); } },
  { name: 'a 404 as problem details', respond: (_q, r) => { r.writeHead(404, { 'content-type': 'application/problem+json' }); r.end(JSON.stringify({ title: 'Not Found', status: 404, code: 'not_found' })); } },
  { name: 'a 200 that is an HTML page', respond: (_q, r) => { r.writeHead(200, { 'content-type': 'text/html' }); r.end('<html><body>Welcome to nginx</body></html>'); } },
  { name: 'a 200 that says JSON and is not', respond: (_q, r) => { r.writeHead(200, { 'content-type': 'application/json' }); r.end('{"service": "salon-api", "version":'); } },
  { name: 'a 200 with an empty object', respond: (_q, r) => { r.writeHead(200, { 'content-type': 'application/json' }); r.end('{}'); } },
  { name: 'a 200 with nothing where the name and version belong', respond: (_q, r) => { r.writeHead(200, { 'content-type': 'application/json' }); r.end(JSON.stringify({ service: null, version: null, commit: null, time: null })); } },
  { name: 'a 200 with numbers where the name and version belong', respond: (_q, r) => { r.writeHead(200, { 'content-type': 'application/json' }); r.end(JSON.stringify({ service: 1, version: 2, commit: 3, time: 4 })); } },
  { name: 'a 200 with a list', respond: (_q, r) => { r.writeHead(200, { 'content-type': 'application/json' }); r.end('[]'); } },
  { name: 'a 200 with no body', respond: (_q, r) => { r.writeHead(200, { 'content-type': 'application/json' }); r.end(); } },
  { name: 'a connection closed before any answer', respond: (q) => { q.socket.destroy(); } },
  { name: 'a redirect to somewhere else', respond: (_q, r) => { r.writeHead(302, { location: 'http://example.invalid/' }); r.end(); } },
];

test('TC-F01-S01-044 when the API answers with an error or with something that is not meta, the page shows the unavailable state', async ({ page }) => {
  const seen = watch(page);
  for (const { name, respond } of WRONG_ANSWERS) {
    standIn = await standInForApi(respond);

    await page.goto('/');

    await expectUnavailable(page, name);
    expect(standIn.requests(), `${name}: the web app did ask the stand-in`).toBeGreaterThanOrEqual(1);
    expect(internalsIn(await page.locator('body').innerText()), `${name}: internals in what the page says`).toEqual([]);
    await standIn.close();
    standIn = undefined;
  }
  expect(seen.errors, 'errors the browser reported').toEqual([]);
});

test('TC-F01-S01-045 while the API is slow the page shows the loading state, and then the result', async ({ page }) => {
  const SERVICE = 'qa-stand-in-service';
  const VERSION = 'qa-7.7.7';
  standIn = await standInForApi((request, response) => {
    setTimeout(() => metaAnswer(SERVICE, VERSION)(request, response), 1_500);
  });

  await page.goto('/', { waitUntil: 'commit' });

  const loading = page.getByRole('status');
  await expect(loading, 'the loading state, while the API has not answered').toBeVisible({ timeout: 1_400 });
  await expect(loading).toHaveText(message('status.loading'));
  await expect(page.getByTestId('status-loading')).toBeVisible();
  // Then the result replaces it, with no reload.
  await expect(page.getByTestId('status-api-service')).toHaveText(SERVICE);
  await expect(page.getByTestId('status-api-version')).toHaveText(VERSION);
  await expect(page.getByTestId('status-loading')).toHaveCount(0);
  await expect(page.getByTestId('status-unavailable')).toHaveCount(0);
});

test('TC-F01-S01-046 markup in what the API reports is shown as text and never runs', async ({ page }) => {
  const SERVICE = '<img src=x onerror="window.qaInjected = 1"><b>bold</b>';
  const VERSION = '<script>window.qaInjected = 2</script>"\'&amp;';
  standIn = await standInForApi(metaAnswer(SERVICE, VERSION));
  const dialogs: string[] = [];
  page.on('dialog', (dialog) => { dialogs.push(dialog.message()); void dialog.dismiss(); });

  await page.goto('/');

  await expect(page.getByTestId('status-api-service')).toHaveText(SERVICE);
  await expect(page.getByTestId('status-api-version')).toHaveText(VERSION);
  await expect(page.getByTestId('status-loaded').locator('img, b, script')).toHaveCount(0);
  expect(await page.evaluate(() => (window as unknown as { qaInjected?: number }).qaInjected), 'script from the API ran').toBeUndefined();
  expect(dialogs).toEqual([]);
});

test('TC-F01-S01-047 at 360 and 1280 wide the unavailable state fits, its action can be reached, and a very long value still fits', async ({ page }) => {
  for (const size of WIDTHS) {
    await page.setViewportSize({ width: size.width, height: size.height });
    await page.goto('/');
    await expectUnavailable(page, `${size.width} wide`);

    const result = await fit(page);
    expect(result.counted, `${size.width}: there are elements to measure`).toBeGreaterThanOrEqual(5);
    expect(result.pageScrolls, `${size.width}: the page scrolls sideways`).toBe(false);
    expect(result.outside, `${size.width}: elements reaching outside the viewport`).toEqual([]);
    expect(result.clipped, `${size.width}: elements holding more than they show`).toEqual([]);
    const retry = page.getByRole('link', { name: message('status.unavailable.retry') });
    await expect(retry).toBeInViewport({ ratio: 1 });
    const box = await retry.boundingBox();
    // docs/design/ui-ux-standards.md: touch targets of at least 44 points.
    expect(box?.height ?? 0, `${size.width}: height of the action`).toBeGreaterThanOrEqual(44);
    expect(box?.width ?? 0, `${size.width}: width of the action`).toBeGreaterThanOrEqual(44);
  }

  // A value with no place to break, far wider than a phone: it wraps instead of pushing the page out.
  const LONG = `service-${'x'.repeat(300)}`;
  standIn = await standInForApi(metaAnswer(LONG, `v${'9'.repeat(120)}`));
  await page.setViewportSize({ width: WIDTHS[0].width, height: WIDTHS[0].height });
  await page.goto('/');
  await expect(page.getByTestId('status-api-service')).toHaveText(LONG);
  const long = await fit(page);
  expect(long.pageScrolls, 'with a very long value at 360: the page scrolls sideways').toBe(false);
  expect(long.outside, 'with a very long value at 360: elements reaching outside the viewport').toEqual([]);
});

test('TC-F01-S01-048 an automated accessibility scan of the unavailable state finds nothing serious, and the state is announced', async ({ page }) => {
  await page.goto('/');
  await expectUnavailable(page, 'API stopped');

  expect(await seriousViolations(page), 'serious or critical violations').toEqual([]);
  // A screen reader is told, without the person having to look for it.
  // Scoped to the status panel: the framework keeps an empty announcer of its own elsewhere on the page.
  await expect(page.getByTestId('status-panel').getByRole('alert')).toContainText(message('status.unavailable.title'));
});

test('TC-F01-S01-049 the unavailable state can be used with the keyboard alone: Tab reaches "try again", the focus shows, Enter retries', async ({ page }) => {
  await page.goto('/');
  await expectUnavailable(page, 'API stopped');
  const retry = page.getByRole('link', { name: message('status.unavailable.retry') });

  await page.keyboard.press('Tab');

  const indicator = await focusIndicator(retry);
  expect(indicator.focused, 'the first Tab reaches the only action on the page').toBe(true);
  expect(indicator.focusVisible, 'the browser treats the focus as one to show').toBe(true);
  expect(indicator.outlineStyle, 'the focused action has an outline').not.toBe('none');
  expect(indicator.outlineWidth, 'and the outline has a width').toBeGreaterThanOrEqual(2);

  // The service is back by the time the person tries again.
  standIn = await standInForApi(metaAnswer('qa-back-again', 'qa-1.0.0'));
  await page.keyboard.press('Enter');

  await expect(page.getByTestId('status-api-service')).toHaveText('qa-back-again');
  await expect(page.getByTestId('status-unavailable')).toHaveCount(0);
  expect(standIn.requests(), 'trying again asked the API again').toBeGreaterThanOrEqual(1);
});

test('TC-F01-S01-050 in the unavailable state every string comes from the translation file and every element a test reads or presses carries a test id', async ({ page }) => {
  await page.goto('/');
  await expectUnavailable(page, 'API stopped');

  const translated = new Set(allMessages());
  const shown = await stringsShown(page);
  expect(shown.length, 'there are strings to check').toBeGreaterThanOrEqual(5);
  expect(shown.filter((text) => !translated.has(text)), 'strings that are not in apps/web/messages/en.json').toEqual([]);

  for (const id of ['app-name', 'status-panel', 'status-heading', 'status-unavailable', 'status-unavailable-title', 'status-unavailable-body', 'status-retry']) {
    await expect(page.getByTestId(id), id).toBeVisible();
  }
  const present = await testIds(page);
  expect(present.filter((id) => !TEST_ID_NAME.test(id)), 'test ids not named <area>-<thing> in kebab-case').toEqual([]);
  expect(present.filter((id, index) => present.indexOf(id) !== index), 'test ids used more than once').toEqual([]);
  const pressable = await pressables(page);
  expect(pressable.length, 'there is something to press').toBeGreaterThanOrEqual(1);
  expect(pressable.filter((element) => !element.testId), 'elements a person can press that have no test id').toEqual([]);
});

test('TC-F01-S01-051 when the API comes back, "try again" shows its name and version without the web app being restarted', async ({ page, api }) => {
  await page.goto('/');
  await expectUnavailable(page, 'API stopped');

  await startApi();
  const meta = (await (await api.meta()).json()) as Meta;
  await page.getByRole('link', { name: message('status.unavailable.retry') }).click();

  await expect(page.getByTestId('status-api-service')).toHaveText(meta.service);
  await expect(page.getByTestId('status-api-version')).toHaveText(meta.version);
  await expect(page.getByTestId('status-unavailable')).toHaveCount(0);
});
