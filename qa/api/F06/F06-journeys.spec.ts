/**
 * F06's journeys across its stages through the API, from the README's "Feature-level test focus": an operator outside a
 * support session reaches nothing of a person's profile, week, date changes, leave or blocks; and removing a person
 * takes everything of theirs out of reach together, frees their seat and leaves their account, with their rows kept for
 * past bookings until salon erasure. The Owner's journey through the screens is
 * qa/web/F06/journey-a-person-and-their-time.spec.ts.
 *
 * Each asserts what passes between the stages, not each stage's rules, which are the stages' cases. Prepared before
 * F06-S03 closed: its names are in `PENDING_F06_S03`.
 */
import { refused } from '../../shared/booking-rules';
import type { Tokens } from '../../shared/identity';
import { test, expect, type OperatorActor } from '../../shared/operator';
import { plusDays } from '../../shared/salon-hours';
import { MEMBERS, SIGN_IN_ACCOUNTS, Team, memberInput, memberPath, memberPhotoPath, memberRow, teamSalon, teamSnapshot, updateOf } from '../../shared/team';
import { Absences, absenceRoutes, absenceSnapshot, blockInput, leaveInput, salonDate } from '../../shared/team-leave';
import { Schedules, changeInput, changeRoutes, offChange, schedulePath, weekOf } from '../../shared/team-schedule';
import { pngUpload, grayPng } from '../../shared/salon-brand';

test.describe.configure({ timeout: 180_000 });

test('TC-F06-002 STF-01, STF-02, STF-03, STF-05: an operator Admin or Support account outside a support session can neither read nor change a salon\'s team, week, date changes, leave or blocks, by any route of the feature', async ({ identity, operator }) => {
  const team = new Team(identity);
  const schedules = new Schedules(team);
  const absences = new Absences(team);
  const { salon, owner, created } = await teamSalon(operator, identity);
  const person = await team.add(salon, owner.tokens, memberInput({ name: 'Operator Must Not Read' }));
  const today = await salonDate(identity);
  const week = await schedules.save(salon, owner.tokens, person.id, weekOf('09:00', '18:00'));
  const note = 'QA operator must not read this';
  const change = (await schedules.add(salon, owner.tokens, person.id, changeInput(plusDays(today, 2), { note }))).change;
  const leave = (await absences.addLeave(salon, owner.tokens, person.id, leaveInput(plusDays(today, 5), { type: 'sick', note }))).leave;
  const block = (await absences.addBlock(salon, owner.tokens, person.id, blockInput(plusDays(today, 6), { label: note }))).block;
  const free = plusDays(today, 20);
  const schedule = { version: week.version, today, weekly: week.weekly, dateChanges: [change] };
  const member = await team.get(salon, owner.tokens, person.id);
  const routes: { label: string; method: string; path: string; data?: unknown }[] = [
    { label: 'the team', method: 'GET', path: MEMBERS },
    { label: 'a member', method: 'GET', path: memberPath(person.id) },
    { label: 'add a member', method: 'POST', path: MEMBERS, data: memberInput() },
    { label: 'edit a member', method: 'PUT', path: memberPath(person.id), data: updateOf(member, { title: 'Reached' }) },
    { label: 'remove a member', method: 'DELETE', path: `${memberPath(person.id)}?version=${member.version}` },
    { label: 'remove a photo', method: 'DELETE', path: memberPhotoPath(person.id) },
    { label: 'the sign-in accounts', method: 'GET', path: SIGN_IN_ACCOUNTS },
    { label: 'the schedule', method: 'GET', path: schedulePath(person.id) },
    ...changeRoutes(person.id, schedule, change, free),
    ...absenceRoutes(person.id, leave, block, free).all,
  ];
  const snapshot = () => [teamSnapshot(created.id), absenceSnapshot(created.id)].join('/');
  const before = snapshot();
  const actors: [string, OperatorActor][] = [['Admin', await operator.actor()], ['Support', await operator.actor('OPERATOR_SUPPORT')]];
  for (const [who, actor] of actors) {
    await test.step(`${who} outside a support session`, async () => {
      for (const route of routes) {
        const r = await team.call(salon, route.method, route.path, actor.tokens as Tokens, route.data);
        const body = await r.text();
        expect([401, 403, 404], `${who} ${route.label} (${r.status()} ${body.slice(0, 160)})`).toContain(r.status());
        expect(JSON.parse(body).code, `${who} ${route.label}: a refusal`).toMatch(/^(invalid_session|permission_denied|not_found)$/);
        for (const secret of [note, 'Operator Must Not Read']) expect(body, `${who} ${route.label}: nothing of the salon`).not.toContain(secret);
      }
    });
  }
  expect(snapshot(), 'nothing changed').toBe(before);
  await team.dispose();
});

test('TC-F06-003 STF-01, STF-02, STF-03, STF-05: removing a person with a sign-in, a photo, a week, a date change, leave and a block takes all of it out of reach together: the account stays free to link, their rows stay for past bookings, and someone added again under their name starts clean', async ({ identity, operator }) => {
  const team = new Team(identity);
  const schedules = new Schedules(team);
  const absences = new Absences(team);
  const { salon, owner, created } = await teamSalon(operator, identity);
  const today = await salonDate(identity);
  const account = await team.createAccount(salon, owner.tokens);
  const person = await team.add(salon, owner.tokens, memberInput({ name: 'Nimal Perera', signInAccountId: account.id }));
  const other = await team.add(salon, owner.tokens, memberInput({ name: 'Stays On' }));
  const pictured = await team.upload(salon, owner.tokens, person.id, pngUpload(grayPng(320, 320, { value: 90 }), 'small.png'));
  const week = await schedules.save(salon, owner.tokens, person.id, weekOf('09:00', '18:00'));
  const change = (await schedules.add(salon, owner.tokens, person.id, offChange(plusDays(today, 2), 'Private'))).change;
  const leave = (await absences.addLeave(salon, owner.tokens, person.id, leaveInput(plusDays(today, 5), { type: 'sick' }))).leave;
  const block = (await absences.addBlock(salon, owner.tokens, person.id, blockInput(plusDays(today, 6)))).block;
  const seats = await team.usage(salon, owner.tokens);

  await team.remove(salon, owner.tokens, await team.get(salon, owner.tokens, person.id));

  await test.step('nothing of theirs can be read or changed', async () => {
    const free = plusDays(today, 20);
    const schedule = { version: week.version, today, weekly: week.weekly, dateChanges: [change] };
    const before = [teamSnapshot(created.id), absenceSnapshot(created.id)].join('/');
    await refused(await team.getRaw(salon, owner.tokens, person.id), 404, 'not_found', 'the member');
    await refused(await team.call(salon, 'GET', schedulePath(person.id), owner.tokens), 404, 'not_found', 'the schedule');
    for (const route of [...changeRoutes(person.id, schedule, change, free), ...absenceRoutes(person.id, leave, block, free).all]) {
      await refused(await team.call(salon, route.method, route.path, owner.tokens, route.data), 404, 'not_found', route.label);
    }
    expect([teamSnapshot(created.id), absenceSnapshot(created.id)].join('/'), 'nothing saved for someone who has left').toBe(before);
    expect((await team.list(salon, owner.tokens)).map(m => m.id), 'the list').toEqual([other.id]);
    expect((await team.photo(salon.hostname, pictured.photoId!)).status(), 'their photo').toBe(404);
  });
  await test.step('their account stays, free to link, and counts as one seat as it did', async () => {
    const listed = (await team.accounts(salon, owner.tokens)).find(a => a.id === account.id);
    expect(listed?.memberId ?? null, 'free').toBeNull();
    expect(await team.usage(salon, owner.tokens), 'a linked person took no seat beyond the account').toBe(seats);
  });
  await test.step('their id and name stay stored for past bookings', async () => {
    expect(memberRow(created.id, person.id), 'the tombstone').toContain('Nimal Perera');
  });
  await test.step('someone added under the same name starts clean', async () => {
    const again = await team.add(salon, owner.tokens, memberInput({ name: 'Nimal Perera' }));
    expect(again.id, 'a separate person').not.toBe(person.id);
    const read = await absences.read(salon, owner.tokens, again.id);
    expect([read.dateChanges.length, read.leave.length, read.blockedTime.length], 'none of their time').toEqual([0, 0, 0]);
    expect(again.signInAccountId ?? null, 'nor their sign-in').toBeNull();
  });
  await test.step('the person who stays is untouched', async () => {
    const read = await absences.read(salon, owner.tokens, other.id);
    expect([read.leave.length, read.blockedTime.length]).toEqual([0, 0]);
  });
  await team.dispose();
});
