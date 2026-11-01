/**
 * F04-S01 the Settings page and its Profile tab in the browser: who sees it, the save-preview-
 * publish path, discard, field errors, a conflict between two people, a support session, the
 * unsaved-edits guard, the loading and unavailable states, every width of the matrix, and
 * accessibility.
 *
 * Expected results are the F04-S01 spec's (acceptance criteria and "UI and UX") and
 * docs/design/ui-ux-standards.md; words come from the translation file and elements from the test
 * ids the handoff names. Rules the API cases already prove (every validation row, every role on
 * every endpoint) are not proved again here: each case checks what the person sees, and checks
 * persistence once through the API.
 */
import type { BrowserContext, Page } from '@playwright/test';

import { DESIGN_WIDTHS } from '../../shared/design-reference';
import { webUrl } from '../../shared/env';
import { test as browserTest, expect, type AccountBrowser } from '../../shared/identity-browser';
import type { Actor, Role } from '../../shared/identity';
import { Operator, ownerSession, supportJson, type SupportStart } from '../../shared/operator';
import { fit, focusIndicator, seriousViolations } from '../../shared/page-checks';
import { Profiles, e164, fullInput, profileSnapshot, unique } from '../../shared/salon-profile';
import { message } from '../../shared/strings';
import { alpha, salonUrl, type Salon } from '../../shared/tenants';

const test = browserTest.extend<{ operator: Operator; profiles: Profiles }>({
  operator: async ({ identity }, use) => {
    const operator = new Operator(identity);
    await operator.resetFixtures();
    try { await use(operator); } finally { await operator.resetToBaseline(); }
  },
  profiles: async ({ identity }, use) => { await use(new Profiles(identity)); },
});

const PROFILE = '/workspace/settings/profile';
const FIELDS = ['phone', 'whatsapp', 'address', 'location', 'description', 'instagram', 'facebook', 'website'] as const;
const id = (page: Page, name: string) => page.getByTestId(name);
const field = (page: Page, name: (typeof FIELDS)[number]) => id(page, `profile-${name}`);

/** Opens the Profile tab signed in as the actor and waits until the form shows the draft. */
async function openProfile(accounts: AccountBrowser, actor: Actor, width = 1280, salon: Salon = alpha): Promise<{ context: BrowserContext; page: Page }> {
  const context = await accounts.context(salon, [actor], { width });
  const page = await accounts.open(context, PROFILE);
  await ready(page);
  return { context, page };
}

async function ready(page: Page) {
  await expect(id(page, 'profile-form')).toBeVisible();
  await expect(id(page, 'profile-loading')).toHaveCount(0);
}

async function staffOf(accounts: AccountBrowser, role: Role) {
  return accounts.identity.staff(alpha, role);
}

test('TC-F04-S01-018 AC-2: the Owner and Manager reach the Profile tab from Settings; the Receptionist, Professional and Accountant see no Settings and get "no access" by address', async ({ accounts }) => {
  for (const role of ['OWNER', 'MANAGER'] as const) {
    await test.step(`${role} sees Settings and reaches Profile`, async () => {
      const context = await accounts.context(alpha, [await staffOf(accounts, role)]);
      const page = await accounts.open(context, '/workspace/account');
      await expect(id(page, 'workspace-settings')).toBeVisible();
      await id(page, 'workspace-settings').click();
      await expect(page).toHaveURL(new RegExp(`${PROFILE}$`));
      await expect(id(page, 'settings-tab-profile')).toBeVisible();
      await ready(page);
      const direct = await accounts.open(context, '/workspace/settings');
      await expect(direct, 'Settings opens the first tab the person may see').toHaveURL(new RegExp(`${PROFILE}$`));
      await context.close();
    });
  }
  for (const role of ['RECEPTIONIST', 'PROFESSIONAL', 'ACCOUNTANT'] as const) {
    await test.step(`${role} sees no Settings and is told "no access"`, async () => {
      const context = await accounts.context(alpha, [await staffOf(accounts, role)]);
      const page = await accounts.open(context, '/workspace/account');
      await expect(id(page, 'workspace-account')).toBeVisible();
      await expect(id(page, 'workspace-settings'), 'no Settings entry').toHaveCount(0);
      for (const path of ['/workspace/settings', PROFILE]) {
        const direct = await accounts.open(context, path);
        await expect(id(direct, 'account-denied'), `${path}: no access`).toBeVisible();
        await expect(id(direct, 'profile-form'), `${path}: no form`).toHaveCount(0);
      }
      await context.close();
    });
  }
});

test('TC-F04-S01-019 AC-3, AC-4, AC-11: a Manager types a profile, sees the preview follow, saves a draft that survives a reload, and publishes it to the public salon', async ({ accounts, profiles }) => {
  const manager = await staffOf(accounts, 'MANAGER');
  const { page } = await openProfile(accounts, manager);
  const marker = unique('Journey').replace(' ', '-');
  const typed = {
    phone: '0112504417', whatsapp: '+94771234567', address: `No. 24, ${marker} Road, Colombo 07`, location: '6.9061, 79.8611',
    description: `A friendly salon ${marker}`, instagram: `https://instagram.com/${marker}`, facebook: '', website: `https://${marker.toLowerCase()}.example.com`,
  };
  await test.step('"What customers see" follows the typing before anything is saved', async () => {
    await field(page, 'address').fill(typed.address);
    await expect(id(page, 'profile-preview-address')).toContainText(typed.address);
    await field(page, 'description').fill(typed.description);
    await expect(id(page, 'profile-preview-description')).toContainText(typed.description);
    await expect(id(page, 'profile-description-count')).toContainText(String(typed.description.length));
  });
  for (const name of FIELDS) await field(page, name).fill(typed[name]);
  await id(page, 'profile-save').click();
  await expect(page.getByText(message('settings.profile.saved'))).toBeVisible();
  await expect(id(page, 'publish-bar-status')).toContainText(message('settings.publishBar.unpublished'));
  const publishedBefore = (await profiles.published(alpha)).profile;
  await test.step('the draft survives a reload, numbers shown in the national format, and is not served yet', async () => {
    await page.reload();
    await ready(page);
    await expect(field(page, 'address')).toHaveValue(typed.address);
    await expect(field(page, 'description')).toHaveValue(typed.description);
    await expect(field(page, 'whatsapp'), 'stored once in E.164, shown national').toHaveValue('077 123 4567');
    await expect(id(page, 'publish-bar-status')).toContainText(message('settings.publishBar.unpublished'));
    expect((await profiles.published(alpha)).profile, 'the public salon still serves the earlier profile').toEqual(publishedBefore);
  });
  await test.step('Publish to everyone saves the unsaved edit first, then publishes', async () => {
    const last = `${typed.description} and bridal`;
    await field(page, 'description').fill(last);
    await id(page, 'profile-publish').click();
    await expect(page.getByText(message('settings.profile.published'))).toBeVisible();
    await expect(id(page, 'publish-bar-status')).toContainText(message('settings.publishBar.published'));
    await expect(id(page, 'profile-publish'), 'nothing left to publish').toBeDisabled();
    const served = (await profiles.published(alpha)).profile!;
    const branch = served.branches.find(b => b.isDefault)!;
    expect(served.description).toBe(last);
    expect(branch.address).toBe(typed.address);
    expect(branch.phone).toBe(e164(typed.phone));
    expect(branch.whatsapp).toBe(typed.whatsapp);
    expect(branch.location).toEqual({ latitude: 6.9061, longitude: 79.8611 });
    expect(served.links).toEqual({ instagram: typed.instagram, facebook: null, website: typed.website });
  });
});

test('TC-F04-S01-020 AC-5: Discard changes asks first, keeps the edits when cancelled, and returns the form to the published profile when confirmed', async ({ accounts, profiles }) => {
  const manager = await staffOf(accounts, 'MANAGER');
  const { input } = await profiles.publishFresh(alpha, manager.tokens);
  const servedBefore = (await profiles.published(alpha)).profile;
  const { page } = await openProfile(accounts, manager);
  await expect(field(page, 'description')).toHaveValue(input.description!);
  const edited = unique('Edited description');
  await field(page, 'description').fill(edited);
  await id(page, 'profile-save').click();
  await expect(id(page, 'publish-bar-status')).toContainText(message('settings.publishBar.unpublished'));
  await id(page, 'profile-discard').click();
  await expect(id(page, 'profile-discard-dialog')).toBeVisible();
  await id(page, 'profile-discard-cancel').click();
  await expect(id(page, 'profile-discard-dialog')).toHaveCount(0);
  await expect(field(page, 'description'), 'cancelling keeps the edits').toHaveValue(edited);
  await id(page, 'profile-discard').click();
  await id(page, 'profile-discard-confirm').click();
  await expect(field(page, 'description'), 'back to the published profile').toHaveValue(input.description!);
  await expect(id(page, 'publish-bar-status')).toContainText(message('settings.publishBar.published'));
  expect((await profiles.published(alpha)).profile, 'the published profile is untouched').toEqual(servedBefore);
});

test('TC-F04-S01-021 AC-6: an invalid phone, WhatsApp, map position or link shows its message beside the field and saves nothing', async ({ accounts, profiles }) => {
  const manager = await staffOf(accounts, 'MANAGER');
  const { page } = await openProfile(accounts, manager);
  const rows: [(typeof FIELDS)[number], string, string][] = [
    ['phone', '12345', 'settings.profile.errors.phone'],
    ['whatsapp', '0112504417', 'settings.profile.errors.whatsapp'],
    ['location', '91, 79.86', 'settings.profile.errors.location'],
    ['website', 'http://example.com', 'settings.profile.errors.website'],
  ];
  for (const [name, value, key] of rows) {
    await test.step(`${name} ${value}`, async () => {
      await page.reload();
      await ready(page);
      const before = profileSnapshot(alpha.id);
      await field(page, name).fill(value);
      await id(page, 'profile-save').click();
      const error = id(page, `profile-${name}-error`);
      await expect(error).toBeVisible();
      await expect(error).toContainText(message(key));
      expect(await field(page, name).getAttribute('aria-describedby') ?? '', 'the message is tied to its field').toContain(`profile-${name}-error`);
      const fieldBox = (await field(page, name).boundingBox())!;
      const errorBox = (await error.boundingBox())!;
      expect(Math.abs(errorBox.y - (fieldBox.y + fieldBox.height)), 'shown beside (just below) the field').toBeLessThan(80);
      await expect(field(page, name), 'what was typed is kept').toHaveValue(value);
      expect(profileSnapshot(alpha.id), 'nothing saved').toBe(before);
    });
  }
});

test('TC-F04-S01-022 AC-7: when someone else saved first, the screen says so and Reload shows their changes', async ({ accounts, profiles }) => {
  const first = await staffOf(accounts, 'MANAGER');
  const second = await staffOf(accounts, 'MANAGER');
  const a = await openProfile(accounts, first);
  const b = await openProfile(accounts, second);
  const theirs = unique('Their description');
  await field(a.page, 'description').fill(theirs);
  await id(a.page, 'profile-save').click();
  await expect(a.page.getByText(message('settings.profile.saved'))).toBeVisible();
  const before = profileSnapshot(alpha.id);
  await field(b.page, 'description').fill(unique('My description'));
  await id(b.page, 'profile-save').click();
  await expect(id(b.page, 'profile-conflict')).toBeVisible();
  await expect(id(b.page, 'profile-conflict')).toContainText('Someone else changed the profile'); // AC-7: the screen says someone else changed the profile
  expect(profileSnapshot(alpha.id), 'the later save changed nothing').toBe(before);
  await id(b.page, 'profile-conflict-reload').click();
  await ready(b.page);
  await expect(field(b.page, 'description')).toHaveValue(theirs);
  await expect(id(b.page, 'profile-conflict')).toHaveCount(0);
  expect((await profiles.draft(alpha, first.tokens)).profile.description).toBe(theirs);
});

test('TC-F04-S01-023 AC-9: a support session sees the Profile tab and the draft read-only, with every control disabled', async ({ accounts, operator, profiles, browser }) => {
  const admin = await operator.actor();
  const plan = await operator.plan(admin);
  const { input, created, salon } = await operator.salon(admin, plan.id);
  const owner = await ownerSession(accounts.identity, salon, input);
  const branch = await profiles.defaultBranch(salon, owner.tokens);
  const draftInput = fullInput(branch);
  await profiles.save(salon, owner.tokens, (await profiles.draft(salon, owner.tokens)).version, draftInput);
  const support = await operator.actor('OPERATOR_SUPPORT');
  const session = await supportJson<SupportStart>(await operator.startSupport(support, created.id, { reason: 'QA F04 support reads the profile' }), 'supportStart', 201);
  const context = await browser.newContext({ viewport: { width: 1280, height: 1000 }, locale: 'en' });
  try {
    const entry = await context.newPage();
    await entry.goto(`${salonUrl(salon, webUrl)}/workspace/support#${session.entryCode}`);
    await expect(entry.getByTestId('support-banner')).toBeVisible();
    const page = await context.newPage();
    await page.goto(`${salonUrl(salon, webUrl)}${PROFILE}`);
    await ready(page);
    await expect(id(page, 'profile-read-only')).toBeVisible();
    await expect(field(page, 'description'), 'the draft is shown').toHaveValue(draftInput.description!);
    for (const name of FIELDS) await expect(field(page, name), `${name} disabled`).toBeDisabled();
    for (const control of ['profile-save', 'profile-publish', 'profile-discard']) await expect(id(page, control), `${control} disabled`).toBeDisabled();
  } finally {
    await context.close();
  }
});

test('TC-F04-S01-024 AC-11: leaving the Profile tab with unsaved edits, by a link or another Settings tab, asks first; staying keeps the edits and leaving goes', async ({ accounts }) => {
  const manager = await staffOf(accounts, 'MANAGER');
  const { context, page } = await openProfile(accounts, manager);
  const unsaved = unique('Unsaved address');
  await field(page, 'address').fill(unsaved);
  await id(page, 'workspace-account').click();
  await expect(id(page, 'profile-leave-dialog')).toBeVisible();
  await id(page, 'profile-leave-stay').click();
  await expect(page).toHaveURL(new RegExp(`${PROFILE}$`));
  await expect(field(page, 'address')).toHaveValue(unsaved);
  await id(page, 'workspace-account').click();
  await id(page, 'profile-leave-confirm').click();
  await expect(page).toHaveURL(/\/workspace\/account$/);
  await test.step('changing to another Settings tab with unsaved edits asks first', async () => {
    // After a client-side tab change the previous tab's markup stays, hidden: look among the visible elements.
    const shown = (p: Page, name: string) => p.getByTestId(name).filter({ visible: true });
    const tabbed = await accounts.open(context, PROFILE);
    await ready(tabbed);
    const draftBefore = await accounts.identity.api.send('GET', '/api/v1/salon/profile/draft', { headers: { Host: alpha.hostname, Authorization: `Bearer ${manager.tokens.accessToken}` } }).then(r => r.text());
    const typed = unique('Unsaved before a tab change');
    await field(tabbed, 'address').fill(typed);
    await shown(tabbed, 'settings-tab-hours').click();
    await expect(shown(tabbed, 'profile-leave-dialog')).toBeVisible();
    await shown(tabbed, 'profile-leave-stay').click();
    await expect(tabbed).toHaveURL(new RegExp(`${PROFILE}$`));
    await expect(shown(tabbed, 'settings-tab-profile')).toHaveAttribute('aria-selected', 'true');
    await expect(shown(tabbed, 'profile-address'), 'Stay keeps the edit').toHaveValue(typed);
    await shown(tabbed, 'settings-tab-hours').click();
    await shown(tabbed, 'profile-leave-confirm').click();
    await expect(tabbed).toHaveURL(/\/workspace\/settings\/hours$/);
    await expect(shown(tabbed, 'hours-tab')).toBeVisible();
    const draftAfter = await accounts.identity.api.send('GET', '/api/v1/salon/profile/draft', { headers: { Host: alpha.hostname, Authorization: `Bearer ${manager.tokens.accessToken}` } }).then(r => r.text());
    expect(draftAfter, 'leaving saved nothing').toBe(draftBefore);
    await tabbed.close();
  });
  await test.step('closing the tab with unsaved edits asks the browser to confirm', async () => {
    const again = await accounts.open(context, PROFILE);
    await ready(again);
    await field(again, 'address').fill(unique('Unsaved again'));
    const dialog = again.waitForEvent('dialog');
    await again.close({ runBeforeUnload: true });
    const shown = await dialog;
    expect(shown.type()).toBe('beforeunload');
    await shown.dismiss();
    await expect(field(again, 'address'), 'dismissing keeps the page and the edit').toBeVisible();
  });
});

test('TC-F04-S01-025 AC-11: the Profile tab shows its loading state, and an unreachable service is said plainly, keeps what was typed and recovers on retry', async ({ accounts }) => {
  const manager = await staffOf(accounts, 'MANAGER');
  const context = await accounts.context(alpha, [manager]);
  await test.step('loading, then the form', async () => {
    let release!: () => void;
    const held = new Promise<void>(resolve => { release = resolve; });
    await context.route('**/identity-web/staff/profile-draft', async route => { await held; await route.continue(); });
    const page = await accounts.open(context, PROFILE);
    await expect(id(page, 'profile-loading')).toBeVisible();
    release();
    await ready(page);
    await context.unroute('**/identity-web/staff/profile-draft');
    await page.close();
  });
  await test.step('unreachable on opening, then Try again', async () => {
    await context.route('**/identity-web/staff/profile-draft', route => route.abort('connectionfailed'));
    const page = await accounts.open(context, PROFILE);
    await expect(id(page, 'profile-unavailable')).toBeVisible();
    await context.unroute('**/identity-web/staff/profile-draft');
    await id(page, 'profile-retry').click();
    await ready(page);
    await page.close();
  });
  await test.step('unreachable on saving: nothing saved, the typing kept', async () => {
    const page = await accounts.open(context, PROFILE);
    await ready(page);
    const before = profileSnapshot(alpha.id);
    const typed = unique('Typed while offline');
    await field(page, 'description').fill(typed);
    await context.route('**/identity-web/staff/profile-save', route => route.abort('connectionfailed'));
    await id(page, 'profile-save').click();
    await expect(id(page, 'profile-unavailable')).toBeVisible();
    await expect(field(page, 'description')).toHaveValue(typed);
    expect(profileSnapshot(alpha.id)).toBe(before);
    await context.unroute('**/identity-web/staff/profile-save');
    await id(page, 'profile-save').click();
    await expect(page.getByText(message('settings.profile.saved'))).toBeVisible();
  });
});

test('TC-F04-S01-026 AC-11: at every width of the matrix the Profile tab and publish bar fit with no sideways scrolling and every control reachable', async ({ accounts }) => {
  const manager = await staffOf(accounts, 'MANAGER');
  const { page } = await openProfile(accounts, manager, 360);
  // A long description and address: the longest content the form allows.
  await field(page, 'address').fill('A'.repeat(200));
  await field(page, 'description').fill('Long description words '.repeat(21).slice(0, 500));
  for (const viewport of DESIGN_WIDTHS) {
    await test.step(`${viewport.width} wide`, async () => {
      await page.setViewportSize(viewport);
      const fits = await fit(page);
      expect(fits.counted, 'elements were measured').toBeGreaterThan(0);
      expect(fits.pageScrolls, 'no sideways scrolling').toBe(false);
      // The Settings tab rail scrolls sideways on phones, as the prototype's does (.brtabs): a tab past its edge is scrolled to, not outside the page; each tab is proved reachable below.
      expect(fits.outside.filter(c => !c.startsWith('settings-tab-')), 'nothing outside the viewport').toEqual([]);
      for (const control of ['settings-tab-profile', 'settings-tab-hours', 'settings-tab-rules', 'settings-tab-branding', ...FIELDS.map(f => `profile-${f}`), 'profile-check-map', 'profile-save', 'profile-publish', 'profile-discard', 'publish-bar', 'profile-preview']) {
        const element = id(page, control);
        await element.scrollIntoViewIfNeeded();
        await expect(element, `${control} at ${viewport.width}`).toBeVisible();
      }
      const form = (await id(page, 'profile-form').boundingBox())!;
      const preview = (await id(page, 'profile-preview').boundingBox())!;
      if (viewport.width < 900) expect(preview.y, 'the preview is below the form on narrow screens').toBeGreaterThanOrEqual(form.y + form.height - 1);
      else expect(preview.x, 'the preview is beside the form on wide screens').toBeGreaterThanOrEqual(form.x + form.width - 1);
    });
  }
  await test.step('the field-error state at the narrowest width', async () => {
    await page.setViewportSize(DESIGN_WIDTHS[0]);
    await field(page, 'website').fill(`http://${'long-address-'.repeat(12)}example.com`);
    await id(page, 'profile-save').click();
    await expect(id(page, 'profile-website-error')).toBeVisible();
    await expect(id(page, 'profile-fix')).toBeVisible();
    const fits = await fit(page);
    expect(fits.pageScrolls, 'no sideways scrolling with errors shown').toBe(false);
    expect(fits.outside.filter(c => !c.startsWith('settings-tab-'))).toEqual([]);
  });
});

test('TC-F04-S01-027 AC-11: the Profile tab has no serious accessibility violation in its loaded, field-error and conflict states and can be saved by keyboard alone', async ({ accounts }) => {
  const manager = await staffOf(accounts, 'MANAGER');
  const { page } = await openProfile(accounts, manager);
  await test.step('loaded', async () => expect(await seriousViolations(page)).toEqual([]));
  await test.step('keyboard alone: reach the address, type, reach Save draft and press it', async () => {
    await page.reload();
    await ready(page);
    await page.locator('body').click({ position: { x: 1, y: 1 } });
    let steps = 0;
    while (!(await field(page, 'address').evaluate(el => el === document.activeElement)) && steps++ < 60) await page.keyboard.press('Tab');
    await expect(field(page, 'address'), 'the address is reached by Tab').toBeFocused();
    const typed = unique('Keyboard address');
    await page.keyboard.press('ControlOrMeta+A');
    await page.keyboard.type(typed);
    steps = 0;
    while (!(await id(page, 'profile-save').evaluate(el => el === document.activeElement)) && steps++ < 60) await page.keyboard.press('Tab');
    const focus = await focusIndicator(id(page, 'profile-save'));
    expect(focus.focused, 'Save draft is reached by Tab').toBe(true);
    expect(focus.focusVisible && focus.outlineStyle !== 'none' && focus.outlineWidth > 0, 'the focus is visible').toBe(true);
    await page.keyboard.press('Enter');
    await expect(page.getByText(message('settings.profile.saved'))).toBeVisible();
  });
  await test.step('field error', async () => {
    await field(page, 'phone').fill('12345');
    await id(page, 'profile-save').click();
    await expect(id(page, 'profile-phone-error')).toBeVisible();
    expect(await seriousViolations(page)).toEqual([]);
  });
  await test.step('conflict', async () => {
    await page.reload();
    await ready(page);
    const other = await accounts.identity.staff(alpha, 'MANAGER');
    const profiles = new Profiles(accounts.identity);
    const current = await profiles.draft(alpha, other.tokens);
    await profiles.save(alpha, other.tokens, current.version, fullInput(current.profile.branches[0].id));
    await field(page, 'description').fill(unique('Stale edit'));
    await id(page, 'profile-save').click();
    await expect(id(page, 'profile-conflict')).toBeVisible();
    expect(await seriousViolations(page)).toEqual([]);
  });
});
