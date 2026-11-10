/**
 * An upload over a slow connection, for the cases of BUG-F04-001: the browser's own upload is throttled through the
 * Chrome DevTools Protocol (Network.emulateNetworkConditions), so the request's body takes longer to send than the
 * web app's three-second budget for a call to the API. docs/engineering/web.md, "Structure": an upload's limit starts
 * once its body has arrived, and an upload has its own stated limit.
 */
import type { Page } from '@playwright/test';

import { expect } from '@playwright/test';

/** The web app's budget for one call to the API, which a slow upload must outlast here to prove anything. */
export const API_BUDGET_MS = 3_000;

export type SlowAnswer = { status: number; body: string; ms: number };

/**
 * Runs `act` (which chooses the file) with the page's upload throttled to `bytesPerSecond`, waits for the answer to the
 * upload request whose path ends with `action`, and returns it with how long it took. Asserts the body really took
 * longer to send than the API budget, so a pass is never a fast upload.
 */
export async function slowUpload(page: Page, action: string, act: () => Promise<void>, bytesPerSecond = 600): Promise<SlowAnswer> {
  const cdp = await page.context().newCDPSession(page);
  await cdp.send('Network.enable');
  await cdp.send('Network.emulateNetworkConditions', { offline: false, latency: 0, downloadThroughput: -1, uploadThroughput: bytesPerSecond });
  try {
    const answered = page.waitForResponse(r => new URL(r.url()).pathname.endsWith(`/${action}`) && r.request().method() !== 'GET', { timeout: 90_000 });
    const started = Date.now();
    await act();
    const response = await answered;
    const ms = Date.now() - started;
    const answer = { status: response.status(), body: (await response.text()).slice(0, 300), ms };
    expect(ms, `the upload took longer than the ${API_BUDGET_MS} ms budget to send (${JSON.stringify(answer)})`).toBeGreaterThan(API_BUDGET_MS + 500);
    return answer;
  } finally {
    await cdp.send('Network.emulateNetworkConditions', { offline: false, latency: 0, downloadThroughput: -1, uploadThroughput: -1 });
    await cdp.detach();
  }
}
