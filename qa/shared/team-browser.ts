/**
 * F06 browser fixtures the schedule's screens share: the test with its operator, team and schedule
 * helpers, finding visible elements (`cacheComponents` keeps a route it has left in the document, hidden),
 * opening the Team page and a person's schedule sheet, a support session's browser, and collecting what
 * the browser received (a screen can hide what an answer carried, as F06-S02's dry run showed).
 */
import type { Browser, BrowserContext, Locator, Page } from '@playwright/test';

import { webUrl } from './env';
import { test as browserTest, expect, routes, type AccountBrowser } from './identity-browser';
import type { Actor } from './identity';
import { Operator, supportJson, type SupportStart } from './operator';
import { Hours, localDate } from './salon-hours';
import { Team } from './team';
import { PENDING_F06_S02, Schedules } from './team-schedule';
import { salonUrl, type Salon } from './tenants';

export const test = browserTest.extend<{ operator: Operator; team: Team; schedules: Schedules; hours: Hours }>({
  operator: async ({ identity }, use) => {
    const operator = new Operator(identity);
    await operator.resetFixtures();
    try { await use(operator); } finally { await operator.resetToBaseline(); }
  },
  team: async ({ identity }, use) => { const team = new Team(identity); try { await use(team); } finally { await team.dispose(); } },
  schedules: async ({ team }, use) => { await use(new Schedules(team)); },
  hours: async ({ identity }, use) => { await use(new Hours(identity)); },
});

const W = PENDING_F06_S02.web;
const COLOMBO = 'Asia/Colombo';

export const TEAM_ROUTE = routes.team;
export const id = (scope: Page | Locator, name: string) => scope.getByTestId(name).filter({ visible: true });
export const card = (page: Page, memberId: string) => page.locator(`[data-testid="team-card"][data-id="${memberId}"]`).filter({ visible: true });
export const sheetOf = (page: Page) => id(page, W.sheet);
export const button = (scope: Page | Locator, name: string | RegExp, exact = false) => scope.getByRole('button', { name, exact }).filter({ visible: true });

export async function settled(page: Page) {
  await page.waitForFunction(() => document.getAnimations().every(a => a.playState !== 'running'));
}

export async function ready(page: Page) {
  await expect(page.getByTestId('team-loading').filter({ visible: true })).toHaveCount(0);
  await expect(id(page, 'team-summary').or(id(page, 'team-empty')).first()).toBeVisible();
}

export async function open(accounts: AccountBrowser, actor: Actor, width = 1280): Promise<{ context: BrowserContext; page: Page }> {
  const context = await accounts.context(actor.salon, [actor], { width });
  const page = await accounts.open(context, TEAM_ROUTE);
  await ready(page);
  return { context, page };
}

/** Opens a person's schedule sheet from their card and waits for it to load. */
export async function openSchedule(page: Page, memberId: string) {
  await card(page, memberId).getByTestId(W.cardSchedule).click();
  const sheet = sheetOf(page);
  await expect(sheet).toBeVisible();
  await expect(id(sheet, W.loading)).toHaveCount(0);
  return sheet;
}

/** A support session's browser, on the salon's own web address. */
export async function supportBrowser(browser: Browser, operator: Operator, created: { id: string }, salon: Salon, width = 1280) {
  const support = await operator.actor('OPERATOR_SUPPORT');
  const session = await supportJson<SupportStart>(await operator.startSupport(support, created.id, { reason: 'The salon asked why a stylist shows no hours' }), 'supportStart', 201);
  const context = await browser.newContext({ baseURL: salonUrl(salon, webUrl), viewport: { width, height: 1000 }, locale: 'en' });
  const entry = await context.newPage();
  await entry.goto(`${salonUrl(salon, webUrl)}/workspace/support#${session.entryCode}`);
  await expect(entry.getByTestId('support-banner')).toBeVisible();
  return context;
}

export const salonToday = async (accounts: AccountBrowser) => localDate(await accounts.identity.now(), COLOMBO);

/**
 * Every answer the browser receives from the team's BFF actions from now on, whichever screen asked. Attach it
 * before the page loads what it must not hold; the document itself is `page.content()`, read separately.
 */
export function browserReceived(page: Page) {
  const answers: Promise<string>[] = [];
  page.on('response', r => {
    const url = new URL(r.url());
    if (url.pathname.startsWith('/identity-web/') && url.pathname.includes('/team-')) answers.push(r.text().catch(() => ''));
  });
  return { count: () => answers.length, text: async () => (await Promise.all(answers)).join('\n') };
}
