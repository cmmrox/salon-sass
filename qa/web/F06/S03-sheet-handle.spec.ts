/**
 * F06-S03 AC-14: on a phone the Sheet's handle is visible at the prototype's size when its content overflows, on the
 * schedule and profile sheets and on one sheet of each delivered feature that has one (decision 103).
 *
 * A rendered height is what counts, not classes: the handle is a generated element (`.sheet>div:before` in the
 * prototype, 40 x 4 pixels), and F06-S02's developer measured it at 0px when the content overflowed. So each sheet is
 * first made to overflow, by a short window if its content does not already, and that overflow is asserted: a sheet
 * that fits proves nothing about this defect. If the fix replaces the generated element with a real one, the build
 * names it in `HANDLE_SLOT`, to confirm against the handoff.
 *
 * What this cannot prove: "No other part of any sheet changes". It holds each sheet to the bottom edge at the window's
 * width, as F02 does, and leaves the comparison of everything else to a pass against the accepted build.
 */
import type { Browser, Locator, Page } from '@playwright/test';

import { consoleContext, consoleUrl } from '../../shared/console-browser';
import { handle, expect, routes, type AccountBrowser } from '../../shared/identity-browser';
import type { Actor } from '../../shared/identity';
import type { Operator } from '../../shared/operator';
import { memberInput, teamSalon, type Team, type TeamMember } from '../../shared/team';
import { TEAM_ROUTE, card, id, ready, settled, test } from '../../shared/team-browser';
import { PENDING_F06_S02 } from '../../shared/team-schedule';

test.describe.configure({ timeout: 180_000 });

/** The prototype's handle: `width:40px;height:4px`. */
const PROTOTYPE = { width: 40, height: 4 };
const PHONES = [360, 390];
/** Window heights tried, tallest first, until the sheet's content overflows. F02's confirmation is short enough to fit at 240. */
const HEIGHTS = [800, 600, 440, 320, 240, 180, 150];
/** If the fix uses a real element rather than the generated one. */
const HANDLE_SLOT = '[data-slot="sheet-handle"]';

type Measured = { height: number; width: number; visible: boolean; overflows: boolean };

/** The handle as rendered, and whether anything in the sheet that scrolls has more than it shows. */
const measure = (sheet: Locator) => sheet.evaluate((el, slot): Measured => {
  const real = el.querySelector(slot);
  const style = real ? getComputedStyle(real) : getComputedStyle(el, '::before');
  const generated = real !== null || (style.content !== 'none' && style.content !== 'normal');
  const scrollers = [el, ...Array.from(el.querySelectorAll('*'))].filter(n => ['auto', 'scroll'].includes(getComputedStyle(n).overflowY));
  return {
    height: generated && style.display !== 'none' ? parseFloat(style.height) || 0 : 0,
    width: generated && style.display !== 'none' ? parseFloat(style.width) || 0 : 0,
    visible: generated && style.display !== 'none' && style.visibility === 'visible' && style.backgroundColor !== 'rgba(0, 0, 0, 0)',
    overflows: scrollers.some(n => n.scrollHeight > n.clientHeight + 1),
  };
}, HANDLE_SLOT);

/** Shrinks the window until the sheet overflows, then asserts the handle there, and that the sheet stays on the bottom edge. */
async function expectHandle(page: Page, sheet: Locator, label: string, width: number) {
  let found: Measured | null = null;
  let height = 0;
  for (height of HEIGHTS) {
    await page.setViewportSize({ width, height });
    await settled(page);
    const m = await measure(sheet);
    if (m.overflows) { found = m; break; }
  }
  expect(found, `${label} at ${width}: its content overflows at some height, or this proves nothing`).not.toBeNull();
  expect({ height: found!.height, width: found!.width }, `${label} at ${width} x ${height}: the handle as rendered, at the prototype's size`).toEqual(PROTOTYPE);
  expect(found!.visible, `${label} at ${width} x ${height}: and it can be seen`).toBe(true);
  const box = (await sheet.boundingBox())!;
  expect(Math.round(box.y + box.height), `${label}: on the bottom edge`).toBe(height);
  expect(Math.round(box.width), `${label}: the window's width`).toBe(width);
}

const salonSheet = (page: Page) => page.locator('[data-slot="sheet-content"]').filter({ visible: true });

/** One sheet of each delivered feature that has one, each opened in a page already at the phone's width. */
function sheets(accounts: AccountBrowser, browser: Browser, operator: Operator, owner: Actor, person: TeamMember) {
  const signedIn = async (route: string, width: number) => {
    const context = await accounts.context(owner.salon, [owner], { width });
    return { context, page: await accounts.open(context, route) };
  };
  return [
    { label: 'F02 confirmation', open: async (width: number) => {
      const { context, page } = await signedIn(routes.staff, width);
      await accounts.ready(page);
      await handle(page, 'revoke-all').click();
      return { context, page, sheet: salonSheet(page) };
    } },
    { label: 'F03 console sheet', open: async (width: number) => {
      const context = await consoleContext(browser, await operator.actor(), { width });
      const page = await context.newPage();
      await page.goto(consoleUrl('/console/plans'));
      await page.getByTestId('console-create-plan').click();
      return { context, page, sheet: page.getByTestId('console-create-plan-sheet') };
    } },
    { label: 'F04 closure sheet', open: async (width: number) => {
      const { context, page } = await signedIn('/workspace/settings/hours', width);
      await id(page, 'closure-add').click();
      return { context, page, sheet: id(page, 'closure-sheet') };
    } },
    { label: 'F05 service sheet', open: async (width: number) => {
      const { context, page } = await signedIn('/workspace/services', width);
      await id(page, 'services-new').click();
      return { context, page, sheet: id(page, 'service-sheet') };
    } },
    { label: 'F06 profile sheet', open: async (width: number) => {
      const { context, page } = await signedIn(TEAM_ROUTE, width);
      await ready(page);
      await id(page, 'team-add').click();
      return { context, page, sheet: id(page, 'team-sheet') };
    } },
    { label: 'F06 schedule sheet', open: async (width: number) => {
      const { context, page } = await signedIn(TEAM_ROUTE, width);
      await ready(page);
      await card(page, person.id).getByTestId(PENDING_F06_S02.web.cardSchedule).click();
      return { context, page, sheet: id(page, PENDING_F06_S02.web.sheet) };
    } },
  ];
}

async function arrange(operator: Operator, accounts: AccountBrowser, team: Team) {
  const { salon, owner } = await teamSalon(operator, accounts.identity);
  const person = await team.add(salon, owner.tokens, memberInput({ name: 'Handle Person' }));
  return { salon, owner, person };
}

test('TC-F06-S03-043 AC-14: at phone widths the handle is rendered at the prototype\'s 40 by 4 pixels and can be seen when the content overflows, on the schedule and profile sheets and one sheet each of F02, F03, F04 and F05', async ({ accounts, operator, team, browser }) => {
  test.setTimeout(300_000);
  const { owner, person } = await arrange(operator, accounts, team);
  const all = sheets(accounts, browser, operator, owner, person);
  expect(all.map(s => s.label.slice(0, 3)), 'every delivered feature with a sheet is here').toEqual(['F02', 'F03', 'F04', 'F05', 'F06', 'F06']);
  for (const { label, open } of all) {
    for (const width of PHONES) {
      await test.step(`${label} at ${width}`, async () => {
        const { context, page, sheet } = await open(width);
        await expect(sheet).toBeVisible();
        await expectHandle(page, sheet, label, width);
        await context.close();
      });
    }
  }
});

test('TC-F06-S03-044 AC-14: the handle does not change the wide layout: at 1280 each sheet is the full-height drawer with no handle, as in the prototype', async ({ accounts, operator, team, browser }) => {
  test.setTimeout(240_000);
  const { owner, person } = await arrange(operator, accounts, team);
  for (const { label, open } of sheets(accounts, browser, operator, owner, person)) {
    await test.step(label, async () => {
      const { context, page, sheet } = await open(1280);
      await expect(sheet).toBeVisible();
      await settled(page);
      const m = await measure(sheet);
      expect(m.height, `${label}: no handle`).toBe(0);
      const box = (await sheet.boundingBox())!;
      expect(Math.round(box.y), `${label}: from the top`).toBe(0);
      expect(Math.round(box.height), `${label}: to the bottom`).toBe(page.viewportSize()!.height);
      await context.close();
    });
  }
});
