/**
 * F01-S01: the web shell's status page and not-found page while the API is up. Expected results
 * are AC-7 and AC-9, the spec's "UI and UX" section, and docs/design/ui-ux-standards.md.
 *
 * An element is named the way a person perceives it (its role and its words, the words taken from
 * the translation file) or by its test id; never by a class, a position or a copied string.
 */
import type { Meta } from '@qa/api-client';
import { expect, test } from '@qa/fixtures';
import { fit, focusIndicator, pressables, seriousViolations, stringsShown, TEST_ID_NAME, testIds, WIDTHS } from '@qa/page-checks';
import { allMessages, message } from '@qa/strings';

const UNKNOWN_ADDRESS = '/qa-no-such-page';

test('TC-F01-S01-032 the home page shows the service name and version the API reports', async ({ page, api }) => {
  const meta = (await (await api.meta()).json()) as Meta;
  expect(meta.service.length, 'the API reports a service name to compare with').toBeGreaterThan(0);
  expect(meta.version.length, 'the API reports a version to compare with').toBeGreaterThan(0);

  await page.goto('/');

  await expect(page.getByTestId('status-api-service')).toHaveText(meta.service);
  await expect(page.getByTestId('status-api-version')).toHaveText(meta.version);
  await expect(page.getByTestId('status-loaded')).toBeVisible();
  await expect(page.getByText(message('status.loaded.summary'), { exact: true })).toBeVisible();
  // One state at a time: neither of the other two is on the page with it.
  await expect(page.getByTestId('status-loading')).toHaveCount(0);
  await expect(page.getByTestId('status-unavailable')).toHaveCount(0);
  // Each value sits beside the word that says what it is.
  await expect(page.getByRole('term').filter({ hasText: message('status.loaded.service') })).toBeVisible();
  await expect(page.getByRole('term').filter({ hasText: message('status.loaded.version') })).toBeVisible();
});

test('TC-F01-S01-033 the status page shows the product\'s placeholder name and says what the page is', async ({ page }) => {
  await page.goto('/');

  await expect(page.getByTestId('app-name')).toHaveText(message('app.name'));
  await expect(page.getByRole('heading', { level: 1, name: message('status.heading') })).toBeVisible();
  await expect(page.getByRole('heading', { level: 1 })).toHaveCount(1);
  await expect(page).toHaveTitle(message('app.name'));
  await expect(page.getByRole('main')).toHaveCount(1);
  expect(await page.locator('html').getAttribute('lang'), 'the page declares its language').toBe('en');
});

test('TC-F01-S01-034 an address the web app does not have answers 404 with a not-found page that leads home', async ({ page }) => {
  const response = await page.goto(UNKNOWN_ADDRESS);

  expect(response?.status()).toBe(404);
  await expect(page.getByRole('heading', { level: 1, name: message('notFound.title') })).toBeVisible();
  await expect(page.getByTestId('not-found-body')).toHaveText(message('notFound.body'));
  await expect(page.getByTestId('app-name')).toHaveText(message('app.name'));

  await page.getByRole('link', { name: message('notFound.home') }).click();

  await expect(page).toHaveURL(/\/$/);
  await expect(page.getByTestId('status-loaded')).toBeVisible();
});

test('TC-F01-S01-035 at 360 and 1280 wide the status page and the not-found page fit without scrolling sideways', async ({ page }) => {
  for (const address of ['/', UNKNOWN_ADDRESS]) {
    for (const size of WIDTHS) {
      await page.setViewportSize({ width: size.width, height: size.height });
      await page.goto(address);
      await expect(page.getByTestId(address === '/' ? 'status-loaded' : 'not-found')).toBeVisible();

      const result = await fit(page);
      const where = `${address} at ${size.width} wide`;
      expect(result.counted, `${where}: there are elements to measure`).toBeGreaterThanOrEqual(4);
      expect(result.pageScrolls, `${where}: the page scrolls sideways`).toBe(false);
      expect(result.outside, `${where}: elements reaching outside the viewport`).toEqual([]);
      expect(result.clipped, `${where}: elements holding more than they show`).toEqual([]);
    }
  }
});

test('TC-F01-S01-036 an automated accessibility scan of the status page and the not-found page finds nothing serious', async ({ page }) => {
  for (const [address, landmark] of [['/', 'status-loaded'], [UNKNOWN_ADDRESS, 'not-found']] as const) {
    await page.goto(address);
    await expect(page.getByTestId(landmark)).toBeVisible();

    expect(await seriousViolations(page), `serious or critical violations on ${address}`).toEqual([]);
  }
});

test('TC-F01-S01-037 the not-found page can be used with the keyboard alone, and shows where the focus is', async ({ page }) => {
  await page.goto(UNKNOWN_ADDRESS);
  const home = page.getByRole('link', { name: message('notFound.home') });
  await expect(home).toBeVisible();

  await page.keyboard.press('Tab');

  const indicator = await focusIndicator(home);
  expect(indicator.focused, 'the first Tab reaches the only link on the page').toBe(true);
  expect(indicator.focusVisible, 'the browser treats the focus as one to show').toBe(true);
  expect(indicator.outlineStyle, 'the focused link has an outline').not.toBe('none');
  expect(indicator.outlineWidth, 'and the outline has a width').toBeGreaterThanOrEqual(2);

  await page.keyboard.press('Enter');

  await expect(page).toHaveURL(/\/$/);
  await expect(page.getByTestId('status-loaded')).toBeVisible();
});

test('TC-F01-S01-038 every string shown on the status page and the not-found page comes from the translation file', async ({ page, api }) => {
  const meta = (await (await api.meta()).json()) as Meta;
  const translated = new Set(allMessages());

  for (const [address, landmark] of [['/', 'status-loaded'], [UNKNOWN_ADDRESS, 'not-found']] as const) {
    await page.goto(address);
    await expect(page.getByTestId(landmark)).toBeVisible();

    const shown = await stringsShown(page);
    expect(shown.length, `${address}: there are strings to check`).toBeGreaterThanOrEqual(4);
    // What the API said is data, not wording; everything else must be a string of the file.
    const fromApi = address === '/' ? [meta.service, meta.version] : [];
    const untranslated = shown.filter((text) => !translated.has(text) && !fromApi.includes(text));
    expect(untranslated, `${address}: strings that are not in apps/web/messages/en.json`).toEqual([]);
  }
});

test('TC-F01-S01-039 every element a test reads or presses on the status page and the not-found page carries a test id', async ({ page }) => {
  const expected = {
    '/': ['app-name', 'status-panel', 'status-heading', 'status-loaded', 'status-summary', 'status-api-service', 'status-api-version'],
    [UNKNOWN_ADDRESS]: ['app-name', 'not-found', 'not-found-title', 'not-found-body', 'not-found-home'],
  };
  for (const [address, ids] of Object.entries(expected)) {
    await page.goto(address);
    for (const id of ids) {
      await expect(page.getByTestId(id), `${address}: ${id}`).toBeVisible();
    }
    const present = await testIds(page);
    expect(present.length, `${address}: there are test ids to check`).toBeGreaterThanOrEqual(ids.length);
    expect(present.filter((id) => !TEST_ID_NAME.test(id)), `${address}: test ids not named <area>-<thing> in kebab-case`).toEqual([]);
    expect(present.filter((id, index) => present.indexOf(id) !== index), `${address}: test ids used more than once`).toEqual([]);
    const withoutId = (await pressables(page)).filter((element) => !element.testId);
    expect(withoutId, `${address}: elements a person can press that have no test id`).toEqual([]);
  }
  // The not-found page has something to press, so the rule above was really applied to something.
  await page.goto(UNKNOWN_ADDRESS);
  expect((await pressables(page)).length, 'the not-found page has a link to press').toBeGreaterThanOrEqual(1);
});
