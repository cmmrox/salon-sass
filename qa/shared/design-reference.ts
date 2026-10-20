/** Expectations from the final supplied HTML cascade and approved CR-007, not product CSS. */
import fs from 'node:fs';
import path from 'node:path';
import { expect, type Locator, type Page } from '@playwright/test';
import { reportDir } from './env';
import { fit } from './page-checks';

export const DESIGN_WIDTHS = [360, 390, 768, 1024, 1280, 1440].map(width => ({ width, height: 1000 }));
export async function appearance(element: Locator) {
  await expect(element).toBeVisible();
  return element.evaluate(el => {
    const s = getComputedStyle(el), r = el.getBoundingClientRect();
    return { background: s.backgroundColor, colour: s.color, radius: s.borderTopLeftRadius, position: s.position,
      font: s.fontFamily, fontSize: s.fontSize, shadow: s.boxShadow, width: r.width, height: r.height,
      padding: s.padding, gap: s.gap, offsetTop: s.top, minHeight: s.minHeight,
      left: r.left, right: r.right, bottom: r.bottom, top: r.top };
  });
}
/**
 * Where each prototype token lives on the root. Since CR-016 (F01-S03 AC-13) theme.css holds the
 * prototype's final values under shadcn/ui's variable names; the values are unchanged.
 */
const THEME_TOKENS: Record<string, string> = {
  background: '--background', secondary: '--secondary', border: '--border', text: '--foreground', 'text-muted': '--muted-foreground',
  focus: '--ring', positive: '--positive', 'positive-surface': '--positive-surface', caution: '--caution',
  'caution-surface': '--caution-surface', negative: '--destructive', 'negative-surface': '--negative-surface' };
export async function referenceTypeAndPalette(page: Page) {
  await expect(page.getByRole('main')).toBeVisible();
  const values = await page.evaluate(async (TOKEN_NAMES) => {
    await document.fonts.ready;
    for (const weight of [400, 500, 600]) await document.fonts.load(`${weight} 15px Poppins`);
    const root = getComputedStyle(document.documentElement), body = getComputedStyle(document.body);
    return { font: body.fontFamily, size: body.fontSize,
      loaded: Array.from(document.fonts).filter(f => f.family.replaceAll(/['"]/g, '') === 'Poppins' && f.status === 'loaded').map(f => f.weight),
      colours: Object.fromEntries(Object.entries(TOKEN_NAMES).map(([key, name]) => [key, root.getPropertyValue(name).trim().toUpperCase()])) };
  }, THEME_TOKENS);
  expect(values.font.split(',')[0].replaceAll(/['"]/g, '').trim()).toBe('Poppins');
  expect(values.size).toBe('15px');
  for (const weight of ['400','500','600']) expect(values.loaded).toContain(weight);
  expect(values.colours).toEqual({ background: '#F3F0EB', secondary: '#EAE5DC', border: '#DDD6CA', text: '#111827', 'text-muted': '#6B7280', focus:'#1D5FD0',positive:'#0F7B55','positive-surface':'#DDF1E6',caution:'#8F5A00','caution-surface':'#FFF4DC',negative:'#B3261E','negative-surface':'#FDE9E7' });
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
/**
 * The workspace navigation's routes: only these, each once (CR-010), plus the Owner's Plan and
 * billing (F03-S02) and Support access (F03-S04) pages, which F03 added to the staff workspace.
 */
const NAVIGATION_ROUTES = ['/workspace/account', '/workspace/staff', '/workspace/audit', '/workspace/billing', '/workspace/support-access'];
async function navigationLinks(nav: Locator) {
  const links = nav.getByRole('link');
  const hrefs = await links.evaluateAll(elements => elements.map(el => el.getAttribute('href')));
  expect(hrefs.length).toBeGreaterThan(0);
  for (const href of hrefs) expect(NAVIGATION_ROUTES).toContain(href);
  expect(new Set(hrefs).size).toBe(hrefs.length);
  expect(await nav.locator('svg').count(), 'each route keeps its prototype glyph').toBe(hrefs.length);
  await expect(nav.locator('[aria-current="page"]')).toHaveCount(1);
  for (const link of await links.all()) {
    const item = await appearance(link);
    expect(item.height, 'rail item height').toBe(46); expect(item.fontSize).toBe('14.5px'); expect(item.padding).toBe('0px 14px');
  }
  return links;
}
/**
 * The workspace navigation (CR-017, F01-S03 AC-15): the shadcn/ui Sidebar. From 900px a fixed rail
 * below the 60px header, 200px wide (236px from 1200px), white with a line on its right; below 900px
 * no navigation is on the page until the header's menu button opens the Sidebar's sheet from the
 * left, which focuses the current route and, on Escape, closes and gives focus back to the button.
 */
export async function referenceNavigation(page: Page, id: string, width: number) {
  // The Sidebar animates its width when the viewport crosses a breakpoint: measure the settled layout.
  await page.evaluate(() => Promise.all(document.getAnimations().map(a => a.finished.catch(() => undefined))));
  const menu = page.getByTestId('workspace-menu');
  if (width >= 900) {
    await expect(menu, 'no menu button beside the rail').toBeHidden();
    const nav = page.getByTestId(id); await expect(nav).toBeVisible();
    const rail = await nav.evaluate(el => { const c = el.closest('[data-slot="sidebar-container"]'); if (!c) return null; const s = getComputedStyle(c), r = c.getBoundingClientRect();
      const inner = c.querySelector('[data-slot="sidebar-inner"]'); return { position: s.position, left: r.left, top: r.top, width: r.width, border: `${s.borderRightWidth} ${s.borderRightColor}`, background: inner ? getComputedStyle(inner).backgroundColor : '' }; });
    expect(rail, 'the navigation sits in the shadcn/ui Sidebar').not.toBeNull();
    expect(rail!.position).toBe('fixed'); expect(rail!.left).toBe(0); expect(rail!.top, 'below the 60px header').toBe(60);
    expect(rail!.width).toBe(width < 1200 ? 200 : 236); expect(rail!.border).toBe('1px rgb(221, 214, 202)'); expect(rail!.background).toBe('rgb(255, 255, 255)');
    await navigationLinks(nav);
    return;
  }
  await expect(page.getByTestId(id), 'no navigation on the page until the menu opens').toHaveCount(0);
  await expect(page.locator('[data-slot="sidebar-container"]:visible')).toHaveCount(0);
  await expect(menu).toBeVisible(); await expect(menu).toHaveAttribute('aria-expanded', 'false');
  const button = await appearance(menu); expect(button.height).toBeGreaterThanOrEqual(44); expect(button.width).toBeGreaterThanOrEqual(44);
  await menu.click();
  const sheet = page.getByRole('dialog', { name: 'Staff workspace' }); await expect(sheet).toBeVisible();
  await expect(menu).toHaveAttribute('aria-expanded', 'true');
  const box = await appearance(sheet); expect(box.left).toBe(0); expect(box.width).toBe(288); expect(box.height).toBe(page.viewportSize()!.height);
  const nav = sheet.getByTestId(id); await expect(nav).toBeVisible();
  await navigationLinks(nav);
  await expect(nav.locator('[aria-current="page"]'), 'the sheet opens on the current route').toBeFocused();
  await page.keyboard.press('Escape'); await expect(sheet).toHaveCount(0);
  await expect(menu, 'focus returns to the menu button').toBeFocused(); await expect(menu).toHaveAttribute('aria-expanded', 'false');
}
/**
 * Cards, fields and actions of the account screens. Since CR-017 a field's visible surface is the
 * Input, the Input Group around a password, or the Select trigger; actions are pill Buttons.
 */
export async function referenceAccountControls(page: Page) {
  const cards = page.locator('main section[data-slot="card"]'); expect(await cards.count()).toBeGreaterThan(0);
  for (const card of await cards.all()) { const s = await appearance(card); expect(s.background).toBe('rgb(255, 255, 255)'); expect(s.radius).toBe('24px'); expect(s.shadow).toBe('none'); }
  const surfaces = page.locator('main [data-slot="input"], main [data-slot="input-group"], main [data-slot="select-trigger"]');
  for (const field of await surfaces.all()) { const s = await appearance(field); expect(s.radius).toBe('20px'); expect(s.height).toBeGreaterThanOrEqual(48); expect(s.background).toBe('rgb(255, 255, 255)'); }
  for (const typed of await page.locator('main input:visible').all()) await expect(typed).toHaveCSS('font-size', '16px');
  const buttons = page.locator('main [data-slot="button"]:not([data-slot="input-group"] *)'); expect(await buttons.count()).toBeGreaterThan(0);
  for (const button of await buttons.all()) { const s = await appearance(button); expect(s.radius).toBe('999px'); expect(s.height).toBeGreaterThanOrEqual(50); }
}
export async function referenceDialog(page: Page, workspace: boolean, width: number) {
  const dialog = page.getByRole('dialog'); await expect(dialog).toBeVisible(); await expect(dialog).toHaveAttribute('aria-modal', 'true');
  const s = await appearance(dialog), height = page.viewportSize()!.height;
  await expect(dialog.getByRole('heading')).toHaveCSS('font-size',workspace&&width>=900?'26px':'22px');
  if (workspace && width >= 900) { expect(s.width).toBe(460); expect(s.right).toBe(width); expect(s.height).toBe(height); expect(s.radius).toBe('0px'); }
  else { expect(s.width).toBe(Math.min(width, 430)); expect(s.bottom).toBe(height); expect(s.height).toBeLessThanOrEqual(height * .9); expect(s.radius).toBe('32px'); }
  await expect(page.getByTestId('account-cancel-action')).toBeFocused();
  await page.keyboard.press('Shift+Tab'); await expect(page.getByTestId('account-confirm-action')).toBeFocused();
  await page.keyboard.press('Tab'); await expect(page.getByTestId('account-cancel-action')).toBeFocused();
}
export async function designScreenshot(page: Page, name: string) {
  // An open modal (Sheet) hides the page, main landmark included, from assistive technology: wait for the modal instead.
  if (await page.getByRole('dialog').count()) await expect(page.getByRole('dialog').first()).toBeVisible();
  else await expect(page.getByRole('main')).toBeVisible();
  const folder = path.join(reportDir, 'design'); fs.mkdirSync(folder, { recursive: true });
  await page.screenshot({ path: path.join(folder, name + '.png'), fullPage: false,
    mask: [page.locator('input'), page.getByTestId('account-setup-key'), page.getByTestId('account-setup-uri')] });
}
