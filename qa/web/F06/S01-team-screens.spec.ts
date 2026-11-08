/**
 * F06-S01 the Team page in the browser: the Staff navigation entry and the two tabs, the person
 * cards and their header, the Empty state, the profile sheet with its live count and messages, the
 * service picker, the Sign-in select, the seat message and the Billing page's seat usage, removal
 * with its confirmation, the photo control and the initial on a colour, who sees which controls, a
 * support session, the loading, unavailable, conflict and refused states, the leave guard, every
 * width of the matrix, keyboard use and accessibility.
 *
 * Expected results are the F06-S01 spec's (acceptance criteria, "UI and UX") and the prototype
 * (Salon web, Staff, the F06 block: `aStaff`, `tmTabs`, `acctTab`, `stOpen`, `stErr`, `stCatRow`,
 * `stPaint`, `stSave`, `stDel`, `stDelGo`), whose messages are written here from the spec and the
 * prototype rather than read from the product's translation file. Rules the API cases prove (every
 * boundary, every role on every endpoint) are not proved again: each case checks what the person sees,
 * and persistence once through the API.
 *
 * The test ids in `T` are the build's, confirmed against the F06-S01 handoff at the dry run (c9b2d57);
 * nothing else here spells one. `cacheComponents` keeps a route it has left in the document, hidden, so
 * a check that follows a tab move targets the visible element.
 */
import type { BrowserContext, Locator, Page } from '@playwright/test';

import { Catalogue, serviceInput } from '../../shared/catalogue';
import { DESIGN_WIDTHS } from '../../shared/design-reference';
import { webUrl } from '../../shared/env';
import { test as browserTest, expect, routes, type AccountBrowser } from '../../shared/identity-browser';
import type { Actor, Role, SignedStaff } from '../../shared/identity';
import { Operator, supportJson, type SupportStart } from '../../shared/operator';
import { fit, focusIndicator, seriousViolations } from '../../shared/page-checks';
import { fixture } from '../../shared/salon-brand';
import { PENDING_F06_S01, Team, memberInput, teamSalon, type TeamMember } from '../../shared/team';
import { salonUrl, type Salon } from '../../shared/tenants';

const test = browserTest.extend<{ operator: Operator; team: Team; cat: Catalogue }>({
  operator: async ({ identity }, use) => {
    const operator = new Operator(identity);
    await operator.resetFixtures();
    try { await use(operator); } finally { await operator.resetToBaseline(); }
  },
  team: async ({ identity }, use) => { const team = new Team(identity); try { await use(team); } finally { await team.dispose(); } },
  cat: async ({ identity }, use) => { const cat = new Catalogue(identity); try { await use(cat); } finally { await cat.dispose(); } },
});
test.describe.configure({ timeout: 180_000 });

const TEAM_ROUTE = routes.team;
const ACCOUNTS_ROUTE = routes.staffList;

/** Test ids, from the F06-S01 handoff and the build. A repeated row carries `data-id`. */
const T = {
  nav: 'workspace-team', tabTeam: 'team-tab-team', tabAccounts: 'team-tab-accounts',
  loading: 'team-loading', unavailable: 'team-unavailable', retry: 'team-retry', refused: 'team-refused', readOnly: 'team-read-only',
  summary: 'team-summary', newMember: 'team-add', empty: 'team-empty', emptyNew: 'team-empty-add', rule: 'team-rule',
  card: 'team-card', cardName: 'team-card-name', cardTitle: 'team-card-title', cardBio: 'team-card-bio', cardServices: 'team-card-services',
  cardOnline: 'team-card-online', cardSignIn: 'team-card-sign-in', cardEdit: 'team-card-edit', cardAvatar: 'team-member-avatar', cardInitial: 'team-member-initial',
  sheet: 'team-sheet', name: 'team-name', title: 'team-title', highlights: 'team-highlights', bio: 'team-bio', bioCount: 'team-bio-count',
  online: 'team-online', signIn: 'team-sign-in', save: 'team-sheet-save', remove: 'team-sheet-remove', close: 'team-sheet-close', problem: 'team-sheet-problem', problems: 'team-sheet-problems',
  category: 'team-picker-row', categoryToggle: 'team-picker-category', categorySummary: 'team-picker-summary', categoryChoose: 'team-picker-choose', serviceChip: 'team-picker-service',
  photo: 'team-photo-file', photoChoose: 'team-photo-choose', photoPreview: 'team-member-photo', photoRemove: 'team-photo-remove', photoError: 'team-photo-error', photoAfterCreate: 'team-photo-after-add',
  conflict: 'team-conflict', conflictReload: 'team-conflict-reload',
  leaveDialog: 'team-leave-dialog', leaveStay: 'team-leave-stay', leaveConfirm: 'team-leave-confirm',
  removeDialog: 'team-remove-dialog', removeConfirm: 'team-remove-confirm', removeCancel: 'team-remove-cancel',
  staffRow: 'account-staff-row', staffProfile: 'account-staff-team-profile', billingSeats: 'billing-staff-seats',
} as const;

/** The spec's and the prototype's words. */
const TEXT = {
  entry: 'Staff',
  emptyTitle: 'Add your first team member',
  addButton: '+ Add team member',
  summary: (n: number, m: number) => `${n} ${n === 1 ? 'person' : 'people'} · ${m} shown for online booking`,
  added: 'Added to the team.', saved: 'Profile saved.', removed: 'Removed from the team.',
  noServices: 'None yet', noSignIn: 'None', shown: 'Shown', hidden: 'Hidden',
  noProfile: 'No team profile',
  notPerformed: 'Not performed',
  allOf: (n: number) => (n === 1 ? '1 service, and any added later' : `All ${n} services, and any added later`),
  someOf: (m: number, n: number) => `${m} of ${n} services`,
  noSignInOption: 'No sign-in. They can still take bookings',
  photoAfterCreate: 'Add a photo after you add this person.',
  removeTitle: (name: string) => `Remove ${name}?`,
  removeBody: 'They will no longer be available for booking. Past bookings keep their name. Their sign-in account, if any, stays until you remove it.',
  // The message F02's staff screen already shows (spec, AC-6: "the existing message").
  noSeat: 'The salon\'s plan has no free staff seat. Remove a staff member or ask the platform to move the salon to a larger plan.',
  memberLimit: 'A salon can have up to 200 team members. Remove someone first.',
  err: {
    name: 'Name must be 2 to 40 characters.', title: 'Title must be 40 characters or fewer.',
    highlights: 'Highlights must be 60 characters or fewer.', bio: 'Bio must be 140 characters or fewer.',
  },
};

/** The prototype's six avatar colours (`stOpen`), as the browser reports them. */
const AVATAR_COLOURS = ['#3E5C8A', '#8A4B6B', '#3E6B7A', '#6B6B3E', '#2F6B5E', '#7A4A00'].map(hex => {
  const [r, g, b] = [1, 3, 5].map(i => parseInt(hex.slice(i, i + 2), 16));
  return `rgb(${r}, ${g}, ${b})`;
});

const id = (page: Page | Locator, name: string) => page.getByTestId(name).filter({ visible: true });
const card = (page: Page, memberId: string) => page.locator(`[data-testid="${T.card}"][data-id="${memberId}"]`).filter({ visible: true });
const categoryRow = (page: Page, categoryId: string) => page.locator(`[data-testid="${T.category}"][data-id="${categoryId}"]`).filter({ visible: true });

/** Loading has finished and the Team tab shows its header or its Empty state. */
async function ready(page: Page) {
  await expect(id(page, T.loading)).toHaveCount(0);
  await expect(id(page, T.summary).or(id(page, T.empty)).first()).toBeVisible();
}

async function open(accounts: AccountBrowser, actor: Actor, width = 1280): Promise<{ context: BrowserContext; page: Page }> {
  const context = await accounts.context(actor.salon, [actor], { width });
  const page = await accounts.open(context, TEAM_ROUTE);
  await ready(page);
  return { context, page };
}

async function settled(page: Page) {
  await page.waitForFunction(() => document.getAnimations().every(a => a.playState !== 'running'));
}

async function accessible(page: Page, label: string) {
  await settled(page);
  expect.soft(await seriousViolations(page), `${label}: no serious or critical violation`).toEqual([]);
}

/** Tabs forward until the element has the focus, failing after `limit` presses. */
async function tabTo(page: Page, target: Locator, limit = 120) {
  let steps = 0;
  while (!(await target.evaluate(el => el === document.activeElement)) && steps++ < limit) await page.keyboard.press('Tab');
  await expect(target, 'reached by Tab').toBeFocused();
}

/** Chooses an option of a shadcn Select by the trigger's test id and the option's words (web.md: never `selectOption`). */
async function choose(page: Page, trigger: string, words: string) {
  await id(page, trigger).click();
  await page.getByRole('option', { name: words }).click();
  await expect(page.getByRole('listbox')).toHaveCount(0);
}

/** A small menu: Colour with three services, Nails with one, and the other eight categories empty. */
async function arrangeMenu(cat: Catalogue, salon: Salon, tokens: Actor['tokens']) {
  const colour = await cat.category(salon, tokens, 'Colour');
  const nails = await cat.category(salon, tokens, 'Nails');
  const roots = await cat.create(salon, tokens, serviceInput(colour.id, { name: 'Root touch-up' }));
  const balayage = await cat.create(salon, tokens, serviceInput(colour.id, { name: 'Balayage' }));
  const gloss = await cat.create(salon, tokens, serviceInput(colour.id, { name: 'Gloss' }));
  const gel = await cat.create(salon, tokens, serviceInput(nails.id, { name: 'Gel polish' }));
  return { colour, nails, roots, balayage, gloss, gel };
}

/** A support session's browser, on the salon's own web address. */
async function supportBrowser(browser: import('@playwright/test').Browser, operator: Operator, created: { id: string }, salon: Salon) {
  const support = await operator.actor('OPERATOR_SUPPORT');
  const session = await supportJson<SupportStart>(await operator.startSupport(support, created.id, { reason: 'The salon asked why a stylist is not shown online' }), 'supportStart', 201);
  const context = await browser.newContext({ baseURL: salonUrl(salon, webUrl), viewport: { width: 1280, height: 1000 }, locale: 'en' });
  const entry = await context.newPage();
  await entry.goto(`${salonUrl(salon, webUrl)}/workspace/support#${session.entryCode}`);
  await expect(entry.getByTestId('support-banner')).toBeVisible();
  return context;
}

// ---------------------------------------------------------------- AC-11 the entry and the tabs

test('TC-F06-S01-039 AC-11, AC-13: the Staff entry shows for every role with team.read and opens the Team tab; with staff.list a Sign-in accounts tab shows, without it no tab bar and /workspace/staff answers as today; an Accountant has no entry and is refused the page', async ({ accounts, operator }) => {
  const { salon, owner } = await teamSalon(operator, accounts.identity);
  const actors: [Role, Actor][] = [['OWNER', owner]];
  for (const role of ['MANAGER', 'RECEPTIONIST', 'PROFESSIONAL'] as Role[]) actors.push([role, await accounts.identity.staff(salon, role)]);
  for (const [role, actor] of actors) {
    await test.step(role, async () => {
      const context = await accounts.context(salon, [actor]);
      const page = await accounts.open(context, routes.staff);
      await accounts.ready(page);
      await expect(id(page, T.nav)).toHaveText(TEXT.entry);
      await id(page, T.nav).click();
      await expect(page).toHaveURL(new RegExp(`${TEAM_ROUTE}$`));
      await ready(page);
      await expect(id(page, T.nav), 'the entry is current').toHaveAttribute('aria-current', 'page');
      await expect(page.locator(`a[href="${ACCOUNTS_ROUTE}"][data-testid^="workspace-"]`), 'no navigation entry goes to the accounts screen').toHaveCount(0);
      if (role === 'OWNER' || role === 'MANAGER') {
        await expect(id(page, T.tabTeam)).toBeVisible();
        await expect(id(page, T.tabAccounts)).toBeVisible();
      } else {
        await expect(page.getByTestId(T.tabTeam), 'no tab bar').toHaveCount(0);
        await expect(page.getByTestId(T.tabAccounts), 'no tab bar').toHaveCount(0);
        const denied = await accounts.open(context, ACCOUNTS_ROUTE);
        await expect(denied.getByTestId('account-denied'), '/workspace/staff answers as it does today').toBeVisible();
        await expect(denied.getByTestId(T.staffRow)).toHaveCount(0);
      }
      await context.close();
    });
  }
  await test.step('ACCOUNTANT', async () => {
    const actor = await accounts.identity.staff(salon, 'ACCOUNTANT');
    const context = await accounts.context(salon, [actor]);
    const account = await accounts.open(context, routes.staff);
    await accounts.ready(account);
    await expect(account.getByTestId(T.nav), 'no navigation entry').toHaveCount(0);
    const page = await accounts.open(context, TEAM_ROUTE);
    await expect(page.getByRole('alert').first(), 'the page says it is refused').toBeVisible();
    await expect(page.getByTestId(T.card)).toHaveCount(0);
    await expect(page.getByTestId(T.newMember)).toHaveCount(0);
    await context.close();
  });
});

test('TC-F06-S01-040 AC-11: Sign-in accounts is the existing staff screen under the tabs, with its form and rows, and each account names its linked team member or says "No team profile"', async ({ accounts, operator, team }) => {
  const { salon, owner } = await teamSalon(operator, accounts.identity);
  const linkedAccount = await team.createAccount(salon, owner.tokens);
  const person = await team.add(salon, owner.tokens, memberInput({ name: 'Linked Person', signInAccountId: linkedAccount.id }));
  const context = await accounts.context(salon, [owner]);
  const page = await accounts.open(context, TEAM_ROUTE);
  await ready(page);
  await id(page, T.tabAccounts).click();
  await expect(page).toHaveURL(new RegExp(`${ACCOUNTS_ROUTE}$`));
  await accounts.ready(page);
  await test.step('the existing screen', async () => {
    await expect(page.getByTestId('account-staff-create'), 'its Add staff form').toBeVisible();
    await expect(page.getByTestId(T.staffRow), 'its rows').toHaveCount(2);
  });
  await test.step('the profile each account is linked to', async () => {
    const row = (accountId: string) => page.locator(`[data-testid="${T.staffRow}"][data-id="${accountId}"]`);
    await expect(row(linkedAccount.id).getByTestId(T.staffProfile)).toHaveText(person.name);
    await expect(row(owner.principal.id).getByTestId(T.staffProfile)).toHaveText(TEXT.noProfile);
  });
  await test.step('the tab goes back, and the page loads directly on either', async () => {
    await id(page, T.tabTeam).click();
    await expect(page).toHaveURL(new RegExp(`${TEAM_ROUTE}$`));
    await ready(page);
    await expect(card(page, person.id)).toBeVisible();
    await page.goto(ACCOUNTS_ROUTE);
    await accounts.ready(page);
    await expect(id(page, T.tabAccounts)).toBeVisible();
  });
});

// ---------------------------------------------------------------- AC-12 the Team tab

test('TC-F06-S01-041 AC-12: the Team tab has a card per person in name order with name, title, bio, the categories they perform or "None yet", Shown or Hidden, and the account\'s label or "None"; the header counts people and those shown', async ({ accounts, operator, team, cat }) => {
  const { salon, owner } = await teamSalon(operator, accounts.identity);
  const { colour, nails, gel } = await arrangeMenu(cat, salon, owner.tokens);
  const account = await team.createAccount(salon, owner.tokens);
  const chamara = await team.add(salon, owner.tokens, memberInput({ name: 'Chamara', title: 'Director', bio: 'Colour for twenty years.', shownOnline: false, services: { categoryIds: [colour.id], serviceIds: [gel.id] }, signInAccountId: account.id }));
  const amal = await team.add(salon, owner.tokens, memberInput({ name: 'amal', title: 'Stylist', bio: null, services: { categoryIds: [], serviceIds: [] } }));
  const bimal = await team.add(salon, owner.tokens, memberInput({ name: 'Bimal', title: null, bio: null, services: { categoryIds: [nails.id], serviceIds: [] } }));
  const { page } = await open(accounts, owner);
  await expect(id(page, T.summary)).toHaveText(TEXT.summary(3, 2));
  await expect(page.getByTestId(T.cardName), 'name order, ignoring case').toHaveText(['amal', 'Bimal', 'Chamara']);
  const expectations: [TeamMember, string, string][] = [
    [amal, TEXT.noServices, TEXT.shown],
    [bimal, 'Nails', TEXT.shown],
  ];
  for (const [member, services, online] of expectations) {
    await expect(card(page, member.id).getByTestId(T.cardServices), `${member.name}: services`).toContainText(services);
    await expect(card(page, member.id).getByTestId(T.cardOnline), `${member.name}: badge`).toHaveText(online);
    await expect(card(page, member.id).getByTestId(T.cardSignIn), `${member.name}: sign-in`).toContainText(TEXT.noSignIn);
  }
  const c = card(page, chamara.id);
  await expect(c.getByTestId(T.cardTitle)).toHaveText('Director');
  await expect(c.getByTestId(T.cardBio)).toHaveText('Colour for twenty years.');
  await expect(c.getByTestId(T.cardServices), 'the category performed whole').toContainText('Colour');
  await expect(c.getByTestId(T.cardServices), 'and the category of a single service').toContainText('Nails');
  await expect(c.getByTestId(T.cardOnline)).toHaveText(TEXT.hidden);
  await expect(c.getByTestId(T.cardSignIn), 'the account\'s label').toContainText(account.email);
  await expect(c.getByTestId(T.cardEdit)).toBeVisible();
});

test('TC-F06-S01-042 AC-13: the Receptionist and Professional read the cards with no sign-in row and no control to change anything; a support session reads them with the sign-in row and no such control', async ({ accounts, operator, team, browser }) => {
  const { salon, owner, created } = await teamSalon(operator, accounts.identity);
  const account = await team.createAccount(salon, owner.tokens);
  const person = await team.add(salon, owner.tokens, memberInput({ name: 'Seen By All', signInAccountId: account.id }));
  const noChange = async (page: Page, label: string) => {
    await expect(card(page, person.id), `${label}: the card`).toBeVisible();
    for (const control of [T.newMember, T.emptyNew, T.cardEdit, T.sheet]) await expect(page.getByTestId(control), `${label}: no ${control}`).toHaveCount(0);
  };
  for (const role of ['RECEPTIONIST', 'PROFESSIONAL'] as Role[]) {
    await test.step(role, async () => {
      const actor = await accounts.identity.staff(salon, role);
      const { page, context } = await open(accounts, actor);
      await noChange(page, role);
      await expect(page.getByTestId(T.cardSignIn), `${role}: no sign-in row`).toHaveCount(0);
      expect(await page.content(), `${role}: the label reaches no part of the page`).not.toContain(account.email);
      await context.close();
    });
  }
  await test.step('a support session', async () => {
    const context = await supportBrowser(browser, operator, created, salon);
    const page = await context.newPage();
    await page.goto(`${salonUrl(salon, webUrl)}${TEAM_ROUTE}`);
    await ready(page);
    await noChange(page, 'support');
    await expect(id(page, T.readOnly), 'says it cannot change the team').toBeVisible();
    await expect(card(page, person.id).getByTestId(T.cardSignIn), 'support has staff.list').toContainText(account.email);
    await context.close();
  });
});

test('TC-F06-S01-043 AC-12: with no people the Team tab shows the Empty state, with + Add team member only for who may use it', async ({ accounts, operator }) => {
  const { salon, owner } = await teamSalon(operator, accounts.identity);
  const { page } = await open(accounts, owner);
  await expect(id(page, T.empty)).toContainText(TEXT.emptyTitle);
  await expect(id(page, T.emptyNew)).toContainText(TEXT.addButton);
  await expect(page.getByTestId(T.card)).toHaveCount(0);
  const receptionist = await accounts.identity.staff(salon, 'RECEPTIONIST');
  const other = await open(accounts, receptionist);
  await expect(id(other.page, T.empty)).toContainText(TEXT.emptyTitle);
  await expect(other.page.getByTestId(T.emptyNew), 'no button for a Receptionist').toHaveCount(0);
  await expect(other.page.getByTestId(T.newMember)).toHaveCount(0);
});

// ---------------------------------------------------------------- AC-1, AC-2 the sheet

test('TC-F06-S01-044 AC-1: + Add team member opens the sheet in the prototype\'s order with shown online on and no sign-in; a broken name shows its message and disables Add to team while it lasts; the bio counts live; success says "Added to the team." and the person is saved', async ({ accounts, operator, team }) => {
  const { salon, owner } = await teamSalon(operator, accounts.identity);
  const { page } = await open(accounts, owner);
  await id(page, T.emptyNew).click();
  await expect(id(page, T.sheet)).toBeVisible();
  await test.step('the fields in the prototype\'s order', async () => {
    // A new person has the line about adding a photo in the photo control's place.
    const order = [T.photoAfterCreate, T.name, T.title, T.highlights, T.bio, T.online, T.signIn];
    const tops: number[] = [];
    for (const name of order) tops.push((await id(page, name).boundingBox())!.y);
    expect([...tops].sort((a, b) => a - b), 'each below the one before').toEqual(tops);
    await expect(id(page, T.photoAfterCreate), 'a new person\'s photo comes later').toHaveText(TEXT.photoAfterCreate);
    await expect(id(page, T.online), 'on by default').toBeChecked();
    await expect(id(page, T.signIn), 'none by default').toContainText(TEXT.noSignInOption);
    await expect(page.getByTestId(T.remove), 'nothing to remove yet').toHaveCount(0);
  });
  await test.step('a broken name', async () => {
    await id(page, T.name).fill('A');
    await expect(id(page, T.problem).filter({ hasText: TEXT.err.name })).toBeVisible();
    await expect(id(page, T.save)).toBeDisabled();
    await expect(id(page, T.save)).toHaveText('Add to team');
    await id(page, T.name).fill('  Nimal Perera  ');
    await expect(page.getByTestId(T.problem)).toHaveCount(0);
    await expect(id(page, T.save)).toBeEnabled();
  });
  await test.step('each other limit says what it is, and disables Add to team while it lasts', async () => {
    const rows: [string, number, string][] = [[T.title, 41, TEXT.err.title], [T.highlights, 61, TEXT.err.highlights], [T.bio, 141, TEXT.err.bio]];
    for (const [field, length, message] of rows) {
      await id(page, field).fill('x'.repeat(length));
      await expect(id(page, T.problem).filter({ hasText: message }), message).toBeVisible();
      await expect(id(page, T.save)).toBeDisabled();
      if (field === T.bio) await expect(id(page, T.bioCount), 'the count passes the limit').toHaveText('141/140');
      await id(page, field).fill('x'.repeat(length - 1));
      await expect(id(page, T.problem).filter({ hasText: message })).toHaveCount(0);
      await expect(id(page, T.save)).toBeEnabled();
    }
  });
  await test.step('the live count', async () => {
    await id(page, T.bio).fill('x'.repeat(65));
    await expect(id(page, T.bioCount)).toHaveText('65/140');
  });
  await id(page, T.title).fill('Colour Specialist');
  await id(page, T.highlights).fill('Balayage • Highlights');
  await id(page, T.save).click();
  await expect(page.getByText(TEXT.added)).toBeVisible();
  await expect(id(page, T.sheet)).toHaveCount(0);
  const [saved] = await team.list(salon, owner.tokens);
  expect(saved).toMatchObject({ name: 'Nimal Perera', title: 'Colour Specialist', highlights: 'Balayage • Highlights', bio: 'x'.repeat(65), shownOnline: true });
  await expect(card(page, saved.id).getByTestId(T.cardName), 'its card').toHaveText('Nimal Perera');
  await expect(id(page, T.summary), 'the header in the singular').toHaveText(TEXT.summary(1, 1));
});

test('TC-F06-S01-055 AC-1: at 200 people the sheet says "A salon can have up to 200 team members. Remove someone first.", keeps the person\'s details open, and adds nobody', async ({ accounts, operator, team }) => {
  test.setTimeout(240_000);
  const { salon, owner } = await teamSalon(operator, accounts.identity, 250);
  for (let n = 1; n <= 200; n++) await team.add(salon, owner.tokens, { name: `Person ${String(n).padStart(3, '0')}` });
  const { page } = await open(accounts, owner);
  await id(page, T.newMember).click();
  await id(page, T.name).fill('Number 201');
  await id(page, T.save).click();
  // By role: a message behind the sheet's overlay is hidden from the person and must not count.
  await expect(page.getByRole('alert').filter({ hasText: TEXT.memberLimit }), 'the person is told').toBeVisible();
  await expect(id(page, T.name), 'what they typed is kept').toHaveValue('Number 201');
  expect((await team.list(salon, owner.tokens)).length, 'nobody added').toBe(200);
});

test('TC-F06-S01-045 AC-2, AC-16: Edit profile opens the person in the same sheet and saves with "Profile saved."; a save someone else overtook says so, keeps their change and offers Reload', async ({ accounts, operator, team }) => {
  const { salon, owner } = await teamSalon(operator, accounts.identity);
  const person = await team.add(salon, owner.tokens, memberInput({ name: 'Sanduni', title: 'Stylist' }));
  const { page } = await open(accounts, owner);
  await card(page, person.id).getByTestId(T.cardEdit).click();
  await expect(id(page, T.name)).toHaveValue('Sanduni');
  await expect(id(page, T.save)).toHaveText('Save changes');
  await expect(id(page, T.remove)).toHaveText('Remove from team');
  await id(page, T.title).fill('Senior Stylist');
  await id(page, T.save).click();
  await expect(page.getByText(TEXT.saved)).toBeVisible();
  await expect(card(page, person.id).getByTestId(T.cardTitle)).toHaveText('Senior Stylist');
  const edited = await team.get(salon, owner.tokens, person.id);
  expect(edited.title, 'saved').toBe('Senior Stylist');
  await test.step('a save someone else overtook', async () => {
    await card(page, person.id).getByTestId(T.cardEdit).click();
    await team.save(salon, owner.tokens, edited, { title: 'Set By Someone Else' });
    await id(page, T.title).fill('Set Here');
    await id(page, T.save).click();
    await expect(id(page, T.conflict), 'the person is told someone else changed it').toBeVisible();
    expect((await team.get(salon, owner.tokens, person.id)).title, 'their change stands').toBe('Set By Someone Else');
    await id(page, T.conflictReload).click();
    await expect(id(page, T.title), 'Reload shows what is saved').toHaveValue('Set By Someone Else');
  });
});

// ---------------------------------------------------------------- AC-3 the picker

test('TC-F06-S01-046 AC-3: the picker lists the categories with services in the salon\'s order; unticked reads "Not performed", ticked "All 3 services, and any added later", and Choose narrows to "2 of 3 services"; it saves as whole categories and single services and reopens the same', async ({ accounts, operator, team, cat }) => {
  const { salon, owner } = await teamSalon(operator, accounts.identity);
  const { colour, nails, roots, balayage, gel } = await arrangeMenu(cat, salon, owner.tokens);
  await cat.move(salon, owner.tokens, nails.id, 'up');
  await cat.move(salon, owner.tokens, nails.id, 'up');
  const person = await team.add(salon, owner.tokens, memberInput({ name: 'Picking Services' }));
  const { page } = await open(accounts, owner);
  const editPerson = async () => { await card(page, person.id).getByTestId(T.cardEdit).click(); await expect(id(page, T.sheet)).toBeVisible(); };
  await editPerson();
  const colourRow = categoryRow(page, colour.id);
  const nailsRow = categoryRow(page, nails.id);
  await test.step('only the categories with services, in the salon\'s order', async () => {
    await expect(page.getByTestId(T.category), 'Nails was moved above Colour').toHaveCount(2);
    await expect(page.getByTestId(T.category).nth(0)).toHaveAttribute('data-id', nails.id);
    await expect(page.getByTestId(T.category).nth(1)).toHaveAttribute('data-id', colour.id);
    await expect(colourRow.getByTestId(T.categorySummary)).toHaveText(TEXT.notPerformed);
    await expect(colourRow.getByTestId(T.categoryChoose), 'nothing to narrow until ticked').toHaveCount(0);
  });
  await test.step('tick Nails whole; narrow Colour to two of three', async () => {
    await nailsRow.getByTestId(T.categoryToggle).click();
    await expect(nailsRow.getByTestId(T.categorySummary), 'the singular').toHaveText(TEXT.allOf(1));
    await colourRow.getByTestId(T.categoryToggle).click();
    await expect(colourRow.getByTestId(T.categorySummary)).toHaveText(TEXT.allOf(3));
    await colourRow.getByTestId(T.categoryChoose).click();
    await expect(colourRow.getByTestId(T.serviceChip)).toHaveCount(3);
    await colourRow.locator(`[data-testid="${T.serviceChip}"][data-id="${(await cat.services(salon, owner.tokens)).find(s => s.name === 'Gloss')!.id}"]`).click();
    await expect(colourRow.getByTestId(T.categorySummary)).toHaveText(TEXT.someOf(2, 3));
  });
  await id(page, T.save).click();
  await expect(page.getByText(TEXT.saved)).toBeVisible();
  await test.step('saved as a whole category and two single services', async () => {
    const saved = await team.get(salon, owner.tokens, person.id);
    expect(saved.services.categoryIds, 'Nails whole').toEqual([nails.id]);
    expect([...saved.services.serviceIds].sort(), 'Colour as single services').toEqual([roots.id, balayage.id].sort());
    expect((await team.publicList(salon)).find(m => m.id === person.id)?.serviceIds.sort(), 'and what they perform').toEqual([roots.id, balayage.id, gel.id].sort());
  });
  await test.step('reopened: the same choices', async () => {
    await editPerson();
    await expect(categoryRow(page, colour.id).getByTestId(T.categorySummary)).toHaveText(TEXT.someOf(2, 3));
    await expect(categoryRow(page, nails.id).getByTestId(T.categorySummary)).toContainText('any added later');
  });
});

// ---------------------------------------------------------------- AC-5 the Sign-in select

test('TC-F06-S01-047 AC-5: Sign-in offers "No sign-in" and each active account not linked to someone else, by its label; a choice is saved, and is shown on the card', async ({ accounts, operator, team }) => {
  const { salon, owner } = await teamSalon(operator, accounts.identity);
  const free = await team.createAccount(salon, owner.tokens);
  const taken = await team.createAccount(salon, owner.tokens);
  const removed = await team.createAccount(salon, owner.tokens);
  await team.removeAccount(salon, owner.tokens, removed.id);
  await team.add(salon, owner.tokens, memberInput({ name: 'Has An Account', signInAccountId: taken.id }));
  const person = await team.add(salon, owner.tokens, memberInput({ name: 'Choosing An Account' }));
  const { page } = await open(accounts, owner);
  await card(page, person.id).getByTestId(T.cardEdit).click();
  await id(page, T.signIn).click();
  const options = page.getByRole('option');
  await expect(options.filter({ hasText: TEXT.noSignInOption }), 'no sign-in').toHaveCount(1);
  await expect(options.filter({ hasText: free.email }), 'a free account').toHaveCount(1);
  await expect(options.filter({ hasText: owner.account.email.toLowerCase() }), 'the Owner\'s account').toHaveCount(1);
  await expect(options.filter({ hasText: taken.email }), 'not one linked to someone else').toHaveCount(0);
  await expect(options.filter({ hasText: removed.email }), 'not a removed one').toHaveCount(0);
  await page.keyboard.press('Escape');
  await choose(page, T.signIn, free.email);
  await id(page, T.save).click();
  await expect(page.getByText(TEXT.saved)).toBeVisible();
  expect((await team.get(salon, owner.tokens, person.id)).signInAccountId, 'saved').toBe(free.id);
  await expect(card(page, person.id).getByTestId(T.cardSignIn)).toContainText(free.email);
  await test.step('removing the account: the card says None', async () => {
    await team.removeAccount(salon, owner.tokens, free.id);
    await page.reload();
    await ready(page);
    await expect(card(page, person.id).getByTestId(T.cardSignIn)).toContainText(TEXT.noSignIn);
  });
});

// ---------------------------------------------------------------- AC-6 seats on screen

test('TC-F06-S01-048 AC-6: with no free seat the sheet shows the existing message and adds nobody, a Save that takes no seat still works, and the Billing page\'s seat usage counts people with no sign-in', async ({ accounts, operator, team }) => {
  const { salon, owner, plan } = await teamSalon(operator, accounts.identity, 3);
  const a = await team.add(salon, owner.tokens, memberInput({ name: 'First Person' }));
  await team.add(salon, owner.tokens, memberInput({ name: 'Second Person' }));
  await team.expectSeats(owner as SignedStaff, 3, 'full');
  const { page, context } = await open(accounts, owner);
  await id(page, T.newMember).click();
  await id(page, T.name).fill('Third Person');
  await id(page, T.save).click();
  await expect(page.getByRole('alert').filter({ hasText: TEXT.noSeat }), 'the existing message').toBeVisible();
  expect((await team.list(salon, owner.tokens)).length, 'nobody added').toBe(2);
  await id(page, T.close).click();
  await id(page, T.leaveConfirm).click();
  await card(page, a.id).getByTestId(T.cardEdit).click();
  await id(page, T.title).fill('Takes No Seat');
  await id(page, T.save).click();
  await expect(page.getByText(TEXT.saved), 'an edit needs no seat').toBeVisible();
  const billing = await accounts.open(context, '/workspace/billing');
  await expect(billing.getByTestId(T.billingSeats), 'seats used').toContainText('3');
  await expect(billing.getByTestId(T.billingSeats), 'of the plan\'s').toContainText(String(plan.staffSeats));
  await test.step('removing a person shows at once in the usage', async () => {
    await team.remove(salon, owner.tokens, await team.get(salon, owner.tokens, a.id));
    await billing.reload();
    await expect(billing.getByTestId(T.billingSeats), 'one person fewer').toContainText('2');
  });
});

// ---------------------------------------------------------------- AC-7 removal

test('TC-F06-S01-049 AC-7: Remove from team asks first with the spec\'s words; Cancel keeps the person, and Remove takes them off the list and says "Removed from the team."', async ({ accounts, operator, team }) => {
  const { salon, owner } = await teamSalon(operator, accounts.identity);
  const account = await team.createAccount(salon, owner.tokens);
  const person = await team.add(salon, owner.tokens, memberInput({ name: 'Leaving Person', signInAccountId: account.id }));
  const { page } = await open(accounts, owner);
  await card(page, person.id).getByTestId(T.cardEdit).click();
  await id(page, T.remove).click();
  const dialog = id(page, T.removeDialog);
  await expect(dialog).toBeVisible();
  await expect(dialog).toContainText(TEXT.removeTitle('Leaving Person'));
  await expect(dialog).toContainText(TEXT.removeBody);
  await id(page, T.removeCancel).click();
  await expect(dialog).toHaveCount(0);
  expect((await team.list(salon, owner.tokens)).map(m => m.id), 'Cancel keeps them').toEqual([person.id]);
  await id(page, T.remove).click();
  await id(page, T.removeConfirm).click();
  await expect(page.getByText(TEXT.removed)).toBeVisible();
  await expect(card(page, person.id)).toHaveCount(0);
  expect(await team.list(salon, owner.tokens), 'gone').toEqual([]);
  expect((await accounts.identity.list(owner as SignedStaff)).find(a => a.id === account.id)?.active, 'their account stays').toBe(true);
});

// ---------------------------------------------------------------- AC-9 the photo control

test('TC-F06-S01-050 AC-9, AC-16: with no photo a card shows the person\'s initial on one of the prototype\'s six colours, chosen by id; the named photo control previews a chosen photo, shows the reason for a refused one keeping the previous, and Remove removes it', async ({ accounts, operator, team }) => {
  const { salon, owner } = await teamSalon(operator, accounts.identity);
  const people: TeamMember[] = [];
  for (const name of ['Amal', 'Bimal', 'Chamara', 'Dilan', 'Eranga', 'Farah', 'Gayan', 'Hasini', 'Ishan', 'Janaki', 'Kavinda', 'Lahiru']) {
    people.push(await team.add(salon, owner.tokens, memberInput({ name })));
  }
  const person = people[0];
  const { page } = await open(accounts, owner);
  const colourOf = (memberId: string) => card(page, memberId).getByTestId(T.cardInitial).evaluate(el => getComputedStyle(el).backgroundColor);
  await test.step('the initial on a colour', async () => {
    await expect(card(page, person.id).getByTestId(T.cardInitial)).toHaveText('A');
    const colours = await Promise.all(people.map(p => colourOf(p.id)));
    for (const colour of colours) expect(AVATAR_COLOURS, 'one of the prototype\'s six').toContain(colour);
    expect(new Set(colours).size, 'chosen by id, so twelve people do not all share one').toBeGreaterThan(1);
    const before = colours[0];
    await page.reload();
    await ready(page);
    expect(await colourOf(person.id), 'the same colour on the next visit').toBe(before);
  });
  await card(page, person.id).getByTestId(T.cardEdit).click();
  // The sheet's own avatar: the card behind it shows the same photo.
  const preview = id(page, T.sheet).getByTestId(T.photoPreview);
  await expect(id(page, T.photoChoose), 'the photo control has a name').toHaveAccessibleName(/photo/i);
  await page.getByTestId(T.photo).setInputFiles(fixture('logo-700x600-banded.png'));
  await expect(preview).toBeVisible();
  await expect.poll(async () => (await team.get(salon, owner.tokens, person.id)).photoId, { message: 'the photo is saved' }).not.toBeNull();
  const saved = (await team.get(salon, owner.tokens, person.id)).photoId!;
  await expect(preview, 'served through the salon\'s own address').toHaveAttribute('src', new RegExp(saved));
  await test.step('a refused file', async () => {
    await page.getByTestId(T.photo).setInputFiles(fixture('not-an-image.txt', 'image/png', 'photo.png'));
    await expect(id(page, T.photoError), 'the reason is shown').toBeVisible();
    expect((await team.get(salon, owner.tokens, person.id)).photoId, 'the previous photo is kept').toBe(saved);
  });
  await test.step('Remove', async () => {
    await id(page, T.photoRemove).click();
    await expect.poll(async () => (await team.get(salon, owner.tokens, person.id)).photoId, { message: 'the photo is removed' }).toBeNull();
    await expect(preview).toHaveCount(0);
  });
});

// ---------------------------------------------------------------- AC-16 states, leave guard, widths, keyboard and accessibility

test('TC-F06-S01-051 AC-16: the Team tab shows its loading state, says an unreachable service plainly and recovers on Try again, and a change refused after a role change says so and saves nothing', async ({ accounts, operator, team }) => {
  const { salon, owner } = await teamSalon(operator, accounts.identity);
  const person = await team.add(salon, owner.tokens, memberInput({ name: 'State Person' }));
  const context = await accounts.context(salon, [owner]);
  const data = (url: URL) => PENDING_F06_S01.bffMatch(url);
  await test.step('loading', async () => {
    let release!: () => void;
    const held = new Promise<void>(resolve => { release = resolve; });
    await context.route(data, async r => { await held; await r.continue(); });
    const page = await context.newPage();
    void page.goto(TEAM_ROUTE);
    await expect(id(page, T.loading), 'the Skeleton while loading').toBeVisible();
    release();
    await ready(page);
    await context.unroute(data);
    await page.close();
  });
  await test.step('unreachable, then Try again', async () => {
    await context.route(data, r => r.abort('connectionfailed'));
    const page = await accounts.open(context, TEAM_ROUTE);
    await expect(id(page, T.unavailable)).toBeVisible();
    await context.unroute(data);
    await id(page, T.retry).click();
    await ready(page);
    await expect(card(page, person.id)).toBeVisible();
    await page.close();
  });
  await test.step('a refused change: the Manager is made a Receptionist while the page is open', async () => {
    const manager = await accounts.identity.staff(salon, 'MANAGER');
    const { page } = await open(accounts, manager);
    await card(page, person.id).getByTestId(T.cardEdit).click();
    await id(page, T.title).fill('Should Not Save');
    const record = (await accounts.identity.list(owner as SignedStaff)).find(x => x.id === manager.principal.id)!;
    const changed = await accounts.identity.call(salon, 'PATCH', `/staff/${manager.principal.id}`, { version: record.version, role: 'RECEPTIONIST' }, owner.tokens);
    expect(changed.status(), 'the Owner changed the role').toBe(200);
    await id(page, T.save).click();
    await expect(id(page, T.refused), 'the sheet says the change is not allowed').toBeVisible();
    expect((await team.get(salon, owner.tokens, person.id)).title, 'nothing saved').toBe(person.title);
  });
});

test('TC-F06-S01-052 AC-16: leaving the sheet with unsaved edits by Escape or Close asks first; Stay keeps the edits, Leave discards them, and closing the page asks the browser', async ({ accounts, operator, team }) => {
  const { salon, owner } = await teamSalon(operator, accounts.identity);
  const person = await team.add(salon, owner.tokens, memberInput({ name: 'Guarded' }));
  const { page } = await open(accounts, owner);
  await card(page, person.id).getByTestId(T.cardEdit).click();
  await id(page, T.name).fill('Guarded Edit');
  await test.step('Escape, then Stay', async () => {
    await page.keyboard.press('Escape');
    await expect(id(page, T.leaveDialog)).toBeVisible();
    await id(page, T.leaveStay).click();
    await expect(id(page, T.name), 'the edit is kept').toHaveValue('Guarded Edit');
  });
  await test.step('closing the page asks the browser', async () => {
    const dialog = page.waitForEvent('dialog');
    await page.close({ runBeforeUnload: true });
    const shown = await dialog;
    expect(shown.type()).toBe('beforeunload');
    await shown.dismiss();
    await expect(id(page, T.name), 'dismissing keeps the page and the edit').toHaveValue('Guarded Edit');
  });
  await test.step('Close, then Leave', async () => {
    await id(page, T.close).click();
    await expect(id(page, T.leaveDialog)).toBeVisible();
    await id(page, T.leaveConfirm).click();
    await expect(id(page, T.sheet)).toHaveCount(0);
    expect((await team.get(salon, owner.tokens, person.id)).name, 'nothing saved').toBe('Guarded');
  });
  await test.step('an untouched sheet closes without asking', async () => {
    await card(page, person.id).getByTestId(T.cardEdit).click();
    await id(page, T.close).click();
    await expect(id(page, T.sheet)).toHaveCount(0);
    await expect(page.getByTestId(T.leaveDialog)).toHaveCount(0);
  });
});

test('TC-F06-S01-053 AC-16: at every width of the matrix the Team tab with long names, the Empty state and the sheet fit with no sideways scrolling and every control reachable', async ({ accounts, operator, team, cat }) => {
  test.setTimeout(300_000);
  const { salon, owner } = await teamSalon(operator, accounts.identity);
  const empty = await teamSalon(operator, accounts.identity);
  await arrangeMenu(cat, salon, owner.tokens);
  const person = await team.add(salon, owner.tokens, memberInput({
    name: 'A name of exactly forty characters long!', title: 'A title of exactly forty characters long', highlights: 'Highlights of sixty characters are the most the sheet allows', bio: 'A bio of the longest kind. '.repeat(6).slice(0, 140),
  }));
  for (let n = 1; n <= 9; n++) await team.add(salon, owner.tokens, memberInput({ name: `Another person ${n}` }));
  for (const { width, height } of DESIGN_WIDTHS) {
    await test.step(`${width} wide`, async () => {
      const context = await accounts.context(salon, [owner], { width });
      const page = await accounts.open(context, TEAM_ROUTE);
      await page.setViewportSize({ width, height });
      await ready(page);
      const checkFit = async (on: Page, label: string) => {
        await settled(on);
        const f = await fit(on);
        expect(f.counted, `${width} ${label}: elements measured`).toBeGreaterThan(0);
        expect(f.pageScrolls, `${width} ${label}: no sideways scrolling`).toBe(false);
        expect(f.outside, `${width} ${label}: nothing outside the screen`).toEqual([]);
      };
      await checkFit(page, 'Team tab');
      await expect(id(page, T.newMember)).toBeInViewport();
      await card(page, person.id).getByTestId(T.cardEdit).click();
      await checkFit(page, 'sheet');
      await id(page, T.save).scrollIntoViewIfNeeded();
      await expect(id(page, T.save)).toBeInViewport();
      await expect(id(page, T.remove)).toBeVisible();
      await id(page, T.close).click();
      await id(page, T.tabAccounts).click();
      await accounts.ready(page);
      await checkFit(page, 'Sign-in accounts tab');
      await context.close();
      const emptyContext = await accounts.context(empty.salon, [empty.owner], { width });
      const emptyPage = await accounts.open(emptyContext, TEAM_ROUTE);
      await emptyPage.setViewportSize({ width, height });
      await ready(emptyPage);
      await checkFit(emptyPage, 'Empty state');
      await expect(id(emptyPage, T.emptyNew)).toBeVisible();
      await emptyContext.close();
    });
  }
});

test('TC-F06-S01-054 AC-16: the Team tab, the sheet with a field error and the removal confirmation have no serious accessibility violation for the Owner and a read-only role; a person is added by keyboard alone with a visible focus, and the photo control and each category\'s Choose are named', async ({ accounts, operator, team, cat }) => {
  const { salon, owner } = await teamSalon(operator, accounts.identity);
  const { colour } = await arrangeMenu(cat, salon, owner.tokens);
  const person = await team.add(salon, owner.tokens, memberInput({ name: 'Accessible Person', services: { categoryIds: [colour.id], serviceIds: [] } }));
  const { page } = await open(accounts, owner);
  await accessible(page, 'Team tab');
  await card(page, person.id).getByTestId(T.cardEdit).click();
  await id(page, T.name).fill('A');
  await expect(id(page, T.problem).filter({ hasText: TEXT.err.name })).toBeVisible();
  await accessible(page, 'sheet with a field error');
  await test.step('accessible names', async () => {
    await expect(id(page, T.photoChoose), 'the photo control').toHaveAccessibleName(/photo/i);
    await expect(categoryRow(page, colour.id).getByTestId(T.categoryChoose), 'Choose names its category').toHaveAccessibleName(/Colour/);
    await expect(id(page, T.problems), 'the error is announced').toHaveAttribute('role', 'alert');
  });
  await id(page, T.name).fill('Accessible Person');
  await id(page, T.remove).click();
  await accessible(page, 'removal confirmation');
  await id(page, T.removeCancel).click();
  await id(page, T.close).click();
  await expect(id(page, T.sheet)).toHaveCount(0);
  await test.step('add a person by keyboard', async () => {
    const add = id(page, T.newMember);
    await tabTo(page, add);
    const focus = await focusIndicator(add);
    expect(focus.focusVisible && focus.outlineWidth > 0 && focus.outlineStyle !== 'none', `a visible focus: ${JSON.stringify(focus)}`).toBe(true);
    await page.keyboard.press('Enter');
    await expect(id(page, T.name)).toBeVisible();
    await tabTo(page, id(page, T.name));
    await page.keyboard.type('Keyboard Person');
    await tabTo(page, id(page, T.online));
    await page.keyboard.press('Space');
    await expect(id(page, T.online), 'Space turns it off').not.toBeChecked();
    await tabTo(page, id(page, T.save));
    await page.keyboard.press('Enter');
    await expect(id(page, T.sheet)).toHaveCount(0);
    const added = (await team.list(salon, owner.tokens)).find(m => m.name === 'Keyboard Person');
    expect(added?.shownOnline, 'added, hidden as chosen').toBe(false);
  });
  await test.step('a read-only role', async () => {
    const receptionist = await accounts.identity.staff(salon, 'RECEPTIONIST');
    const other = await open(accounts, receptionist);
    await accessible(other.page, 'Team tab, Receptionist');
  });
});
