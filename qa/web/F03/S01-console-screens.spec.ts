/**
 * F03-S01 console screens: the keyboard journey, the width matrix, accessibility, and what each
 * operator role is shown. Cases TC-F03-S01-032 to -035 (cases.md); -036 is manual.
 *
 * Arrangement is through the API with real operator credentials bridged into the console's
 * cookies; the journey itself (-032) signs in through the screens by keyboard. Test ids are the
 * S01 handoff's `console-*` ids; form fields are `console-<kebab field name>`.
 */
import type { Locator, Page } from '@playwright/test';

import {
  test, expect, WIDTHS, consoleContext, consoleUrl, expectAccessible, expectFits, expectSheetFocus, signInByKeyboard,
} from '../../shared/console-browser';
import { operatorAccount, uniqueSlug, strongPassword, type Operator } from '../../shared/operator';

const id = (n: number) => `TC-F03-S01-${String(n).padStart(3, '0')}`;

async function type(page: Page, field: Locator, text: string) {
  await field.focus();
  await page.keyboard.press('ControlOrMeta+A');
  await page.keyboard.type(text);
}

/** A shadcn/ui Select by keyboard: open the trigger with Enter, move to the option, choose it with Enter. */
async function choose(page: Page, trigger: Locator, option: Locator) {
  await trigger.focus();
  await page.keyboard.press('Enter');
  await expect(option).toBeVisible();
  await option.focus();
  await page.keyboard.press('Enter');
  await expect(page.getByRole('listbox')).toHaveCount(0);
}

async function seeded(operator: Operator, role: 'OPERATOR_ADMIN' | 'OPERATOR_SUPPORT' = 'OPERATOR_ADMIN') {
  return operator.actor(role);
}

test(`${id(32)} A keyboard user signs in, defines a plan, creates a salon and inspects it`, async ({ browser, operator }) => {
  test.setTimeout(120_000);
  const account = operatorAccount('OPERATOR_ADMIN', false);
  await operator.seed(account);
  const context = await consoleContext(browser);
  const page = await context.newPage();
  await signInByKeyboard(page, operator, account);
  // A plan, by keyboard.
  await page.goto(consoleUrl('/console/plans'));
  await expect(page.getByTestId('console-plans')).toBeVisible();
  await page.getByTestId('console-create-plan').focus();
  await page.keyboard.press('Enter');
  await expect(page.getByTestId('console-create-plan-sheet')).toBeVisible();
  const plan = { key: uniqueSlug('ui'), name: `QA UI plan ${Date.now()}`, staffSeats: '4', branches: '2', messageAllowance: '50' };
  await type(page, page.getByTestId('console-plan-key'), plan.key);
  await type(page, page.getByTestId('console-plan-name'), plan.name);
  await type(page, page.getByTestId('console-staff-seats'), plan.staffSeats);
  await type(page, page.getByTestId('console-branches'), plan.branches);
  await type(page, page.getByTestId('console-message-allowance'), plan.messageAllowance);
  await page.getByTestId('console-custom-domain').focus();
  await page.keyboard.press('Space');
  await page.getByTestId('console-create-plan-submit').focus();
  await page.keyboard.press('Enter');
  await expect(page.getByTestId('console-plan-created')).toBeVisible();
  // A separate Admin reads through the API: a second sign-in in the same TOTP step would be a replay.
  const admin = await operator.actor();
  const created = (await operator.planPage(admin, `?q=${plan.key}`)).items[0];
  expect(created, 'the plan exists through the API').toMatchObject({ key: plan.key, name: plan.name, staffSeats: 4, branches: 2, messageAllowance: 50, customDomain: true, dedicatedApp: false });
  // A salon, by keyboard.
  await page.goto(consoleUrl('/console/salons'));
  await page.getByTestId('console-create-salon').focus();
  await page.keyboard.press('Enter');
  await expect(page.getByTestId('console-create-salon-sheet')).toBeVisible();
  const slug = uniqueSlug('ui');
  const ownerEmail = `owner-${slug}@example.invalid`;
  await type(page, page.getByTestId('console-salon-name'), `QA UI Salon ${slug}`);
  await type(page, page.getByTestId('console-slug'), slug);
  await choose(page, page.getByTestId('console-plan-id'), page.getByTestId(`console-plan-id-option-${created.id}`));
  await expect(page.getByTestId('console-country'), 'the documented default country').toHaveValue('LK');
  await expect(page.getByTestId('console-currency'), 'the documented default currency').toHaveValue('LKR');
  await expect(page.getByTestId('console-time-zone'), 'the documented default zone').toHaveValue('Asia/Colombo');
  await type(page, page.getByTestId('console-contact-email'), `contact-${slug}@example.invalid`);
  await type(page, page.getByTestId('console-monthly-amount'), '2500.50');
  await type(page, page.getByTestId('console-owner-name'), 'QA UI Owner');
  await type(page, page.getByTestId('console-owner-email'), ownerEmail);
  await type(page, page.getByTestId('console-owner-password'), strongPassword());
  await page.getByTestId('console-create-salon-submit').focus();
  await page.keyboard.press('Enter');
  await expect(page.getByTestId('console-salon-created')).toBeVisible();
  await expect(page.getByTestId('console-salon-created-hostname')).toHaveText(`${slug}.localhost`);
  await expect(page.getByTestId('console-salon-created-next'), 'the Owner sign-in next step').toBeVisible();
  await expect(page.getByTestId('console-owner-password'), 'the password is cleared on success').toHaveCount(0);
  await page.getByTestId('console-salon-created-view').focus();
  await page.keyboard.press('Enter');
  await expect(page.getByTestId('console-salon-detail')).toBeVisible();
  await expect(page.getByTestId('console-salon-hostname')).toHaveText(`${slug}.localhost`);
  await expect(page.getByTestId('console-salon-plan')).toContainText(plan.name);
  const salon = (await operator.salonPage(admin, `?q=${slug}`)).items[0];
  const detail = await operator.salonDetail(admin, salon.id);
  expect(detail).toMatchObject({ slug, ownerEmail, plan: { id: created.id } });
  expect((await operator.subscription(admin, salon.id)).monthlyAmount, 'major units entered, minor units stored').toBe(250050);
  await context.close();
});

test(`${id(33)} Console screens fit every width in the matrix without clipping or horizontal scroll`, async ({ browser, operator }) => {
  test.setTimeout(240_000);
  const admin = await seeded(operator);
  const plan = await operator.plan(admin, { name: `QA plan with a deliberately long name that must wrap ${'x'.repeat(40)}` });
  const { created } = await operator.salon(admin, plan.id, { name: `QA Salon ${'Long Name '.repeat(8)}`.slice(0, 120) });
  const screens: [string, string, string, string?][] = [
    ['salon directory', '/console/salons', 'console-salons', 'console-create-salon'],
    ['salon detail', `/console/salons/${created.id}`, 'console-salon-detail'],
    ['plan directory', '/console/plans', 'console-plans', 'console-create-plan'],
    ['plan detail', `/console/plans/${plan.id}`, 'console-plan-detail'],
  ];
  for (const viewport of WIDTHS) {
    // A session of its own per context: renewing a shared refresh token would revoke the family.
    const context = await consoleContext(browser, await operator.actor(), { width: viewport.width });
    const page = await context.newPage();
    for (const [label, route, ready, sheet] of screens) {
      await page.goto(consoleUrl(route));
      await expect(page.getByTestId(ready)).toBeVisible();
      await expectFits(page, `${label} at ${viewport.width}`);
      if (sheet) {
        await page.getByTestId(sheet).click();
        await expect(page.getByTestId(`${sheet}-sheet`)).toBeVisible();
        await expectFits(page, `${label} sheet at ${viewport.width}`);
        await page.keyboard.press('Escape');
      }
    }
    await context.close();
  }
  const signIn = await consoleContext(browser, undefined, { width: 360 });
  const page = await signIn.newPage();
  await page.goto(consoleUrl('/console/sign-in'));
  await expect(page.getByTestId('console-sign-in-card')).toBeVisible();
  await expectFits(page, 'sign-in at 360');
  await signIn.close();
});

test(`${id(34)} Console screens pass the automated scan, keep focus contained and announce errors`, async ({ browser, operator }) => {
  const admin = await seeded(operator);
  const plan = await operator.plan(admin);
  const { created } = await operator.salon(admin, plan.id);
  const anonymous = await consoleContext(browser);
  const signIn = await anonymous.newPage();
  await signIn.goto(consoleUrl('/console/sign-in'));
  await expectAccessible(signIn, 'sign-in');
  await anonymous.close();
  const context = await consoleContext(browser, admin);
  const page = await context.newPage();
  for (const [route, ready] of [['/console/salons', 'console-salons'], [`/console/salons/${created.id}`, 'console-salon-detail'],
    ['/console/plans', 'console-plans'], [`/console/plans/${plan.id}`, 'console-plan-detail']] as const) {
    await page.goto(consoleUrl(route));
    await expect(page.getByTestId(ready)).toBeVisible();
    await expectAccessible(page, route);
  }
  for (const [route, opener] of [['/console/salons', 'console-create-salon'], ['/console/plans', 'console-create-plan']] as const) {
    await page.goto(consoleUrl(route));
    await expectSheetFocus(page, page.getByTestId(opener), page.getByTestId(`${opener}-sheet`));
  }
  // A refused submission is announced and names the field.
  await page.getByTestId('console-create-plan').click();
  await page.getByTestId('console-create-plan-submit').click();
  await expect(page.getByTestId('console-error')).toBeVisible();
  await expect(page.getByTestId('console-error')).toHaveAttribute('role', 'alert');
  await expect(page.getByTestId('console-plan-key')).toHaveAttribute('aria-invalid', 'true');
  await expectAccessible(page, 'create plan with errors');
  await context.close();
});

test(`${id(35)} Support sees salon directory access only, and Admin sees only Salons, Plans and Sign out`, async ({ browser, operator }) => {
  const admin = await seeded(operator);
  const support = await seeded(operator, 'OPERATOR_SUPPORT');
  const navigation = (page: Page) => page.getByRole('navigation').first().getByRole('link');
  const adminContext = await consoleContext(browser, admin);
  const adminPage = await adminContext.newPage();
  await adminPage.goto(consoleUrl('/console/salons'));
  await expect(adminPage.getByTestId('console-salons')).toBeVisible();
  const adminLinks = (await navigation(adminPage).evaluateAll(links => links.map(l => new URL((l as HTMLAnchorElement).href).pathname))).sort();
  // S01 AC-18 names Salons and Plans; S04 adds the Support sessions screen for Admin (its spec, "UI and UX").
  expect(adminLinks, 'Admin navigation').toEqual(expect.arrayContaining(['/console/plans', '/console/salons']));
  expect(adminLinks.filter(l => !['/console/plans', '/console/salons', '/console/support'].includes(l)), 'no other management link').toEqual([]);
  await expect(adminPage.getByTestId('console-sign-out')).toBeVisible();
  await adminContext.close();
  // From S04 Support reads the directory (operator-console.md); it has no plan or billing management.
  const supportContext = await consoleContext(browser, support);
  const supportPage = await supportContext.newPage();
  await supportPage.goto(consoleUrl('/console/salons'));
  await expect(supportPage.getByTestId('console-salons')).toBeVisible();
  await expect(supportPage.getByTestId('console-create-salon'), 'no creation for Support').toHaveCount(0);
  await supportPage.goto(consoleUrl('/console/plans'));
  await expect(supportPage.getByTestId('console-denied'), 'plans are refused clearly').toBeVisible();
  await expect(supportPage.getByTestId('console-create-plan')).toHaveCount(0);
  const supportLinks = await navigation(supportPage).evaluateAll(links => links.map(l => new URL((l as HTMLAnchorElement).href).pathname));
  expect(supportLinks, 'no plan link for Support').not.toContain('/console/plans');
  await expect(supportPage.getByTestId('console-sign-out')).toBeVisible();
  await supportContext.close();
});
