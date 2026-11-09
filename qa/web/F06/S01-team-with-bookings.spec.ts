/**
 * F06-S01 cases deferred to F07, run with F07's feature pass now that bookings exist: removing a person with upcoming
 * bookings, and a save that stops someone doing services they have upcoming bookings for. Each is refused by the API
 * with the errors the deferred entry names, changes nothing, and the sheet says so in F06's words.
 *
 * Expected results are F06-S01's (AC-7, AC-8, "API and data changes") and the deferred entries' reasons in its
 * stage.yaml: the removal is 409 in_use with an error {field upcomingBookings, detail N} and the sheet's "<name> has N
 * upcoming bookings. Move or cancel them first."; the save is 409 in_use with one error {field services, detail <service
 * name>} per service and "This person has upcoming bookings for <services>. Move them first." The test ids are those of
 * qa/web/F06/S01-team-screens.spec.ts. Bookings are made through F07's endpoints. Registered under F06-S01 at F07's
 * feature-verify, when the deferred entries move to automation.
 */
import { refused } from '../../shared/booking-rules';
import { bookingSalon, holdInput, performs } from '../../shared/booking';
import { id, openAs, test, expect } from '../../shared/booking-browser';
import { serviceInput } from '../../shared/catalogue';
import { plusDays } from '../../shared/salon-hours';
import { teamSnapshot, updateOf } from '../../shared/team';
import { TEAM_ROUTE, card } from '../../shared/team-browser';
import { message } from '../../shared/strings';

test.describe.configure({ timeout: 180_000 });

/** F06-S01's test ids (qa/web/F06/S01-team-screens.spec.ts). */
const T = { cardEdit: 'team-card-edit', remove: 'team-sheet-remove', removeConfirm: 'team-remove-confirm', save: 'team-sheet-save', problem: 'team-sheet-problem',
  category: 'team-picker-row', categoryChoose: 'team-picker-choose', serviceChip: 'team-picker-service' } as const;
const errorsOf = (problem: unknown) => ((problem as { errors?: { field?: string; detail?: string }[] }).errors ?? []).map(e => ({ field: e.field, detail: e.detail }));
/** F06's words with the values put in (ICU plural as en.json writes it). */
const removal = (name: string, count: number) => message('team.notices.inUseRemoval').replace('{name}', name)
  .replace(/\{count, plural, one \{# upcoming booking\} other \{# upcoming bookings\}\}/, `${count} upcoming booking${count === 1 ? '' : 's'}`);

test('TC-F06-S01-011 AC-7: removing a person with upcoming bookings is 409 in_use naming how many, changes nothing, and the sheet says "<name> has N upcoming bookings. Move or cancel them first."', async ({ accounts, identity, operator, clock }) => {
  const s = await bookingSalon(identity, operator, clock, { people: 1 });
  const [person] = s.members;
  await s.bookings.confirm(s.salon, s.owner.tokens, (await s.bookings.place(s.salon, s.owner.tokens, holdInput(person.id, s.service.id, plusDays(s.today, 2), '10:00'))).id);
  await s.bookings.place(s.salon, s.owner.tokens, holdInput(person.id, s.service.id, plusDays(s.today, 3), '10:00'));
  const before = teamSnapshot(s.created.id);
  const current = await s.team.get(s.salon, s.owner.tokens, person.id);
  const { problem } = await refused(await s.team.removeRaw(s.salon, s.owner.tokens, person.id, current.version), 409, 'in_use', 'remove');
  expect(errorsOf(problem), 'two upcoming bookings').toEqual([{ field: 'upcomingBookings', detail: '2' }]);
  const { page } = await openAs(accounts, s.owner, TEAM_ROUTE);
  await card(page, person.id).getByTestId(T.cardEdit).click();
  await id(page, T.remove).click();
  await id(page, T.removeConfirm).click();
  await expect(page.getByText(removal(person.name, 2))).toBeVisible();
  expect(teamSnapshot(s.created.id), 'nothing changed').toBe(before);
});

test('TC-F06-S01-012 AC-8: a save that stops a person doing services they have upcoming bookings for is 409 in_use with one error per service naming it, changes nothing, and the sheet says "This person has upcoming bookings for <services>. Move them first."', async ({ accounts, identity, operator, clock }) => {
  const s = await bookingSalon(identity, operator, clock, { people: 1 });
  const [person] = s.members;
  const colour = await s.catalogue.create(s.salon, s.owner.tokens, serviceInput(s.category.id, { name: 'Root touch-up', durationMinutes: 30, bufferMinutes: 0 }));
  await performs(s, person, [s.service.id, colour.id]);
  await s.bookings.place(s.salon, s.owner.tokens, holdInput(person.id, s.service.id, plusDays(s.today, 2), '10:00'));
  await s.bookings.confirm(s.salon, s.owner.tokens, (await s.bookings.place(s.salon, s.owner.tokens, holdInput(person.id, colour.id, plusDays(s.today, 2), '14:00'))).id);
  const before = teamSnapshot(s.created.id);
  const current = await s.team.get(s.salon, s.owner.tokens, person.id);
  const { problem } = await refused(await s.team.saveRaw(s.salon, s.owner.tokens, person.id, updateOf(current, { services: { categoryIds: [], serviceIds: [] } })), 409, 'in_use', 'save');
  const errors = errorsOf(problem);
  expect(errors.map(e => e.field), 'one error per service').toEqual(['services', 'services']);
  expect(errors.map(e => e.detail).sort(), 'each names its service').toEqual([s.service.name, colour.name].sort());
  expect(teamSnapshot(s.created.id), 'nothing changed').toBe(before);

  const { page } = await openAs(accounts, s.owner, TEAM_ROUTE);
  await card(page, person.id).getByTestId(T.cardEdit).click();
  const row = page.locator(`[data-testid="${T.category}"][data-id="${s.category.id}"]`).filter({ visible: true });
  await row.getByTestId(T.categoryChoose).click();
  for (const serviceId of [s.service.id, colour.id]) await row.locator(`[data-testid="${T.serviceChip}"][data-id="${serviceId}"]`).click();
  await id(page, T.save).click();
  const words = message('team.notices.inUseServices');
  await expect(page.getByText(new RegExp(`^${words.split('{services}')[0]}.*${words.split('{services}')[1].replace('.', '\\.')}$`))).toBeVisible();
  for (const name of [s.service.name, colour.name]) await expect(page.getByText(new RegExp(words.split('{services}')[0])), `names ${name}`).toContainText(name);
  expect(teamSnapshot(s.created.id), 'nothing changed by the sheet').toBe(before);
});
