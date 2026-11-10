/**
 * F04-S03's case deferred to F07, run with F07's feature pass now that bookings exist: the closure sheet lists the real
 * bookings on the day it would close, and the hours card says how many upcoming bookings a new week leaves outside the
 * salon's hours; every booking is kept.
 *
 * Expected results are F04-S03's AC-8 and its deferred entry's reason (stage.yaml): "N upcoming bookings fall outside
 * these hours" and the bookings on a closed day. The words are the product's (settings.closures.*, settings.hours.*), as
 * F04's screen tests read them; the test ids are those of qa/web/F04/S03-hours-screens.spec.ts. Bookings are made through
 * F07's endpoints. Registered under F04-S03 at F07's feature-verify, when the deferred entry moves to automation.
 */
import { bookingSalon, customer, holdInput } from '../../shared/booking';
import { chooseCalendarDay, id, openAs, test, expect } from '../../shared/booking-browser';
import { plusDays, weekdayOf } from '../../shared/salon-hours';
import { message } from '../../shared/strings';

test.describe.configure({ timeout: 180_000 });

const HOURS = '/workspace/settings/hours';
const plural = (key: string, n: number) => message(key).replace(/\{count, plural, one \{([^}]*)\} other \{([^}]*)\}\}/, (_, one: string, other: string) => (n === 1 ? one : other).replace('#', String(n)));

test('TC-F04-S03-031 AC-8: the closure sheet lists the bookings on the day it would close, and saving hours that close their weekday says how many upcoming bookings fall outside; every booking is kept', async ({ accounts, identity, operator, clock }) => {
  // On the real clock: under the test clock's date in 2101 the Hours tab refuses the Owner (noted for the lead, F07 review).
  const s = await bookingSalon(identity, operator, clock, { people: 2, realClock: true });
  const date = plusDays(s.today, 3);
  const [amaya, kavindi] = [customer(), customer()];
  const confirmed = await s.bookings.confirm(s.salon, s.owner.tokens, (await s.bookings.place(s.salon, s.owner.tokens, holdInput(s.members[0].id, s.service.id, date, '10:00', { customer: amaya }))).id);
  const held = await s.bookings.place(s.salon, s.owner.tokens, holdInput(s.members[1].id, s.service.id, date, '12:00', { customer: kavindi }));
  const { page } = await openAs(accounts, s.owner, HOURS);
  await expect(id(page, 'hours-loading')).toHaveCount(0);
  await test.step('the closure sheet', async () => {
    await id(page, 'closure-add').click();
    await id(page, 'closure-date').click();
    await chooseCalendarDay(id(page, 'closure-calendar'), date);
    await expect(id(page, 'closure-no-bookings'), 'not "no bookings"').toHaveCount(0);
    await expect(page.getByText(plural('settings.closures.bookingsTitle', 2))).toBeVisible();
    await expect(page.getByText(message('settings.closures.bookingsBody'))).toBeVisible();
    for (const [time, who] of [['10:00', amaya], ['12:00', kavindi]] as const) {
      await expect(page.getByText(message('settings.closures.bookingAt').replace('{time}', time).replace('{name}', who.name)), `${time} · ${who.name}`).toBeVisible();
    }
    // Focus leaves the Day popover's trigger first: Escape there belongs to the popover.
    await id(page, 'closure-reason').click();
    await page.keyboard.press('Escape');
    await expect(id(page, 'closure-sheet'), 'closed without closing the day').toBeHidden({ timeout: 10_000 });
    expect(await s.hours.closures(s.salon, s.owner.tokens), 'no closure made').toEqual([]);
  });
  await test.step('the hours card', async () => {
    const day = weekdayOf(date);
    await id(page, `hours-${day}-open`).click();
    await id(page, 'hours-save').click();
    await expect(page.getByText(plural('settings.hours.outsideTitle', 2)), 'two upcoming bookings fall outside').toBeVisible();
  });
  expect((await s.bookings.get(s.salon, s.owner.tokens, confirmed.id)).booking.state, 'kept').toBe('confirmed');
  expect((await s.bookings.get(s.salon, s.owner.tokens, held.id)).booking.state, 'kept').toBe('held');
});
