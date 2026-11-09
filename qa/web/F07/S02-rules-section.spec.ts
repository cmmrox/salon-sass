/**
 * F07-S02 "Holding and assigning" on Settings, Booking rules: its place after "Booking times", its two Selects with the
 * prototype's options and help, saving with the existing Save booking rules and its message, the history naming the
 * change, a Manager doing the same, and the section by keyboard, at 360 pixels and in the accessibility check.
 *
 * Expected results are the F07-S02 spec's AC-8 and AC-12 and the prototype's `setRules` in the F07 block. The tab's
 * other behaviour (loading, errors, two people saving at once, the leave guard, support) is F04-S04's, proved by its
 * own cases, which run again with F07's feature pass. Test ids are `PENDING_F07_S02_WEB`, confirmed at S02's close.
 */
import type { Page } from '@playwright/test';

import { BookingRules } from '../../shared/booking-rules';
import { bookingSalon, staffAt } from '../../shared/booking';
import { DESIGN_WIDTHS, PENDING_F07_S02_WEB as W, RULES_ROUTE, S02_TEXT as T, id, openAs, settled, test, expect } from '../../shared/booking-browser';
import { fit, seriousViolations } from '../../shared/page-checks';

test.describe.configure({ timeout: 180_000 });

type Stored = { holdMinutes?: number; noPreference?: string };

async function ready(page: Page) {
  await expect(id(page, 'rules-loading')).toHaveCount(0);
  await expect(id(page, 'rules-form')).toBeVisible();
}
async function choose(page: Page, trigger: string, value: string) {
  await id(page, trigger).click();
  await id(page, W.option(trigger, value)).click();
  await expect(page.getByTestId(W.option(trigger, value))).toHaveCount(0);
}
const headingY = async (page: Page, name: string) => (await page.getByRole('heading', { name, exact: true }).filter({ visible: true }).boundingBox())?.y ?? NaN;

test('TC-F07-S02-016 AC-8: the Owner finds "Holding and assigning" after "Booking times" with the prototype\'s two Selects and their help, saves 20 minutes and "most booked" with Save booking rules, and the history names the change; a Manager saves it too', async ({ accounts, identity, operator, clock }) => {
  const s = await bookingSalon(identity, operator, clock, { people: 1 });
  const { page } = await openAs(accounts, s.owner, RULES_ROUTE);
  await ready(page);
  await test.step('its place and words', async () => {
    const [before, here, after] = await Promise.all([T.before, T.heading, T.after].map(n => headingY(page, n)));
    expect(before < here && here < after, `${T.before}, then ${T.heading}, then ${T.after}`).toBe(true);
    await expect(page.getByText(T.holdLabel, { exact: true }).filter({ visible: true })).toBeVisible();
    await expect(page.getByText(T.holdHelp, { exact: true }).filter({ visible: true })).toBeVisible();
    await expect(page.getByText(T.whoLabel, { exact: true }).filter({ visible: true })).toBeVisible();
    await expect(page.getByText(T.whoHelp, { exact: true }).filter({ visible: true })).toBeVisible();
    await expect(id(page, W.hold), 'the current hold length').toHaveText('10 minutes');
    await expect(id(page, W.noPreference), 'the current strategy').toHaveText(T.whoOptions[0][1]);
  });
  await test.step('the options', async () => {
    for (const [trigger, options] of [[W.hold, T.holdOptions], [W.noPreference, T.whoOptions]] as const) {
      await id(page, trigger).click();
      for (const [value, label] of options) await expect(id(page, W.option(trigger, value)), `${trigger}: ${label}`).toHaveText(label);
      await expect(page.getByRole('option').filter({ visible: true }), `${trigger}: only these`).toHaveCount(options.length);
      await page.keyboard.press('Escape');
    }
  });
  await test.step('saving', async () => {
    await choose(page, W.hold, '20');
    await choose(page, W.noPreference, 'most_booked');
    await id(page, 'rules-save').click();
    await expect(page.getByText(T.saved)).toBeVisible();
    expect((await new BookingRules(identity).settings(s.salon, s.owner.tokens)).rules as Stored, 'stored').toMatchObject({ holdMinutes: 20, noPreference: 'most_booked' });
  });
  await test.step('the history names the change', async () => {
    await id(page, 'rules-history').click();
    const changed = id(page, 'rules-history-sheet').getByTestId('rules-history-row').first().getByTestId('rules-history-changed');
    await expect(changed, 'how long a hold lasts').toContainText(/\bhold|\bheld\b/i);
    await expect(changed, 'the "No preference" strategy').toContainText(/no preference/i);
  });
  await test.step('a Manager', async () => {
    const manager = await staffAt(s, 'MANAGER');
    const { page: theirs } = await openAs(accounts, manager, RULES_ROUTE);
    await ready(theirs);
    await expect(id(theirs, W.hold)).toHaveText('20 minutes');
    await choose(theirs, W.hold, '5');
    await id(theirs, 'rules-save').click();
    await expect(theirs.getByText(T.saved)).toBeVisible();
    expect(((await new BookingRules(identity).settings(s.salon, s.owner.tokens)).rules as Stored).holdMinutes, 'saved by the Manager').toBe(5);
  });
});

test('TC-F07-S02-017 AC-12: the section is used by keyboard alone, fits every width down to 360 pixels without sideways scrolling, and the tab passes the accessibility check with it', async ({ accounts, identity, operator, clock }) => {
  const s = await bookingSalon(identity, operator, clock, { people: 1 });
  await test.step('by keyboard', async () => {
    const { page } = await openAs(accounts, s.owner, RULES_ROUTE);
    await ready(page);
    const hold = id(page, W.hold);
    let presses = 0;
    while (!(await hold.evaluate(el => el === document.activeElement)) && presses++ < 80) await page.keyboard.press('Tab');
    await expect(hold, 'reached by Tab').toBeFocused();
    await page.keyboard.press('Enter');
    await expect(page.getByRole('listbox')).toBeVisible();
    await expect(page.getByRole('option', { name: '10 minutes', exact: true }), 'the open list starts on the current choice').toBeFocused();
    await page.keyboard.press('ArrowDown');
    await expect(page.getByRole('option', { name: '15 minutes', exact: true }), 'the arrow moves to the next').toBeFocused();
    await page.keyboard.press('Enter');
    await expect(page.getByRole('listbox')).toHaveCount(0);
    await expect(hold, 'the next length chosen by keys').toHaveText('15 minutes');
    await page.keyboard.press('Tab');
    await expect(id(page, W.noPreference), 'the strategy is next').toBeFocused();
  });
  for (const viewport of DESIGN_WIDTHS) {
    await test.step(`${viewport.width} pixels`, async () => {
      const { page } = await openAs(accounts, s.owner, RULES_ROUTE, viewport.width);
      await ready(page);
      await id(page, W.noPreference).scrollIntoViewIfNeeded();
      await settled(page);
      const overflow = await fit(page);
      expect(overflow.pageScrolls, `${viewport.width}: no sideways scrolling`).toBe(false);
      // F04's Settings tab strip scrolls sideways inside itself by design; F04's own width cases leave it out the same way.
      expect(overflow.clipped.filter(c => !c.startsWith('settings-tabs ')), `${viewport.width}: nothing clipped`).toEqual([]);
      for (const control of [W.hold, W.noPreference]) await expect(id(page, control), `${viewport.width}: ${control} is there`).toBeVisible();
      if (viewport.width === 360 || viewport.width === 1280) expect(await seriousViolations(page), `${viewport.width}: no serious or critical violation`).toEqual([]);
    });
  }
});
