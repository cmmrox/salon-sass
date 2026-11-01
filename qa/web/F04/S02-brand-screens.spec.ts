/**
 * F04-S02 the Branding tab in the browser: who reaches it, the preview and the contrast of its
 * button for any colour, save and publish reaching every salon screen at the next page load,
 * uploads and their states, default images and reset, field errors, a conflict, a support
 * session, loading and unavailable states, every width, the keyboard and accessibility.
 *
 * Expected results are the F04-S02 spec's (acceptance criteria, "UI and UX", "Design notes"),
 * docs/engineering/web.md "Branding" (the text-colour rule, computed independently in
 * qa/shared/salon-brand.ts) and docs/design/ui-ux-standards.md. The publish bar and the leave dialog
 * keep F04-S01's test ids on this tab (handoff), so they are looked up inside the Branding page only.
 * Brand changes that are published happen on salons the operator creates; on alpha, drafts are
 * discarded at the end.
 */
import type { BrowserContext, Locator, Page } from '@playwright/test';

import { DESIGN_WIDTHS } from '../../shared/design-reference';
import { webUrl } from '../../shared/env';
import { test as browserTest, expect, type AccountBrowser } from '../../shared/identity-browser';
import type { Actor } from '../../shared/identity';
import { Operator, ownerSession, supportJson, type SupportStart } from '../../shared/operator';
import { fit, focusIndicator, seriousViolations } from '../../shared/page-checks';
import { Brands, DEFAULT_COLOUR, SWATCHES, brandSnapshot, contrast, fixture, hexOf, textOn } from '../../shared/salon-brand';
import { message } from '../../shared/strings';
import { alpha, salonUrl, type Salon } from '../../shared/tenants';

const test = browserTest.extend<{ operator: Operator; brands: Brands }>({
  operator: async ({ identity }, use) => {
    const operator = new Operator(identity);
    await operator.resetFixtures();
    try { await use(operator); } finally { await operator.resetToBaseline(); }
  },
  brands: async ({ identity }, use) => {
    const brands = new Brands(identity);
    try { await use(brands); } finally { await brands.dispose(); }
  },
});

const BRANDING = '/workspace/settings/branding';
const id = (scope: Page | Locator, name: string) => scope.getByTestId(name);
/** The Branding page's own publish bar and dialogs (their ids are shared with the Profile tab). */
const tab = (page: Page) => page.getByTestId('brand-tab');

async function ready(page: Page) {
  await expect(id(page, 'brand-form')).toBeVisible();
  await expect(id(page, 'brand-loading')).toHaveCount(0);
}

async function openBranding(accounts: AccountBrowser, actor: Actor, salon: Salon = alpha, width = 1280): Promise<{ context: BrowserContext; page: Page }> {
  const context = await accounts.context(salon, [actor], { width });
  const page = await accounts.open(context, BRANDING);
  await ready(page);
  return { context, page };
}

async function newSalon(operator: Operator, accounts: AccountBrowser) {
  const admin = await operator.actor();
  const plan = await operator.plan(admin);
  const { input, created, salon } = await operator.salon(admin, plan.id);
  const owner = await ownerSession(accounts.identity, salon, input);
  return { admin, created, salon: { ...salon, name: input.name }, owner };
}

const rootBrand = (page: Page) => page.evaluate(() => getComputedStyle(document.documentElement).getPropertyValue('--color-brand').trim().toUpperCase());
const bookColours = async (page: Page) => {
  const book = id(page, 'brand-preview-book');
  const [background, color] = await book.evaluate(el => [getComputedStyle(el).backgroundColor, getComputedStyle(el).color]);
  return { background: hexOf(background), text: hexOf(color) };
};
async function typeColour(page: Page, colour: string) {
  await id(page, 'brand-colour').fill(colour);
  await expect.poll(async () => (await bookColours(page)).background, `the preview takes ${colour}`).toBe(colour.toUpperCase());
}

test('TC-F04-S02-024 AC-1: the Owner and Manager open Settings, Branding and see the draft and its preview; the other roles cannot reach it', async ({ accounts, operator, brands }) => {
  await test.step('the Owner of a salon with a full draft', async () => {
    const { salon, owner } = await newSalon(operator, accounts);
    const logo = await brands.upload(salon, owner.tokens, 'logo', fixture('logo-700x600-banded.png'));
    const cover = await brands.upload(salon, owner.tokens, 'cover', fixture('cover-2000x800-exif.jpg'));
    await brands.edit(salon, owner.tokens, { name: 'Preview Salon', tagline: 'Hair and bridal', colour: '#2F4B8A', logo: logo.id, cover: cover.id });
    const { page } = await openBranding(accounts, owner, salon);
    const profileTab = await id(page, 'settings-tab-profile').boundingBox();
    const brandingTab = await id(page, 'settings-tab-branding').boundingBox();
    expect(profileTab && brandingTab && profileTab.x < brandingTab.x, 'the tab bar shows Profile, then Branding').toBe(true);
    await expect(id(page, 'settings-tab-branding')).toHaveAttribute('aria-selected', 'true');
    await expect(id(page, 'brand-name')).toHaveValue('Preview Salon');
    await expect(id(page, 'brand-preview-name')).toHaveText('Preview Salon');
    await expect(id(page, 'brand-preview-tagline')).toHaveText('Hair and bridal');
    await expect(id(page, 'brand-preview-book')).toHaveText(message('settings.brand.previewBook'));
    expect((await bookColours(page)).background, 'the button is in the draft colour').toBe('#2F4B8A');
    await expect(id(page, 'brand-preview-logo').locator('img')).toHaveAttribute('src', new RegExp(logo.id));
    const radius = await id(page, 'brand-preview-logo').evaluate(el => { const s = getComputedStyle(el); return [parseFloat(s.borderTopLeftRadius), el.getBoundingClientRect().width]; });
    expect(radius[0], 'the logo is a circle').toBeGreaterThanOrEqual(radius[1] / 2 - 1);
    expect(await id(page, 'brand-preview-cover').evaluate(el => getComputedStyle(el).backgroundImage)).toContain(cover.id);
  });
  await test.step('the Manager of alpha reaches the tab', async () => {
    const { page, context } = await openBranding(accounts, await accounts.identity.staff(alpha, 'MANAGER'));
    await expect(id(page, 'brand-name')).toHaveValue(alpha.name);
    await context.close();
  });
  for (const role of ['RECEPTIONIST', 'PROFESSIONAL', 'ACCOUNTANT'] as const) {
    await test.step(`${role} gets "no access"`, async () => {
      const context = await accounts.context(alpha, [await accounts.identity.staff(alpha, role)]);
      const page = await accounts.open(context, BRANDING);
      await expect(id(page, 'account-denied')).toBeVisible();
      await expect(id(page, 'brand-form')).toHaveCount(0);
      await context.close();
    });
  }
});

test('TC-F04-S02-025 AC-2: the preview follows the name, tagline and colour before saving, and its button text is readable by the contrast rule for every colour', async ({ accounts }) => {
  const manager = await accounts.identity.staff(alpha, 'MANAGER');
  const { page } = await openBranding(accounts, manager);
  const accent = await rootBrand(page);
  await test.step('name and tagline as typed', async () => {
    await id(page, 'brand-name').fill('Typed Name');
    await expect(id(page, 'brand-preview-name')).toHaveText('Typed Name');
    await id(page, 'brand-tagline').fill('Typed tagline');
    await expect(id(page, 'brand-preview-tagline')).toHaveText('Typed tagline');
  });
  await test.step('the six swatches', async () => {
    await expect(id(page, 'brand-swatch')).toHaveCount(6);
    for (let n = 0; n < 6; n++) {
      await id(page, 'brand-swatch').nth(n).click();
      const { background, text } = await bookColours(page);
      expect(SWATCHES, `swatch ${n} is one of the prototype's`).toContain(background);
      expect(text, `text on ${background}`).toBe(textOn(background));
      expect(contrast(background, text), `contrast on ${background}`).toBeGreaterThanOrEqual(4.5);
    }
  });
  for (const colour of ['#808080', '#777777', '#FFFF00', '#FFFFFF', '#000000', '#FDE68A', '#1F2937', '#d6086a']) {
    await test.step(`typed ${colour}`, async () => {
      await typeColour(page, colour);
      const { background, text } = await bookColours(page);
      expect(text, `text on ${background} by the rule`).toBe(textOn(background));
      expect(contrast(background, text), `at least 4.5:1 on ${background}`).toBeGreaterThanOrEqual(4.5);
    });
  }
  expect(await rootBrand(page), 'the draft colour stays inside the preview: the workspace accent is the published one').toBe(accent);
  expect(await alphaHasUnpublishedBrand(accounts), 'nothing was saved').toBe(false);
});
async function alphaHasUnpublishedBrand(accounts: AccountBrowser) {
  const manager = await accounts.identity.staff(alpha, 'MANAGER');
  return (await new Brands(accounts.identity).draft(alpha, manager.tokens)).hasUnpublishedChanges;
}

test('TC-F04-S02-026 AC-3: a saved brand draft survives a reload without changing any salon screen, and after publishing every screen shows the new colour, name and logo at the next page load', async ({ accounts, operator, brands, browser }) => {
  const { salon, owner } = await newSalon(operator, accounts);
  const publishedName = (await brands.published(salon)).name;
  const publishedColour = (await brands.published(salon)).brandColour;
  const logo = await brands.upload(salon, owner.tokens, 'logo', fixture('logo-300.webp'));
  await brands.edit(salon, owner.tokens, { logo: logo.id });
  const { page } = await openBranding(accounts, owner, salon);
  const signIn = async () => {
    const context = await browser.newContext({ viewport: { width: 1280, height: 1000 }, locale: 'en' });
    const p = await context.newPage();
    await p.goto(`${salonUrl(salon, webUrl)}/workspace/sign-in`);
    await expect(p.getByText(publishedName).or(p.getByText('New Published Name')).first()).toBeVisible();
    return { context, page: p };
  };
  await id(page, 'brand-name').fill('New Published Name');
  await typeColour(page, '#2F6B5E');
  await id(page, 'brand-save').click();
  await expect(page.getByText(message('settings.brand.saved'))).toBeVisible();
  await test.step('after a reload: the draft is there and nothing else changed', async () => {
    await page.reload();
    await ready(page);
    await expect(id(page, 'brand-name')).toHaveValue('New Published Name');
    await expect(id(tab(page), 'publish-bar-status')).toContainText(message('settings.publishBar.unpublished'));
    expect(await rootBrand(page), 'the workspace accent is still the published colour').toBe(publishedColour);
    await expect(id(page, 'workspace-header')).toContainText(publishedName);
    await expect(id(page, 'workspace-logo'), 'no published logo yet').toHaveCount(0);
    const served = await brands.published(salon);
    expect([served.name, served.brandColour, served.logo]).toEqual([publishedName, publishedColour, null]);
    const s = await signIn();
    await expect(s.page.getByText(publishedName).first()).toBeVisible();
    await expect(id(s.page, 'staff-sign-in-logo')).toHaveCount(0);
    await s.context.close();
  });
  await test.step('publish, then the next page load everywhere', async () => {
    await id(tab(page), 'profile-publish').click();
    await expect(page.getByText(message('settings.brand.published'))).toBeVisible();
    await expect(id(tab(page), 'profile-publish')).toBeDisabled();
    await page.reload();
    await ready(page);
    expect(await rootBrand(page), 'the workspace accent').toBe('#2F6B5E');
    await expect(id(page, 'workspace-header')).toContainText('New Published Name');
    await expect(id(page, 'workspace-logo')).toBeVisible();
    const served = await brands.published(salon);
    expect([served.name, served.brandColour, served.logo?.id]).toEqual(['New Published Name', '#2F6B5E', logo.id]);
    const s = await signIn();
    await expect(s.page.getByText('New Published Name').first()).toBeVisible();
    await expect(id(s.page, 'staff-sign-in-logo')).toBeVisible();
    await s.context.close();
  });
});

test('TC-F04-S02-027 AC-4, AC-12: choosing a logo or cover shows uploading, then the image; a file that cannot be used shows its message beside the image; an unsaved upload is guarded on leaving by a link or another Settings tab', async ({ accounts, operator, brands }) => {
  const { salon, owner } = await newSalon(operator, accounts);
  const { page, context } = await openBranding(accounts, owner, salon);
  await test.step('a logo: uploading, then chosen and in the preview', async () => {
    let release!: () => void;
    const held = new Promise<void>(resolve => { release = resolve; });
    await context.route('**/identity-web/staff/brand-image', async route => { await held; await route.continue(); });
    await id(page, 'brand-logo-file').setInputFiles({ name: 'logo.png', mimeType: 'image/png', buffer: fixture('logo-700x600-banded.png').buffer });
    await expect(page.getByText(message('settings.brand.uploading')).first()).toBeVisible();
    release();
    await expect(id(page, 'brand-logo-chosen')).toBeVisible();
    await expect(id(page, 'brand-preview-logo').locator('img')).toHaveAttribute('src', /brand-images\//);
    await context.unroute('**/identity-web/staff/brand-image');
  });
  await test.step('a text file named .png: refused beside the logo, nothing stored', async () => {
    const before = brandSnapshot(salon.id);
    await id(page, 'brand-logo-file').setInputFiles({ name: 'logo.png', mimeType: 'image/png', buffer: fixture('not-an-image.txt').buffer });
    await expect(id(page, 'brand-logo-error')).toBeVisible();
    expect(await id(page, 'brand-logo-file').getAttribute('aria-describedby') ?? (await id(page, 'brand-logo-choose').getAttribute('aria-describedby')) ?? '').toContain('brand-logo-error');
    expect(brandSnapshot(salon.id), 'nothing stored').toBe(before);
  });
  await test.step('a cover: chosen and in the preview', async () => {
    await id(page, 'brand-cover-file').setInputFiles({ name: 'cover.jpg', mimeType: 'image/jpeg', buffer: fixture('cover-2000x800-exif.jpg').buffer });
    await expect(id(page, 'brand-cover-chosen')).toBeVisible();
    await expect.poll(() => id(page, 'brand-preview-cover').evaluate(el => getComputedStyle(el).backgroundImage)).toContain('brand-images');
  });
  await test.step('leaving with the uploaded images not yet saved asks first', async () => {
    await id(page, 'workspace-account').click();
    await expect(id(page, 'profile-leave-dialog')).toBeVisible();
    await id(page, 'profile-leave-stay').click();
    await expect(page).toHaveURL(new RegExp(`${BRANDING}$`));
    await expect(id(page, 'brand-cover-chosen')).toBeVisible();
    expect((await brands.draft(salon, owner.tokens)).brand.logo, 'nothing saved into the draft yet').toBeNull();
  });
  await test.step('changing to another Settings tab with the images not yet saved asks first', async () => {
    // After a client-side tab change the previous tab's markup stays, hidden: look among the visible elements.
    const shown = (name: string) => page.getByTestId(name).filter({ visible: true });
    await shown('settings-tab-rules').click();
    await expect(shown('profile-leave-dialog')).toBeVisible();
    await shown('profile-leave-stay').click();
    await expect(page).toHaveURL(new RegExp(`${BRANDING}$`));
    await expect(shown('settings-tab-branding')).toHaveAttribute('aria-selected', 'true');
    await expect(shown('brand-cover-chosen'), 'Stay keeps the upload').toBeVisible();
    await shown('settings-tab-rules').click();
    await shown('profile-leave-confirm').click();
    await expect(page).toHaveURL(/\/workspace\/settings\/rules$/);
    await expect(shown('rules-tab')).toBeVisible();
    expect((await brands.draft(salon, owner.tokens)).brand.cover, 'leaving saved nothing into the draft').toBeNull();
  });
});

test('TC-F04-S02-028 AC-5: Use default logo and cover show the initial and the neutral cover, and Reset to default branding asks, then sets the default colour and keeps the name, all as a draft', async ({ accounts, operator, brands }) => {
  const { salon, owner } = await newSalon(operator, accounts);
  const logo = await brands.upload(salon, owner.tokens, 'logo', fixture('logo-300.webp'));
  const cover = await brands.upload(salon, owner.tokens, 'cover', fixture('cover-1000x400.webp'));
  const saved = await brands.edit(salon, owner.tokens, { name: 'Zinnia Studio', tagline: 'To be reset', colour: '#141413', logo: logo.id, cover: cover.id });
  await brands.publish(salon, owner.tokens, saved.version);
  const servedBefore = await brands.published(salon);
  const { page } = await openBranding(accounts, owner, salon);
  await id(page, 'brand-logo-default').click();
  await expect(id(page, 'brand-preview-logo').locator('img')).toHaveCount(0);
  await expect(id(page, 'brand-preview-logo')).toHaveText('Z');
  await id(page, 'brand-cover-default').click();
  await expect(id(page, 'brand-preview-cover')).toHaveAttribute('data-image', 'neutral');
  expect(await id(page, 'brand-preview-cover').evaluate(el => getComputedStyle(el).backgroundImage)).toContain('neutral-cover');
  await id(page, 'brand-reset').click();
  await expect(id(page, 'brand-reset-dialog')).toBeVisible();
  await id(page, 'brand-reset-cancel').click();
  await expect(id(page, 'brand-colour'), 'cancelling keeps the colour').toHaveValue('#141413');
  await id(page, 'brand-reset').click();
  await id(page, 'brand-reset-confirm').click();
  await expect(id(page, 'brand-colour')).toHaveValue(DEFAULT_COLOUR);
  await expect(id(page, 'brand-tagline')).toHaveValue('');
  await expect(id(page, 'brand-name'), 'the name is kept').toHaveValue('Zinnia Studio');
  expect((await bookColours(page)).background).toBe(DEFAULT_COLOUR);
  expect(await brands.published(salon), 'the published brand is unchanged until publishing').toEqual(servedBefore);
  const draft = await brands.draft(salon, owner.tokens);
  expect(draft.brand).toEqual({ name: 'Zinnia Studio', tagline: null, colour: DEFAULT_COLOUR, logo: null, cover: null });
});

test('TC-F04-S02-029 AC-6, AC-12: an empty name or a malformed colour shows its message beside the field and saves nothing, and an over-long tagline cannot be typed', async ({ accounts }) => {
  const manager = await accounts.identity.staff(alpha, 'MANAGER');
  const { page } = await openBranding(accounts, manager);
  for (const [field, value, key] of [['name', '   ', 'settings.brand.errors.name'], ['colour', 'red', 'settings.brand.errors.colour']] as const) {
    await test.step(`${field} "${value.slice(0, 10)}"`, async () => {
      await page.reload();
      await ready(page);
      const before = brandSnapshot(alpha.id);
      await id(page, `brand-${field}`).fill(value);
      await id(page, 'brand-save').click();
      const error = id(page, `brand-${field}-error`);
      await expect(error).toBeVisible();
      await expect(error).toContainText(message(key));
      expect(await id(page, `brand-${field}`).getAttribute('aria-describedby') ?? '').toContain(`brand-${field}-error`);
      expect(brandSnapshot(alpha.id), 'nothing saved').toBe(before);
    });
  }
  await test.step('a tagline over 80 characters cannot be typed (the API refuses one sent anyway, TC-F04-S02-006)', async () => {
    await page.reload();
    await ready(page);
    await id(page, 'brand-tagline').fill('T'.repeat(81));
    expect((await id(page, 'brand-tagline').inputValue()).length).toBeLessThanOrEqual(80);
  });
});

test('TC-F04-S02-030 AC-12: when someone else saved the brand first, the screen says so and Reload shows their changes', async ({ accounts, brands }) => {
  const first = await accounts.identity.staff(alpha, 'MANAGER');
  const second = await accounts.identity.staff(alpha, 'MANAGER');
  try {
    const a = await openBranding(accounts, first);
    const b = await openBranding(accounts, second);
    await id(a.page, 'brand-tagline').fill('Their tagline');
    await id(a.page, 'brand-save').click();
    await expect(a.page.getByText(message('settings.brand.saved'))).toBeVisible();
    const before = brandSnapshot(alpha.id);
    await id(b.page, 'brand-tagline').fill('My tagline');
    await id(b.page, 'brand-save').click();
    await expect(id(b.page, 'brand-conflict')).toBeVisible();
    expect(brandSnapshot(alpha.id), 'the later save changed nothing').toBe(before);
    await id(b.page, 'brand-conflict-reload').click();
    await ready(b.page);
    await expect(id(b.page, 'brand-tagline')).toHaveValue('Their tagline');
  } finally {
    await brands.discard(alpha, first.tokens, (await brands.draft(alpha, first.tokens)).version);
  }
});

test('TC-F04-S02-031 AC-9: a support session sees the Branding tab read-only with every control disabled', async ({ accounts, operator, brands, browser }) => {
  const { created, salon, owner } = await newSalon(operator, accounts);
  await brands.edit(salon, owner.tokens, { tagline: 'Support reads this' });
  const support = await operator.actor('OPERATOR_SUPPORT');
  const session = await supportJson<SupportStart>(await operator.startSupport(support, created.id, { reason: 'QA F04 support reads the brand' }), 'supportStart', 201);
  const context = await browser.newContext({ viewport: { width: 1280, height: 1000 }, locale: 'en' });
  try {
    const entry = await context.newPage();
    await entry.goto(`${salonUrl(salon, webUrl)}/workspace/support#${session.entryCode}`);
    await expect(entry.getByTestId('support-banner')).toBeVisible();
    const page = await context.newPage();
    await page.goto(`${salonUrl(salon, webUrl)}${BRANDING}`);
    await ready(page);
    await expect(id(page, 'brand-read-only')).toBeVisible();
    await expect(id(page, 'brand-tagline')).toHaveValue('Support reads this');
    for (const control of ['brand-name', 'brand-tagline', 'brand-colour', 'brand-logo-choose', 'brand-cover-choose', 'brand-reset', 'brand-save']) {
      await expect(id(page, control), `${control} disabled`).toBeDisabled();
    }
    for (const control of ['profile-publish', 'profile-discard']) await expect(id(tab(page), control), `${control} disabled`).toBeDisabled();
    for (const swatch of await id(page, 'brand-swatch').all()) await expect(swatch).toBeDisabled();
  } finally {
    await context.close();
  }
});

test('TC-F04-S02-032 AC-12: the Branding tab shows its loading state, and an unreachable service is said plainly, keeps what was typed and recovers', async ({ accounts, brands }) => {
  const manager = await accounts.identity.staff(alpha, 'MANAGER');
  const context = await accounts.context(alpha, [manager]);
  try {
    await test.step('loading, then the form', async () => {
      let release!: () => void;
      const held = new Promise<void>(resolve => { release = resolve; });
      await context.route('**/identity-web/staff/brand-draft', async route => { await held; await route.continue(); });
      const page = await accounts.open(context, BRANDING);
      await expect(id(page, 'brand-loading')).toBeVisible();
      release();
      await ready(page);
      await context.unroute('**/identity-web/staff/brand-draft');
      await page.close();
    });
    await test.step('unreachable on opening, then Try again', async () => {
      await context.route('**/identity-web/staff/brand-draft', route => route.abort('connectionfailed'));
      const page = await accounts.open(context, BRANDING);
      await expect(id(page, 'brand-unavailable')).toBeVisible();
      await context.unroute('**/identity-web/staff/brand-draft');
      await id(page, 'brand-retry').click();
      await ready(page);
      await page.close();
    });
    await test.step('unreachable on saving: nothing saved, the typing kept', async () => {
      const page = await accounts.open(context, BRANDING);
      await ready(page);
      const before = brandSnapshot(alpha.id);
      await id(page, 'brand-tagline').fill('Typed while offline');
      await context.route('**/identity-web/staff/brand-save', route => route.abort('connectionfailed'));
      await id(page, 'brand-save').click();
      await expect(id(page, 'brand-unavailable')).toBeVisible();
      await expect(id(page, 'brand-tagline')).toHaveValue('Typed while offline');
      expect(brandSnapshot(alpha.id)).toBe(before);
      await context.unroute('**/identity-web/staff/brand-save');
      await id(page, 'brand-save').click();
      await expect(page.getByText(message('settings.brand.saved'))).toBeVisible();
    });
  } finally {
    await brands.discard(alpha, manager.tokens, (await brands.draft(alpha, manager.tokens)).version);
  }
});

test('TC-F04-S02-033 AC-12: at every width of the matrix the Branding tab and its publish bar fit with no sideways scrolling, every control reachable and the preview placed as the prototype places it', async ({ accounts, operator, brands }) => {
  const { salon, owner } = await newSalon(operator, accounts);
  const logo = await brands.upload(salon, owner.tokens, 'logo', fixture('logo-300.webp'));
  const cover = await brands.upload(salon, owner.tokens, 'cover', fixture('cover-2000x800-exif.jpg'));
  await brands.edit(salon, owner.tokens, { name: 'N'.repeat(60), tagline: 'Long tagline words '.repeat(5).slice(0, 80), logo: logo.id, cover: cover.id });
  const { page } = await openBranding(accounts, owner, salon, 360);
  for (const viewport of DESIGN_WIDTHS) {
    await test.step(`${viewport.width} wide`, async () => {
      await page.setViewportSize(viewport);
      const fits = await fit(page);
      expect(fits.counted).toBeGreaterThan(0);
      expect(fits.pageScrolls, 'no sideways scrolling').toBe(false);
      // The Settings tab rail scrolls sideways on phones, as the prototype's does (.brtabs): a tab past its edge is scrolled to, not outside the page; each tab is proved reachable below.
      expect(fits.outside.filter(c => !c.startsWith('settings-tab-')), 'nothing outside the viewport').toEqual([]);
      for (const control of ['settings-tab-profile', 'settings-tab-hours', 'settings-tab-rules', 'settings-tab-branding', 'brand-name', 'brand-tagline', 'brand-colour', 'brand-swatches', 'brand-logo-choose', 'brand-logo-default', 'brand-cover-choose', 'brand-cover-default', 'brand-reset', 'brand-save', 'brand-preview']) {
        await id(page, control).scrollIntoViewIfNeeded();
        await expect(id(page, control), `${control} at ${viewport.width}`).toBeVisible();
      }
      for (const control of ['profile-publish', 'profile-discard']) {
        await id(tab(page), control).scrollIntoViewIfNeeded();
        await expect(id(tab(page), control)).toBeVisible();
      }
      const form = (await id(page, 'brand-form').boundingBox())!;
      const preview = (await id(page, 'brand-preview').boundingBox())!;
      if (viewport.width < 900) expect(preview.y + preview.height, 'the preview is above the form on narrow screens').toBeLessThanOrEqual(form.y + 1);
      else expect(preview.x + 1 >= form.x + form.width || form.x + 1 >= preview.x + preview.width, 'the preview is beside the form on wide screens').toBe(true);
    });
  }
});

test('TC-F04-S02-034 AC-12: the Branding tab has no serious accessibility violation with a dark and a light draft colour or with a field error, its swatches are named, and it can be used with the keyboard alone', async ({ accounts, brands }) => {
  const manager = await accounts.identity.staff(alpha, 'MANAGER');
  const { page } = await openBranding(accounts, manager);
  try {
    for (const colour of ['#141413', '#FDE68A']) {
      await test.step(`draft colour ${colour}`, async () => {
        await typeColour(page, colour);
        expect(await seriousViolations(page)).toEqual([]);
      });
    }
    await test.step('the swatches have distinct accessible names', async () => {
      const names = await Promise.all((await id(page, 'brand-swatch').all()).map(s => s.getAttribute('aria-label')));
      expect(names.length).toBe(6);
      expect([...names].sort(), 'each of the six swatches is named by its colour').toEqual(SWATCHES.map(c => message('settings.brand.useColour').replace('{colour}', c)).sort());
    });
    await test.step('keyboard: a swatch by the arrow keys, then Save draft by Tab and Enter', async () => {
      await page.reload();
      await ready(page);
      await page.locator('body').click({ position: { x: 1, y: 1 } });
      let steps = 0;
      const isSwatch = () => page.evaluate(() => document.activeElement?.getAttribute('data-testid') === 'brand-swatch');
      while (!(await isSwatch()) && steps++ < 80) await page.keyboard.press('Tab');
      expect(await isSwatch(), 'a swatch is reached by Tab').toBe(true);
      const before = (await bookColours(page)).background;
      await page.keyboard.press('ArrowRight');
      await page.keyboard.press('Space');
      await expect.poll(async () => (await bookColours(page)).background, 'the arrow keys choose another swatch').not.toBe(before);
      steps = 0;
      while (!(await id(page, 'brand-save').evaluate(el => el === document.activeElement)) && steps++ < 80) await page.keyboard.press('Tab');
      const focus = await focusIndicator(id(page, 'brand-save'));
      expect(focus.focused, 'Save draft reached by Tab').toBe(true);
      expect(focus.focusVisible && focus.outlineStyle !== 'none' && focus.outlineWidth > 0, 'the focus is visible').toBe(true);
      await page.keyboard.press('Enter');
      await expect(page.getByText(message('settings.brand.saved'))).toBeVisible();
    });
    await test.step('field error', async () => {
      await id(page, 'brand-name').fill('');
      await id(page, 'brand-save').click();
      await expect(id(page, 'brand-name-error')).toBeVisible();
      expect(await seriousViolations(page)).toEqual([]);
    });
  } finally {
    await brands.discard(alpha, manager.tokens, (await brands.draft(alpha, manager.tokens)).version);
  }
});
