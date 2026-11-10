/**
 * F05-S01's case deferred to F07, run with F07's feature pass now that bookings exist: deleting a service with an
 * upcoming booking is refused with 409 in_use, changes nothing, and the sheet says "This service has upcoming bookings.
 * Hide it from online booking instead."
 *
 * Expected results are F05-S01's AC-9 and its deferred entry's reason (stage.yaml). The words are the product's
 * (services.notices.inUseService); the test ids are those of qa/web/F05/S01-services-screens.spec.ts. The booking is made
 * through F07's endpoints. Registered under F05-S01 at F07's feature-verify, when the deferred entry moves to automation.
 */
import { refused } from '../../shared/booking-rules';
import { bookingSalon, holdInput } from '../../shared/booking';
import { id, openAs, test, expect } from '../../shared/booking-browser';
import { catalogueSnapshot } from '../../shared/catalogue';
import { plusDays } from '../../shared/salon-hours';
import { message } from '../../shared/strings';

test.describe.configure({ timeout: 180_000 });

const T = { card: 'service-card', cardEdit: 'service-card-edit', del: 'service-sheet-delete', deleteConfirm: 'service-delete-confirm' } as const;

test('TC-F05-S01-020 AC-9: deleting a service with an upcoming booking is 409 in_use, changes nothing, and the sheet says "This service has upcoming bookings. Hide it from online booking instead."', async ({ accounts, identity, operator, clock }) => {
  const s = await bookingSalon(identity, operator, clock, { people: 1 });
  await s.bookings.place(s.salon, s.owner.tokens, holdInput(s.members[0].id, s.service.id, plusDays(s.today, 2), '10:00'));
  const before = catalogueSnapshot(s.created.id);
  const current = await s.catalogue.service(s.salon, s.owner.tokens, s.service.id);
  await refused(await s.catalogue.deleteRaw(s.salon, s.owner.tokens, s.service.id, current.version), 409, 'in_use', 'delete');
  const { page } = await openAs(accounts, s.owner, '/workspace/services');
  await page.locator(`[data-testid="${T.card}"][data-id="${s.service.id}"]`).getByTestId(T.cardEdit).click();
  await id(page, T.del).click();
  await id(page, T.deleteConfirm).click();
  await expect(page.getByText(message('services.notices.inUseService'))).toBeVisible();
  expect(catalogueSnapshot(s.created.id), 'nothing changed').toBe(before);
  expect((await s.catalogue.service(s.salon, s.owner.tokens, s.service.id)).id, 'the service is still there').toBe(s.service.id);
});
