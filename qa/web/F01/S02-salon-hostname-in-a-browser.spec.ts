/**
 * F01-S02, AC-11: a salon's hostname works from a browser, on the API's port and on the web
 * app's, "whether the name resolves to 127.0.0.1 or ::1 first". curl and Node.js are
 * TC-F01-S02-061, in qa/checks/F01/S02-listening.sh.
 *
 * No screen changes in this stage (the branded shell is F01-S03), so nothing here judges a page's
 * content: only that a browser given a salon's address reaches the product, over either loopback
 * address, and is answered for that salon. Which address the browser uses is fixed for the test
 * by Chromium's own resolver rules, and read back from the connection.
 */
import { chromium, type Browser } from '@playwright/test';

import { SALON_PATH } from '@qa/api-client';
import { apiUrl, webUrl } from '@qa/env';
import { expect, test } from '@qa/fixtures';
import { alpha, salons, salonUrl, type Salon } from '@qa/tenants';

/** Opens the salon's brand on the API and the salon's hostname on the web app, and returns the addresses reached. */
async function openSalon(browser: Browser, salon: Salon): Promise<{ api: string; web: string }> {
  const context = await browser.newContext();
  try {
    const page = await context.newPage();

    const brand = await page.goto(`${salonUrl(salon, apiUrl)}${SALON_PATH}`);
    expect(brand, `${salon.hostname}: the API answered the browser`).not.toBeNull();
    expect(brand!.status(), `${salon.hostname}: the API's status`).toBe(200);
    expect(await brand!.json(), `${salon.hostname}: the brand the browser was given`).toEqual({
      slug: salon.slug,
      name: salon.name,
      brandColour: salon.brandColour,
    });
    // The address bar still shows the salon's hostname: the browser was not sent elsewhere.
    expect(new URL(page.url()).hostname).toBe(salon.hostname);
    const apiAddress = (await brand!.serverAddr())?.ipAddress ?? '';

    const home = await page.goto(`${salonUrl(salon, webUrl)}/`);
    expect(home, `${salon.hostname}: the web app answered the browser`).not.toBeNull();
    expect(home!.status(), `${salon.hostname}: the web app's status`).toBe(200);
    expect(new URL(page.url()).hostname).toBe(salon.hostname);
    await expect(page.locator('body'), `${salon.hostname}: the web app's page has content`).not.toBeEmpty();
    const webAddress = (await home!.serverAddr())?.ipAddress ?? '';

    return { api: apiAddress, web: webAddress };
  } finally {
    await context.close();
  }
}

const strip = (address: string): string => address.replace(/^\[|\]$/g, '');

test('TC-F01-S02-064 a browser reaches each salon on its hostname, on the API\'s port and the web app\'s, over 127.0.0.1 and over ::1', async ({ browser }) => {
  // As the browser itself resolves the name.
  for (const salon of salons) {
    const reached = await openSalon(browser, salon);
    expect(['127.0.0.1', '::1'], `${salon.hostname}: a loopback address was reached on the API`).toContain(strip(reached.api));
    expect(['127.0.0.1', '::1'], `${salon.hostname}: a loopback address was reached on the web app`).toContain(strip(reached.web));
  }

  // With the name made to resolve to each loopback address in turn.
  for (const [address, literal] of [['127.0.0.1', '127.0.0.1'], ['::1', '[::1]']] as const) {
    const pinned = await chromium.launch({ args: [`--host-resolver-rules=MAP ${alpha.hostname} ${literal}`] });
    try {
      const reached = await openSalon(pinned, alpha);
      expect(strip(reached.api), `${alpha.hostname} resolved to ${address}: the address the API was reached on`).toBe(address);
      expect(strip(reached.web), `${alpha.hostname} resolved to ${address}: the address the web app was reached on`).toBe(address);
    } finally {
      await pinned.close();
    }
  }
});
