/** Expectations from the final supplied HTML cascade and approved CR-007, not product CSS. */
import fs from 'node:fs';
import path from 'node:path';
import { expect, type Locator, type Page } from '@playwright/test';
import { reportDir } from './env';
import { fit } from './page-checks';

export const DESIGN_WIDTHS = [360, 390, 768, 1024, 1280, 1440].map(width => ({ width, height: 1000 }));
export async function appearance(element: Locator) {
  return element.evaluate(el => {
    const s = getComputedStyle(el), r = el.getBoundingClientRect();
    return { background: s.backgroundColor, colour: s.color, radius: s.borderTopLeftRadius, position: s.position,
      font: s.fontFamily, fontSize: s.fontSize, shadow: s.boxShadow, width: r.width, height: r.height,
      left: r.left, right: r.right, bottom: r.bottom, top: r.top };
  });
}
export async function referenceTypeAndPalette(page: Page) {
  const values = await page.evaluate(async () => {
    await document.fonts.ready;
    for (const weight of [400, 500, 600]) await document.fonts.load(`${weight} 15px Poppins`);
    const root = getComputedStyle(document.documentElement), body = getComputedStyle(document.body);
    return { font: body.fontFamily, size: body.fontSize,
      loaded: Array.from(document.fonts).filter(f => f.family.replaceAll(/['"]/g, '') === 'Poppins' && f.status === 'loaded').map(f => f.weight),
      colours: Object.fromEntries(['background','secondary','border','text','text-muted'].map(key => [key, root.getPropertyValue(`--color-${key}`).trim().toUpperCase()])) };
  });
  expect(values.font.split(',')[0].replaceAll(/['"]/g, '').trim()).toBe('Poppins');
  expect(values.size).toBe('15px');
  for (const weight of ['400','500','600']) expect(values.loaded).toContain(weight);
  expect(values.colours).toEqual({ background: '#F3F0EB', secondary: '#EAE5DC', border: '#DDD6CA', text: '#111827', 'text-muted': '#6B7280' });
}
/** Only the reference's named compact identity headers may deliberately ellipsize. */
export async function referenceFit(page: Page, fullName?: string) {
  const result = await fit(page);
  const ids = ['salon-name', ...(await page.getByTestId('workspace-header').count() ? ['account-home'] : [])];
  for (const id of ids) {
    const target = page.getByTestId(id);
    if (!await target.count()) continue;
    if (fullName) { await expect(target).toHaveText(fullName); await expect(target).toHaveAttribute('title', fullName); }
    const overflow = await target.evaluate(el => { const s = getComputedStyle(el); return { whiteSpace: s.whiteSpace, textOverflow: s.textOverflow }; });
    expect(overflow).toEqual({ whiteSpace: 'nowrap', textOverflow: 'ellipsis' });
  }
  return { ...result, clipped: result.clipped.filter(value => !ids.some(id => value.startsWith(`${id} (`))) };
}
export async function referenceCustomerShell(page: Page, width: number) {
  const shell = await appearance(page.getByTestId('salon-shell'));
  expect(shell.width).toBe(Math.min(width, 430)); expect(shell.left).toBe((width - shell.width) / 2);
  expect(shell.background).toBe('rgb(243, 240, 235)');
  const header = await appearance(page.getByTestId('salon-header'));
  expect(header.position).toBe('sticky'); expect(header.background).toBe('rgb(243, 240, 235)');
  expect(header.height).toBe(50);
  const identity = await appearance(page.getByTestId('salon-identity'));
  expect(identity.width).toBe(32); expect(identity.height).toBe(32); expect(identity.radius).toBe('999px');
  await referenceNavigation(page, 'salon-navigation', width, true);
}
export async function referenceNavigation(page: Page, id: string, width: number, customer = false) {
  const nav = page.getByTestId(id); await expect(nav).toBeVisible();
  const styles = await appearance(nav), links = nav.getByRole('link');
  const hrefs = await links.evaluateAll(elements => elements.map(el => el.getAttribute('href')));
  expect(hrefs.length).toBeGreaterThan(0);
  const allowed = ['/', '/account', '/sign-in', '/workspace/sign-in', '/workspace/account', '/workspace/staff', '/workspace/audit'];
  for (const href of hrefs) expect(allowed).toContain(href);
  expect(new Set(hrefs).size).toBe(hrefs.length);
  expect(await nav.locator('svg').count()).toBe(hrefs.length);
  await expect(nav.locator('[aria-current="page"]')).toHaveCount(1);
  if (customer || width < 900) {
    expect(styles.position).toBe('fixed'); expect(styles.bottom).toBe(page.viewportSize()!.height);
    expect(styles.width).toBe(customer ? Math.min(width, 430) : width);
  } else {
    expect(styles.position).toBe('sticky'); expect(styles.width).toBe(width < 1200 ? 200 : 236);
    expect(styles.top).toBe(60);
  }
}
export async function referenceAccountControls(page: Page) {
  const cards = page.locator('main section'); expect(await cards.count()).toBeGreaterThan(0);
  for (const card of await cards.all()) { const s = await appearance(card); expect(s.background).toBe('rgb(255, 255, 255)'); expect(s.radius).toBe('24px'); expect(s.shadow).toBe('none'); }
  const fields = page.locator('main input:not([type="checkbox"]), main select');
  for (const field of await fields.all()) { const s = await appearance(field); expect(s.radius).toBe('20px'); expect(s.height).toBeGreaterThanOrEqual(48); expect(s.fontSize).toBe('16px'); }
  const buttons = page.locator('main button'); expect(await buttons.count()).toBeGreaterThan(0);
  for (const button of await buttons.all()) { const s = await appearance(button); expect(s.radius).toBe('999px'); expect(s.height).toBeGreaterThanOrEqual(50); }
}
export async function referenceDialog(page: Page, workspace: boolean, width: number) {
  const dialog = page.getByRole('dialog'); await expect(dialog).toBeVisible(); await expect(dialog).toHaveAttribute('aria-modal', 'true');
  const s = await appearance(dialog), height = page.viewportSize()!.height;
  if (workspace && width >= 900) { expect(s.width).toBe(460); expect(s.right).toBe(width); expect(s.height).toBe(height); expect(s.radius).toBe('0px'); }
  else { expect(s.width).toBe(Math.min(width, 430)); expect(s.bottom).toBe(height); expect(s.height).toBeLessThanOrEqual(height * .9); expect(s.radius).toBe('32px'); }
  await expect(page.getByTestId('account-cancel-action')).toBeFocused();
  await page.keyboard.press('Shift+Tab'); await expect(page.getByTestId('account-confirm-action')).toBeFocused();
  await page.keyboard.press('Tab'); await expect(page.getByTestId('account-cancel-action')).toBeFocused();
}
export async function designScreenshot(page: Page, name: string) {
  const folder = path.join(reportDir, 'design'); fs.mkdirSync(folder, { recursive: true });
  await page.screenshot({ path: path.join(folder, name + '.png'), fullPage: false,
    mask: [page.locator('input'), page.getByTestId('account-setup-key'), page.getByTestId('account-setup-uri')] });
}
