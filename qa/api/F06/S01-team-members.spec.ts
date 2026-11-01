/**
 * F06-S01 team members from outside: adding and editing with every field boundary and the limit of
 * 200, a person's services stored as whole categories and single services and worked out when read,
 * the optional sign-in link, removal as a tombstone, photos, the public team list and what it must
 * never show, each role, a support session, suspended and read-only salons, tenant isolation through
 * the API and at the database, and the audit trail.
 *
 * Expected results are the F06-S01 spec's (acceptance criteria, "API and data changes", "Design
 * notes", "Corrections at close") and decisions 099 to 101. Seats and everything sent at once are in
 * S01-seats-and-races.spec.ts. The names the build gives are in `PENDING_F06_S01` (qa/shared/team.ts),
 * confirmed at the dry run (c9b2d57).
 *
 * Not automated here (`verification.deferred` of the stage):
 * - cases 011 and 012, AC-7's and AC-8's refusals while an upcoming booking needs the person. No
 *   booking exists before F07, `UpcomingMemberBookings` answers "none" until then, and the spec has
 *   the developer's integration test prove the refusal with an implementation that reports one.
 * - case 013, AC-4's `TeamMembers`, a Java interface with no route, which the developer's integration
 *   tests call; its promise that a removed member's name stays is proved here at the database (009).
 */
import { randomUUID } from 'node:crypto';

import { readOnlySalon } from '../../shared/billing';
import { refused } from '../../shared/booking-rules';
import { Catalogue, comparable, marker, serviceInput, type Service } from '../../shared/catalogue';
import { Customers } from '../../shared/customer-identity';
import { webUrl } from '../../shared/env';
import { type Role, type Tokens } from '../../shared/identity';
import { identitySql } from '../../shared/identity-storage';
import { fixture, grayPng, pngUpload, readJpeg, type FileUpload } from '../../shared/salon-brand';
import { test, expect, alpha, beta, gamma, afterNewestPlan, operatorJson, sqlLiteral, supportJson, type SupportStart } from '../../shared/operator';
import {
  LIMITS, MEMBERS, PENDING_F06_S01, Team, auditOf, memberInput, memberPath, memberPhotoPath, memberRow, publicKeys, refusedOn, teamRows, teamSalon,
  teamSnapshot, teamTables, updateOf, SIGN_IN_ACCOUNTS, type MemberInput, type TeamMember,
} from '../../shared/team';
import { salonUrl, type Salon } from '../../shared/tenants';

test.describe.configure({ timeout: 120_000 });

const chars = (n: number, letter = 'a') => `${letter.toUpperCase()}${letter.repeat(n - 1)}`;
const sorted = (ids: string[]) => [...ids].sort();
const unknownHost = () => `qa-unknown-${randomUUID().slice(0, 8)}.localhost`;
const PHOTO = () => fixture('logo-700x600-banded.png');
const SMALL_PHOTO = () => pngUpload(grayPng(320, 320, { value: 90 }), 'small.png');
const servicesOf = (categoryIds: string[] = [], serviceIds: string[] = []) => ({ services: { categoryIds, serviceIds } });

/** Two services in Colour and one in Nails, all bookable online. */
async function arrangeServices(cat: Catalogue, salon: Salon, tokens: Tokens) {
  const colour = await cat.category(salon, tokens, 'Colour');
  const nails = await cat.category(salon, tokens, 'Nails');
  const s1 = await cat.create(salon, tokens, serviceInput(colour.id, { name: 'Root touch-up' }));
  const s2 = await cat.create(salon, tokens, serviceInput(colour.id, { name: 'Balayage' }));
  const n1 = await cat.create(salon, tokens, serviceInput(nails.id, { name: 'Gel polish' }));
  return { colour, nails, s1, s2, n1 };
}

/** Every endpoint that changes a member, with a body that would succeed for the member given. */
function changeRoutes(member: TeamMember) {
  return [
    { label: 'add', method: 'POST', path: MEMBERS, data: memberInput() as unknown },
    { label: 'edit', method: 'PUT', path: memberPath(member.id), data: updateOf(member, { title: `Edited ${marker()}` }) as unknown },
    { label: 'remove photo', method: 'DELETE', path: memberPhotoPath(member.id), data: undefined },
    { label: 'remove', method: 'DELETE', path: `${memberPath(member.id)}?version=${member.version}`, data: undefined },
  ];
}

/** The read endpoints that need a sign-in. */
const readRoutes = (member: TeamMember) => [
  { label: 'list', path: MEMBERS },
  { label: 'read one', path: memberPath(member.id) },
  { label: 'list accounts', path: SIGN_IN_ACCOUNTS },
];

// ---------------------------------------------------------------- AC-1 adding a person

test('TC-F06-S01-001 AC-1: an Owner adds a person with every field, the name trimmed; they read back as sent, two people may share a name, the defaults are shown online, no services and no sign-in, and a salon starts with no one', async ({ identity, operator }) => {
  const team = new Team(identity);
  const cat = new Catalogue(identity);
  const { salon, owner } = await teamSalon(operator, identity);
  expect(await team.list(salon, owner.tokens), 'a new salon starts with no team members').toEqual([]);
  for (const fixed of [alpha, beta, gamma]) {
    expect(identitySql(`SELECT count(*) FROM ${PENDING_F06_S01.memberTable} WHERE tenant_id = ${sqlLiteral(fixed.id)}`), `${fixed.slug}: no existing salon gained a member`).toBe('0');
  }
  const { colour, n1 } = await arrangeServices(cat, salon, owner.tokens);
  const person = await team.add(salon, owner.tokens, {
    name: '  Nimal Perera  ', title: 'Colour Specialist', highlights: 'Balayage • Highlights', bio: 'Twelve years with colour.', shownOnline: false,
    ...servicesOf([colour.id], [n1.id]), signInAccountId: null,
  });
  expect(person).toMatchObject({ name: 'Nimal Perera', title: 'Colour Specialist', highlights: 'Balayage • Highlights', bio: 'Twelve years with colour.', shownOnline: false, photoUrl: null, photoId: null });
  expect(person.services).toEqual({ categoryIds: [colour.id], serviceIds: [n1.id] });
  expect(person.signInAccountId ?? null, 'no sign-in').toBeNull();
  expect(await team.get(salon, owner.tokens, person.id), 'the same on reading it back').toEqual(person);
  expect((await team.list(salon, owner.tokens)).map(m => m.id), 'listed').toEqual([person.id]);
  await test.step('only a name: the defaults', async () => {
    const plain = await team.add(salon, owner.tokens, { name: 'Ab' });
    expect(plain.shownOnline, 'shown for online booking, on by default').toBe(true);
    expect(plain.services, 'services may be empty').toEqual({ categoryIds: [], serviceIds: [] });
    expect(plain.signInAccountId ?? null, 'none by default').toBeNull();
    expect(plain.title ?? '').toBe('');
    expect(plain.highlights ?? '').toBe('');
    expect(plain.bio ?? '').toBe('');
  });
  await test.step('two people may share a name', async () => {
    const twin = await team.add(salon, owner.tokens, { name: 'Nimal Perera' });
    expect(twin.id, 'a separate person').not.toBe(person.id);
    expect((await team.list(salon, owner.tokens)).filter(m => m.name === 'Nimal Perera').length).toBe(2);
  });
  await team.dispose();
  await cat.dispose();
});

test('TC-F06-S01-002 AC-1: every field boundary, when adding and when editing: the longest allowed are saved, and a name of 1 or 41, a title of 41, highlights of 61, a bio of 141 or the wrong type are a 400 on that field that saves nothing', async ({ identity, operator }) => {
  const team = new Team(identity);
  const { salon, owner, created } = await teamSalon(operator, identity);
  await test.step('the longest of each', async () => {
    const longest = await team.add(salon, owner.tokens, { name: chars(40), title: chars(LIMITS.title, 't'), highlights: chars(LIMITS.highlights, 'h'), bio: chars(LIMITS.bio, 'b') });
    expect([longest.name.length, longest.title?.length, longest.highlights?.length, longest.bio?.length]).toEqual([40, 40, 60, 140]);
    expect((await team.add(salon, owner.tokens, { name: chars(2) })).name, 'a name of two').toBe(chars(2));
  });
  const existing = await team.add(salon, owner.tokens);
  const rows: [string, string, MemberInput][] = [
    ['empty name', 'name', { name: '' }], ['spaces only', 'name', { name: '    ' }], ['a name of 1', 'name', { name: 'A' }], ['one after trimming', 'name', { name: '  A  ' }],
    ['a name of 41', 'name', { name: chars(41) }], ['a name that is not text', 'name', { name: 42 }], ['no name', 'name', { name: null }],
    ['a title of 41', 'title', { title: chars(41) }], ['a title that is not text', 'title', { title: 42 }],
    ['highlights of 61', 'highlights', { highlights: chars(61) }], ['highlights that are not text', 'highlights', { highlights: 42 }],
    ['a bio of 141', 'bio', { bio: chars(141) }], ['a bio that is not text', 'bio', { bio: {} }],
    ['an online setting that is not true or false', 'shownOnline', { shownOnline: 'yes' }],
  ];
  for (const [label, field, input] of rows) {
    await test.step(label, async () => {
      const before = teamSnapshot(created.id);
      await refusedOn(await team.addRaw(salon, owner.tokens, memberInput(input)), field, `add: ${label}`);
      await refusedOn(await team.saveRaw(salon, owner.tokens, existing.id, updateOf(existing, input)), field, `edit: ${label}`);
      expect(teamSnapshot(created.id), `${label}: nothing saved`).toBe(before);
    });
  }
  await team.dispose();
});

test('TC-F06-S01-003 AC-1: a salon has at most 200 team members: of eight added at once at 199 one succeeds, the 201st is 409 limit_reached saving nothing, and removing someone makes room', async ({ identity, operator }) => {
  test.setTimeout(240_000);
  const team = new Team(identity);
  const { salon, owner, created } = await teamSalon(operator, identity, 250);
  for (let n = 1; n < LIMITS.members; n++) await team.add(salon, owner.tokens, { name: `Person ${String(n).padStart(3, '0')}` });
  expect((await team.list(salon, owner.tokens)).length, 'one place left').toBe(LIMITS.members - 1);
  const responses = await Promise.all(Array.from({ length: 8 }, (_, n) => team.addRaw(salon, owner.tokens, { name: `Racer ${n}` })));
  expect(responses.filter(r => r.status() === 201).length, `one takes the last place (answers ${responses.map(r => r.status()).join(', ')})`).toBe(1);
  for (const loser of responses.filter(r => r.status() !== 201)) await refused(loser, 409, 'limit_reached', 'a loser');
  const full = await team.list(salon, owner.tokens);
  expect(full.length, 'read back: the list holds all 200').toBe(LIMITS.members);
  const before = teamSnapshot(created.id);
  await refused(await team.addRaw(salon, owner.tokens, memberInput()), 409, 'limit_reached', 'the 201st');
  expect(teamSnapshot(created.id), 'nothing saved').toBe(before);
  await team.remove(salon, owner.tokens, full[0]);
  expect((await team.add(salon, owner.tokens, { name: 'After a removal' })).name, 'a removed person leaves the count').toBe('After a removal');
  await team.dispose();
});

// ---------------------------------------------------------------- AC-2 editing

test('TC-F06-S01-004 AC-2: an edit saves and raises the version; a save that changes nothing is 409 invalid_state and a stale version is 409 conflict, both changing nothing', async ({ identity, operator }) => {
  const team = new Team(identity);
  const { salon, owner, created } = await teamSalon(operator, identity);
  const person = await team.add(salon, owner.tokens, { name: 'Sanduni' });
  const edited = await team.save(salon, owner.tokens, person, { name: 'Sanduni Silva', title: 'Director', shownOnline: false });
  expect(edited).toMatchObject({ name: 'Sanduni Silva', title: 'Director', shownOnline: false });
  expect(edited.version, 'a new version').not.toBe(person.version);
  expect(await team.get(salon, owner.tokens, person.id), 'read back').toEqual(edited);
  await test.step('a save that changes nothing', async () => {
    const before = teamSnapshot(created.id);
    await refused(await team.saveRaw(salon, owner.tokens, person.id, updateOf(edited)), 409, 'invalid_state', 'no change');
    expect(teamSnapshot(created.id), 'nothing changed').toBe(before);
  });
  await test.step('a stale version', async () => {
    const before = teamSnapshot(created.id);
    await refused(await team.saveRaw(salon, owner.tokens, person.id, updateOf(person, { title: 'Stale' })), 409, 'conflict', 'stale');
    expect(teamSnapshot(created.id), 'nothing changed').toBe(before);
    expect((await team.get(salon, owner.tokens, person.id)).title).toBe('Director');
  });
  await team.dispose();
});

// ---------------------------------------------------------------- AC-3 services

test('TC-F06-S01-005 AC-3: a person\'s services are worked out when read: a whole category covers a service added later and a single choice does not, a moved service follows the category rules at once, and a deleted one disappears', async ({ identity, operator }) => {
  const team = new Team(identity);
  const cat = new Catalogue(identity);
  const { salon, owner } = await teamSalon(operator, identity);
  const { colour, nails, s1, s2, n1 } = await arrangeServices(cat, salon, owner.tokens);
  const whole = await team.add(salon, owner.tokens, memberInput({ name: 'Whole Colour', ...servicesOf([colour.id]) }));
  const single = await team.add(salon, owner.tokens, memberInput({ name: 'Single s2', ...servicesOf([], [s2.id]) }));
  const both = await team.add(salon, owner.tokens, memberInput({ name: 'Nails and s1', ...servicesOf([nails.id], [s1.id]) }));
  expect(whole.services, 'stored as sent').toEqual({ categoryIds: [colour.id], serviceIds: [] });
  expect(single.services).toEqual({ categoryIds: [], serviceIds: [s2.id] });
  expect(both.services).toEqual({ categoryIds: [nails.id], serviceIds: [s1.id] });
  const performs = async (member: TeamMember) => sorted((await team.publicList(salon)).find(m => m.id === member.id)?.serviceIds ?? []);
  const expectAll = async (label: string, rows: [TeamMember, Service[]][]) => {
    for (const [member, services] of rows) {
      expect(await performs(member), `${label}: ${member.name}`).toEqual(sorted(services.map(s => s.id)));
    }
  };
  await expectAll('as saved', [[whole, [s1, s2]], [single, [s2]], [both, [n1, s1]]]);
  const s3 = await cat.create(salon, owner.tokens, serviceInput(colour.id, { name: 'Gloss' }));
  await expectAll('a service added to Colour later', [[whole, [s1, s2, s3]], [single, [s2]], [both, [n1, s1]]]);
  const movedS1 = await cat.update(salon, owner.tokens, s1, { categoryId: nails.id });
  await test.step('s1 moves into Nails: Colour loses it, Nails has it, and the person who has both performs it once', async () => {
    await expectAll('after the move', [[whole, [s2, s3]], [single, [s2]], [both, [n1, movedS1]]]);
    const seen = (await team.publicList(salon)).find(m => m.id === both.id)?.serviceIds ?? [];
    expect(new Set(seen).size, 'no service listed twice').toBe(seen.length);
  });
  await test.step('a single choice follows its service wherever it moves', async () => {
    await cat.update(salon, owner.tokens, s2, { categoryId: nails.id });
    await expectAll('after s2 moves', [[whole, [s3]], [single, [s2]]]);
  });
  await test.step('a deleted service disappears from whole categories and single choices', async () => {
    await cat.remove(salon, owner.tokens, await cat.service(salon, owner.tokens, s2.id));
    expect((await team.get(salon, owner.tokens, single.id)).services.serviceIds, 'not read back').toEqual([]);
    expect((await team.publicList(salon)).some(m => m.id === single.id), 'a person left with no service leaves the public list').toBe(false);
    await expectAll('after the delete', [[both, [n1, movedS1]]]);
  });
  await team.dispose();
  await cat.dispose();
});

test('TC-F06-S01-006 AC-3, AC-14: services that are not the salon\'s, or a single service inside a category also sent whole, are a 400 on services that saves nothing, when adding and when editing', async ({ identity, operator }) => {
  const team = new Team(identity);
  const cat = new Catalogue(identity);
  const { salon, owner, created } = await teamSalon(operator, identity);
  const other = await teamSalon(operator, identity);
  const { colour, s1 } = await arrangeServices(cat, salon, owner.tokens);
  const theirs = await arrangeServices(cat, other.salon, other.owner.tokens);
  const person = await team.add(salon, owner.tokens);
  const rows: [string, MemberInput][] = [
    ['an unknown category', servicesOf([randomUUID()])],
    ['an unknown service', servicesOf([], [randomUUID()])],
    ['another salon\'s category', servicesOf([theirs.colour.id])],
    ['another salon\'s service', servicesOf([], [theirs.s1.id])],
    ['a service id sent as a category', servicesOf([s1.id])],
    ['a category id sent as a service', servicesOf([], [colour.id])],
    ['a single service inside a category also sent whole', servicesOf([colour.id], [s1.id])],
    ['something that is not an id', servicesOf(['not-an-id'])],
  ];
  for (const [label, input] of rows) {
    await test.step(label, async () => {
      const before = teamSnapshot(created.id);
      await refusedOn(await team.addRaw(salon, owner.tokens, memberInput(input)), 'services', `add: ${label}`);
      await refusedOn(await team.saveRaw(salon, owner.tokens, person.id, updateOf(person, input)), 'services', `edit: ${label}`);
      expect(teamSnapshot(created.id), `${label}: nothing saved`).toBe(before);
    });
  }
  await team.dispose();
  await cat.dispose();
});

// ---------------------------------------------------------------- AC-5 the sign-in link

test('TC-F06-S01-007 AC-5: a person needs no account to be saved, listed and public; the accounts list names each active account and who has it; another salon\'s, a removed or an already linked account is a 400 on signInAccountId', async ({ identity, operator }) => {
  const team = new Team(identity);
  const cat = new Catalogue(identity);
  const { salon, owner, created } = await teamSalon(operator, identity);
  const other = await teamSalon(operator, identity);
  const { colour } = await arrangeServices(cat, salon, owner.tokens);
  const person = await team.add(salon, owner.tokens, memberInput({ name: 'No Sign-in', ...servicesOf([colour.id]) }));
  await test.step('no account: saved, listed and on the public list', async () => {
    expect(person.signInAccountId ?? null).toBeNull();
    expect((await team.list(salon, owner.tokens)).map(m => m.id)).toEqual([person.id]);
    expect((await team.publicList(salon)).map(m => m.id)).toEqual([person.id]);
  });
  const account = await team.createAccount(salon, owner.tokens);
  const removedAccount = await team.createAccount(salon, owner.tokens);
  await team.removeAccount(salon, owner.tokens, removedAccount.id);
  await test.step('the accounts list', async () => {
    const list = await team.accounts(salon, owner.tokens);
    expect(sorted(list.map(a => a.id)), 'the active accounts only').toEqual(sorted([owner.principal.id, account.id]));
    const mine = list.find(a => a.id === account.id)!;
    expect(mine).toMatchObject({ label: account.email, role: account.role, memberId: null });
  });
  const linked = await team.link(salon, owner.tokens, person, account.id);
  await test.step('a link', async () => {
    expect(linked.signInAccountId).toBe(account.id);
    expect(linked.signInLabel, 'the staff list\'s label').toBe(account.email);
    expect((await team.accounts(salon, owner.tokens)).find(a => a.id === account.id)?.memberId, 'the account names the person').toBe(person.id);
    expect((await team.publicList(salon)).map(m => m.id), 'still public').toEqual([person.id]);
  });
  const second = await team.add(salon, owner.tokens);
  const rows: [string, string][] = [
    ['another salon\'s account', other.owner.principal.id],
    ['a removed account', removedAccount.id],
    ['an account already linked', account.id],
    ['an unknown account', randomUUID()],
  ];
  for (const [label, id] of rows) {
    await test.step(label, async () => {
      const before = teamSnapshot(created.id);
      await refusedOn(await team.addRaw(salon, owner.tokens, memberInput({ signInAccountId: id })), 'signInAccountId', `add: ${label}`);
      await refusedOn(await team.saveRaw(salon, owner.tokens, second.id, updateOf(second, {}, id)), 'signInAccountId', `edit: ${label}`);
      expect(teamSnapshot(created.id), `${label}: nothing saved`).toBe(before);
    });
  }
  await team.dispose();
  await cat.dispose();
});

test('TC-F06-S01-008 AC-5: removing a person leaves their account active and free to link; removing an account leaves the person on the team with no sign-in, in its stored row as well', async ({ identity, operator }) => {
  const team = new Team(identity);
  const { salon, owner, created } = await teamSalon(operator, identity);
  const account = await team.createAccount(salon, owner.tokens);
  const first = await team.add(salon, owner.tokens, memberInput({ signInAccountId: account.id }));
  await test.step('removing the person', async () => {
    await team.remove(salon, owner.tokens, first);
    expect((await identity.list(owner)).find(a => a.id === account.id)?.active, 'the account stays active').toBe(true);
    expect((await team.accounts(salon, owner.tokens)).find(a => a.id === account.id)?.memberId ?? null, 'and free').toBeNull();
  });
  const second = await team.add(salon, owner.tokens, memberInput({ signInAccountId: account.id }));
  expect(second.signInAccountId, 'free to link').toBe(account.id);
  expect(memberRow(created.id, second.id), 'the stored link, to prove the next step clears something').toContain(account.id);
  await test.step('removing the account', async () => {
    await team.removeAccount(salon, owner.tokens, account.id);
    const after = await team.get(salon, owner.tokens, second.id);
    expect(after.signInAccountId ?? null, 'on the team with no sign-in').toBeNull();
    expect(after.signInLabel ?? null, 'and no label').toBeNull();
    expect(memberRow(created.id, second.id), 'the stored link is cleared').not.toContain(account.id);
    expect((await team.list(salon, owner.tokens)).map(m => m.id), 'still on the team').toEqual([second.id]);
  });
  await team.dispose();
});

// ---------------------------------------------------------------- AC-4, AC-7, AC-8 removal

test('TC-F06-S01-009 AC-4, AC-7: a removed person leaves the list, the public list and every endpoint, and their photo\'s URL answers 404 at once, while their id and name stay stored for past bookings; a stale version is 409 conflict', async ({ identity, operator }) => {
  const team = new Team(identity);
  const cat = new Catalogue(identity);
  const { salon, owner, created } = await teamSalon(operator, identity);
  const { colour } = await arrangeServices(cat, salon, owner.tokens);
  const person = await team.add(salon, owner.tokens, memberInput({ name: 'Leaving Soon', ...servicesOf([colour.id]) }));
  expect((await team.publicList(salon)).map(m => m.id), 'public before').toEqual([person.id]);
  const edited = await team.upload(salon, owner.tokens, (await team.save(salon, owner.tokens, person, { title: 'Now edited' })).id, SMALL_PHOTO());
  const onWeb = () => identity.api.send('GET', `${salonUrl(salon, webUrl)}${PENDING_F06_S01.webPhotoPath(edited.photoId!)}`);
  expect((await team.photo(salon.hostname, edited.photoId!)).status(), 'the photo is served before').toBe(200);
  expect((await onWeb()).status(), 'also through the web').toBe(200);
  await test.step('a stale version changes nothing', async () => {
    const before = teamSnapshot(created.id);
    await refused(await team.removeRaw(salon, owner.tokens, person.id, person.version), 409, 'conflict', 'a stale removal');
    expect(teamSnapshot(created.id), 'nothing changed').toBe(before);
  });
  await team.remove(salon, owner.tokens, edited);
  expect(await team.list(salon, owner.tokens), 'not listed').toEqual([]);
  expect(await team.publicList(salon), 'not public').toEqual([]);
  expect((await team.photo(salon.hostname, edited.photoId!)).status(), 'their photo answers 404 at once').toBe(404);
  expect((await onWeb()).status(), 'also through the web').toBe(404);
  expect(teamRows(created.id).staff_member_photo, 'and its bytes are gone').toBe(0);
  await refused(await team.getRaw(salon, owner.tokens, person.id), 404, 'not_found', 'reading them');
  await refused(await team.saveRaw(salon, owner.tokens, person.id, updateOf(edited, { title: 'After removal' })), 404, 'not_found', 'editing them');
  await refused(await team.removeRaw(salon, owner.tokens, person.id, edited.version), 404, 'not_found', 'removing them again');
  const stored = memberRow(created.id, person.id);
  expect(stored, 'their stored row keeps the id').toContain(person.id);
  expect(stored, 'and the name, which TeamMembers returns for past bookings').toContain('Leaving Soon');
  await team.dispose();
  await cat.dispose();
});

test('TC-F06-S01-010 AC-7, AC-8: with no upcoming booking a save that stops a person performing a service and a removal both go through (the refusals need a booking, cases 011 and 012)', async ({ identity, operator }) => {
  const team = new Team(identity);
  const cat = new Catalogue(identity);
  const { salon, owner } = await teamSalon(operator, identity);
  const { colour, nails } = await arrangeServices(cat, salon, owner.tokens);
  const person = await team.add(salon, owner.tokens, memberInput(servicesOf([colour.id, nails.id])));
  const dropped = await team.save(salon, owner.tokens, person, servicesOf([nails.id]));
  expect(dropped.services.categoryIds, 'Colour dropped').toEqual([nails.id]);
  await team.remove(salon, owner.tokens, dropped);
  expect(await team.list(salon, owner.tokens)).toEqual([]);
  await team.dispose();
  await cat.dispose();
});

// Cases 011 (AC-7), 012 (AC-8) and 013 (AC-4) are deferred to F07: stage.yaml, verification.deferred.

// ---------------------------------------------------------------- AC-9 photos

test('TC-F06-S01-014 AC-9: a PNG, JPEG or WebP of at least 320 x 320 is kept as a square JPEG of at most 800 without metadata, served to anyone on the salon\'s hostname, and replaced by the next', async ({ identity, operator }) => {
  const team = new Team(identity);
  const { salon, owner } = await teamSalon(operator, identity);
  const person = await team.add(salon, owner.tokens);
  const rows: { label: string; file: () => FileUpload; side: number }[] = [
    { label: 'a 700 x 600 PNG: its 600 x 600 centre', file: PHOTO, side: 600 },
    { label: 'a 2000 x 800 JPEG with camera and GPS data: 800 x 800 without them', file: () => fixture('cover-2000x800-exif.jpg'), side: 800 },
    { label: 'a 1000 x 400 WebP: 400 x 400', file: () => fixture('cover-1000x400.webp'), side: 400 },
    { label: 'the smallest, 320 x 320', file: SMALL_PHOTO, side: 320 },
    { label: 'a wrong declared type does not matter when the content is a PNG', file: () => fixture('logo-700x600-banded.png', 'application/pdf', 'photo.pdf'), side: 600 },
  ];
  let previous: string | null = null;
  for (const row of rows) {
    await test.step(row.label, async () => {
      const updated = await team.upload(salon, owner.tokens, person.id, row.file());
      const id = updated.photoId!;
      expect(id, 'a new photo replaces the last').not.toBe(previous);
      const served = await team.photo(salon.hostname, id);
      expect(served.status(), 'served to anyone on the salon\'s hostname').toBe(200);
      expect((served.headers()['content-type'] ?? '').split(';')[0]).toBe('image/jpeg');
      const body = await served.body();
      const jpeg = readJpeg(body);
      expect([jpeg.width, jpeg.height], 'a square of the expected side').toEqual([row.side, row.side]);
      expect(jpeg.markers.filter(m => m === 0xe1 || m === 0xfe), 'no Exif (APP1) or comment segment').toEqual([]);
      for (const text of ['QA-CAMERA', 'QA-SOFTWARE', 'QA-JPEG-COMMENT', 'QA-PNG-', 'GPS']) expect(body.toString('latin1'), text).not.toContain(text);
      if (previous) expect((await team.photo(salon.hostname, previous)).status(), 'the replaced photo answers 404').toBe(404);
      previous = id;
    });
  }
  await team.dispose();
});

test('TC-F06-S01-015 AC-9: a file that is not PNG, JPEG or WebP by content, is over 5 MB, is under 320 pixels on a side, is undecodable or is missing is refused on file, and the person keeps their photo', async ({ identity, operator }) => {
  test.setTimeout(180_000);
  const team = new Team(identity);
  const { salon, owner, created } = await teamSalon(operator, identity);
  const person = await team.add(salon, owner.tokens);
  const withPhoto = await team.upload(salon, owner.tokens, person.id, SMALL_PHOTO());
  const kept = await (await team.photo(salon.hostname, withPhoto.photoId!)).body();
  const big = grayPng(400, 400, { padding: 6 * 1024 * 1024 });
  expect(big.length, 'a valid PNG over 5 MB').toBeGreaterThan(LIMITS.photoBytes);
  const rows: { label: string; file: FileUpload | null }[] = [
    { label: 'text named .png and declared image/png', file: fixture('not-an-image.txt', 'image/png', 'photo.png') },
    { label: 'an SVG declared image/png', file: fixture('logo-300.svg', 'image/png', 'photo.png') },
    { label: 'a GIF', file: fixture('logo-300.gif') },
    { label: 'a BMP renamed .jpg', file: fixture('logo-300.bmp', 'image/jpeg', 'photo.jpg') },
    { label: 'a truncated PNG', file: fixture('logo-truncated.png') },
    { label: 'a 300 x 300 WebP', file: fixture('logo-300.webp') },
    { label: '319 x 320', file: pngUpload(grayPng(319, 320)) },
    { label: '320 x 319', file: pngUpload(grayPng(320, 319)) },
    { label: 'a valid PNG of 6 MB', file: pngUpload(big) },
    { label: 'a PNG declaring 60000 x 60000 pixels', file: pngUpload(grayPng(16, 16, { declared: [60000, 60000] })) },
    { label: 'an empty file', file: pngUpload(Buffer.alloc(0), 'empty.png') },
    { label: 'no file part', file: null },
  ];
  const wrong: string[] = [];
  for (const row of rows) {
    await test.step(row.label, async () => {
      const before = teamSnapshot(created.id);
      const r = await team.uploadRaw(salon, owner.tokens, person.id, row.file);
      const body = await r.text();
      let fields: string[] = [];
      try { fields = ((JSON.parse(body) as { errors?: { field: string }[] }).errors ?? []).map(e => e.field); } catch { /* not problem details */ }
      if (r.status() !== 400 || !body.includes('"bad_request"') || fields.length === 0 || !fields.every(f => /(^|\.)file$/.test(f))) wrong.push(`${row.label}: ${r.status()} ${body.slice(0, 160)}`);
      if (teamSnapshot(created.id) !== before) wrong.push(`${row.label}: something was stored`);
    });
  }
  expect(wrong, 'every refused file is a 400 on its field that stores nothing').toEqual([]);
  const after = await team.get(salon, owner.tokens, person.id);
  expect(after.photoId, 'the previous photo is kept').toBe(withPhoto.photoId);
  expect(Buffer.compare(await (await team.photo(salon.hostname, after.photoId!)).body(), kept), 'and served as it was').toBe(0);
  expect((await identity.api.health()).status(), 'the API is healthy after the hostile files').toBe(200);
  await team.dispose();
});

test('TC-F06-S01-016 AC-9, AC-14: a photo is served on its own salon\'s hostname by the API and through the web, answers as an unknown photo on another salon\'s or an unknown hostname, and Remove makes its URL answer 404', async ({ identity, operator }) => {
  const team = new Team(identity);
  const cat = new Catalogue(identity);
  const a = await teamSalon(operator, identity);
  const b = await teamSalon(operator, identity);
  const { colour } = await arrangeServices(cat, a.salon, a.owner.tokens);
  const person = await team.add(a.salon, a.owner.tokens, memberInput(servicesOf([colour.id])));
  const withPhoto = await team.upload(a.salon, a.owner.tokens, person.id, SMALL_PHOTO());
  const id = withPhoto.photoId!;
  const bytes = await (await team.photo(a.salon.hostname, id)).body();
  await test.step('through the web on the salon\'s hostname', async () => {
    const web = await identity.api.send('GET', `${salonUrl(a.salon, webUrl)}${PENDING_F06_S01.webPhotoPath(id)}`);
    expect(web.status(), 'the BFF serves it').toBe(200);
    expect((web.headers()['content-type'] ?? '').split(';')[0]).toBe('image/jpeg');
    expect(Buffer.compare(await web.body(), bytes), 'the same image').toBe(0);
    expect((await team.publicList(a.salon))[0].photoUrl, 'the public list gives its URL').toContain(id);
  });
  await test.step('another salon\'s and an unknown hostname', async () => {
    for (const host of [b.salon.hostname, unknownHost()]) {
      const r = await team.photo(host, id);
      const unknown = await team.photo(host, randomUUID());
      expect(r.status(), `${host}: not found`).toBe(404);
      expect(comparable(await r.text()), `${host}: the same as an unknown id`).toBe(comparable(await unknown.text()));
    }
    const webOther = await identity.api.send('GET', `${salonUrl(b.salon, webUrl)}${PENDING_F06_S01.webPhotoPath(id)}`);
    expect(webOther.status(), 'not through the other salon\'s web').toBe(404);
  });
  await test.step('Remove', async () => {
    const r = await team.removePhotoRaw(a.salon, a.owner.tokens, person.id);
    expect([200, 204], `remove photo (${r.status()})`).toContain(r.status());
    const after = await team.get(a.salon, a.owner.tokens, person.id);
    expect([after.photoId, after.photoUrl], 'no photo').toEqual([null, null]);
    expect((await team.photo(a.salon.hostname, id)).status(), 'its URL answers 404').toBe(404);
    expect((await team.publicList(a.salon))[0].photoUrl, 'the public list says null').toBeNull();
  });
  await team.dispose();
  await cat.dispose();
});

test('TC-F06-S01-017 AC-9: a caller without permission or a wrong target gets the same answer for an oversized file as for a small one, and a file over the limit is refused on file storing nothing; the developer\'s tests prove the order inside', async ({ identity, operator }) => {
  test.setTimeout(180_000);
  const team = new Team(identity);
  const big = () => pngUpload(grayPng(400, 400, { padding: 6 * 1024 * 1024 }), 'big.png');
  expect(big().buffer.length, 'over the 5 MB limit').toBeGreaterThan(LIMITS.photoBytes);
  const { created, salon, owner } = await teamSalon(operator, identity);
  const other = await teamSalon(operator, identity);
  const person = await team.add(salon, owner.tokens);
  const foreign = await team.add(other.salon, other.owner.tokens);
  const receptionist = await identity.staff(salon, 'RECEPTIONIST');
  const support = await operator.actor('OPERATOR_SUPPORT');
  const session = await supportJson<SupportStart>(await operator.startSupport(support, created.id, { reason: 'QA F06 support cannot upload a photo' }), 'supportStart', 201);
  const supportTokens: Tokens = await operator.supportCredential(await operator.exchange(session.entryCode, salon.hostname));
  const rows: [string, number, string, Tokens | undefined, string][] = [
    ['signed out', 401, 'invalid_session', undefined, person.id],
    ['a Receptionist', 403, 'permission_denied', receptionist.tokens, person.id],
    ['a support session', 403, 'support_read_only', supportTokens, person.id],
    ['an unknown person', 404, 'not_found', owner.tokens, randomUUID()],
    ['another salon\'s person', 404, 'not_found', owner.tokens, foreign.id],
  ];
  for (const [label, status, code, tokens, target] of rows) {
    await test.step(label, async () => {
      const before = teamSnapshot(created.id);
      const small = await refused(await team.uploadRaw(salon, tokens, target, SMALL_PHOTO()), status, code, `${label}: a small file`);
      const large = await team.uploadRaw(salon, tokens, target, big());
      await refused(large, status, code, `${label}: an oversized file`);
      expect(comparable(await large.text()), `${label}: the same answer`).toBe(comparable(small.answer.body));
      expect(teamSnapshot(created.id), `${label}: nothing stored`).toBe(before);
    });
  }
  const kept = await team.upload(salon, owner.tokens, person.id, SMALL_PHOTO());
  const before = teamSnapshot(created.id);
  await refusedOn(await team.uploadRaw(salon, owner.tokens, person.id, big()), PENDING_F06_S01.photoField, 'over the limit, with permission');
  expect(teamSnapshot(created.id), 'nothing stored').toBe(before);
  expect((await team.get(salon, owner.tokens, person.id)).photoId, 'the previous photo is kept').toBe(kept.photoId);
  await team.dispose();
});

// ---------------------------------------------------------------- AC-10 the public list

test('TC-F06-S01-018 AC-10: the public list needs no sign-in and gives, in name order, exactly the people shown online who perform a service bookable online, with those services\' ids and no others', async ({ identity, operator }) => {
  const team = new Team(identity);
  const cat = new Catalogue(identity);
  const { salon, owner } = await teamSalon(operator, identity);
  const { colour, nails, s1, s2, n1 } = await arrangeServices(cat, salon, owner.tokens);
  const hiddenService = await cat.create(salon, owner.tokens, serviceInput(colour.id, { name: 'Hidden treatment', bookableOnline: false }));
  const waxing = await cat.category(salon, owner.tokens, 'Waxing');
  const onlyHidden = await cat.create(salon, owner.tokens, serviceInput(waxing.id, { name: 'Only hidden', bookableOnline: false }));
  // Added out of order: the list must sort them itself.
  const chamara = await team.add(salon, owner.tokens, memberInput({ name: 'Chamara', title: 'Director', highlights: 'Bridal', bio: 'Bridal colour.', ...servicesOf([colour.id]) }));
  const amal = await team.add(salon, owner.tokens, memberInput({ name: 'amal', title: null, highlights: null, bio: null, ...servicesOf([], [n1.id]) }));
  const bimal = await team.upload(salon, owner.tokens, (await team.add(salon, owner.tokens, memberInput({ name: 'Bimal', ...servicesOf([nails.id], [s1.id]) }))).id, SMALL_PHOTO());
  await team.add(salon, owner.tokens, memberInput({ name: 'Dilan, hidden', shownOnline: false, ...servicesOf([colour.id]) }));
  await team.add(salon, owner.tokens, memberInput({ name: 'Eranga, no services' }));
  await team.add(salon, owner.tokens, memberInput({ name: 'Farah, hidden service only', ...servicesOf([waxing.id]) }));
  const list = await team.publicList(salon);
  expect(list.map(m => m.name), 'name order ignoring case, those who qualify and no others').toEqual(['amal', 'Bimal', 'Chamara']);
  for (const item of list) expect(Object.keys(item).sort(), `${item.name}: exactly the public fields`).toEqual(publicKeys);
  const by = (name: string) => list.find(m => m.name === name)!;
  expect(by('amal').id).toBe(amal.id);
  expect(by('amal').photoUrl, 'no photo is null').toBeNull();
  expect(sorted(by('amal').serviceIds)).toEqual([n1.id]);
  expect(by('Bimal').id).toBe(bimal.id);
  expect(by('Bimal').photoUrl, 'a photo has its URL').toContain(bimal.photoId!);
  expect(sorted(by('Bimal').serviceIds)).toEqual(sorted([n1.id, s1.id]));
  expect(by('Chamara')).toMatchObject({ id: chamara.id, title: 'Director', highlights: 'Bridal', bio: 'Bridal colour.' });
  expect(sorted(by('Chamara').serviceIds), 'only the services bookable online').toEqual(sorted([s1.id, s2.id]));
  expect(by('Chamara').serviceIds).not.toContain(hiddenService.id);
  expect(list.flatMap(m => m.serviceIds)).not.toContain(onlyHidden.id);
  await team.dispose();
  await cat.dispose();
});

test('TC-F06-S01-019 AC-10, AC-13: the public list shows nothing about a sign-in account, a version, a hidden or a removed person, for a visitor or a signed-in Owner', async ({ identity, operator }) => {
  const team = new Team(identity);
  const cat = new Catalogue(identity);
  const { salon, owner } = await teamSalon(operator, identity);
  const { colour } = await arrangeServices(cat, salon, owner.tokens);
  const account = await team.createAccount(salon, owner.tokens);
  const linked = await team.add(salon, owner.tokens, memberInput({ name: 'Visible Linked', ...servicesOf([colour.id]), signInAccountId: account.id }));
  const hidden = await team.add(salon, owner.tokens, memberInput({ name: 'Hidden Zzyzx', title: 'Hidden Title Qwerty', shownOnline: false, ...servicesOf([colour.id]) }));
  const removed = await team.add(salon, owner.tokens, memberInput({ name: 'Removed Xyzzy', ...servicesOf([colour.id]) }));
  await team.remove(salon, owner.tokens, removed);
  await test.step('what must not leak exists on the staff side', async () => {
    const staffSide = await team.list(salon, owner.tokens);
    expect(staffSide.find(m => m.id === linked.id)?.signInAccountId, 'the link is there').toBe(account.id);
    expect(staffSide.some(m => m.id === hidden.id), 'the hidden person is there').toBe(true);
  });
  const answers = [
    { label: 'a visitor', response: await team.publicRaw(salon.hostname) },
    { label: 'a signed-in Owner', response: await team.call(salon, 'GET', '/api/v1/team/public', owner.tokens) },
  ];
  for (const { label, response } of answers) {
    await test.step(label, async () => {
      expect(response.status()).toBe(200);
      const text = await response.text();
      const items = (JSON.parse(text) as { items: { id: string }[] }).items;
      expect(items.map(m => m.id), 'the visible person is there, so the scan has something to scan').toEqual([linked.id]);
      for (const [what, value] of [
        ['the account id', account.id], ['the account email', account.email], ['the account mobile', account.mobile], ['the Owner\'s id', owner.principal.id],
        ['a hidden person\'s id', hidden.id], ['a hidden person\'s name', 'Hidden Zzyzx'], ['a hidden person\'s title', 'Hidden Title Qwerty'],
        ['a removed person\'s id', removed.id], ['a removed person\'s name', 'Removed Xyzzy'],
        ['a sign-in field', 'signIn'], ['a version', '"version"'], ['the online setting', 'shownOnline'], ['a salon id', salon.id],
      ] as [string, string][]) {
        if (value) expect(text, `${label}: no ${what}`).not.toContain(value);
      }
    });
  }
  await team.dispose();
  await cat.dispose();
});

test('TC-F06-S01-020 AC-10, AC-14: each salon\'s public list is its own, also when requests for two salons are sent together; an unknown, a suspended and a closed salon answer as the salon\'s public brand does', async ({ identity, operator }) => {
  const team = new Team(identity);
  const cat = new Catalogue(identity);
  const a = await teamSalon(operator, identity);
  const b = await teamSalon(operator, identity);
  const inA = await arrangeServices(cat, a.salon, a.owner.tokens);
  const inB = await arrangeServices(cat, b.salon, b.owner.tokens);
  const ma = await team.add(a.salon, a.owner.tokens, memberInput({ name: `Alpha only ${marker()}`, ...servicesOf([inA.colour.id]) }));
  const mb = await team.add(b.salon, b.owner.tokens, memberInput({ name: `Beta only ${marker()}`, ...servicesOf([inB.colour.id]) }));
  expect((await team.publicList(a.salon)).map(m => m.id)).toEqual([ma.id]);
  expect((await team.publicList(b.salon)).map(m => m.id)).toEqual([mb.id]);
  await test.step('twenty requests for the two salons at once never cross', async () => {
    const order = Array.from({ length: 20 }, (_, n) => (n % 2 ? b : a));
    const answers = await Promise.all(order.map(x => team.publicRaw(x.salon.hostname).then(r => r.text())));
    answers.forEach((text, n) => {
      const own = order[n] === a ? ma : mb;
      const other = order[n] === a ? mb : ma;
      expect(text, `request ${n}: its own person`).toContain(own.id);
      expect(text, `request ${n}: nothing of the other salon`).not.toContain(other.id);
      expect(text).not.toContain(other.name);
    });
  });
  const asBrand = async (host: string, label: string) => {
    const list = await team.publicRaw(host);
    const brand = await identity.api.getOn(host, '/api/v1/salon');
    expect(list.status(), `${label}: the brand's status`).toBe(brand.status());
    expect(comparable(await list.text()), `${label}: the brand's answer`).toBe(comparable(await brand.text()));
    return list.status();
  };
  expect(await asBrand(unknownHost(), 'an unknown salon')).toBe(404);
  await test.step('suspended', async () => {
    await operatorJson(await operator.suspend(b.admin, b.created.id, await operator.version(b.admin, b.created.id)), 'salon');
    expect(await asBrand(b.salon.hostname, 'suspended')).toBe(403);
    await refused(await team.publicRaw(b.salon.hostname), 403, 'salon_suspended', 'suspended list');
  });
  await test.step('closed', async () => {
    await operatorJson(await operator.close(a.admin, a.created.id, await operator.version(a.admin, a.created.id), a.created.slug), 'salon');
    expect(await asBrand(a.salon.hostname, 'closed')).toBe(404);
  });
  await team.dispose();
  await cat.dispose();
});

// ---------------------------------------------------------------- AC-13 roles and salon states

test('TC-F06-S01-021 AC-13: each role, on every endpoint: the Owner and Manager read and change; the Receptionist and Professional read with no sign-in details and are refused every change and the accounts list; the Accountant, a customer and a signed-out caller are refused everything; each refusal changes nothing', async ({ identity, operator }) => {
  test.setTimeout(240_000);
  const team = new Team(identity);
  const { salon, owner, created } = await teamSalon(operator, identity);
  const account = await team.createAccount(salon, owner.tokens);
  const person = await team.upload(salon, owner.tokens, (await team.add(salon, owner.tokens, memberInput({ signInAccountId: account.id }))).id, SMALL_PHOTO());
  const roles: Role[] = ['MANAGER', 'RECEPTIONIST', 'PROFESSIONAL', 'ACCOUNTANT'];
  const actors: { role: Role; tokens: Tokens }[] = [{ role: 'OWNER', tokens: owner.tokens }];
  for (const role of roles) actors.push({ role, tokens: (await identity.staff(salon, role)).tokens });
  /** Refused with the same code everywhere: reads, the accounts list, changes and a photo upload. */
  const refusedAll = async (label: string, status: number, code: string, tokens: Tokens | undefined, reads: { label: string; path: string }[]) => {
    const current = await team.get(salon, owner.tokens, person.id);
    for (const route of reads) await refused(await team.call(salon, 'GET', route.path, tokens), status, code, `${label} ${route.label}`);
    for (const route of changeRoutes(current)) {
      const before = teamSnapshot(created.id);
      await refused(await team.call(salon, route.method, route.path, tokens, route.data), status, code, `${label} ${route.label}`);
      expect(teamSnapshot(created.id), `${label} ${route.label}: nothing changed`).toBe(before);
    }
    const before = teamSnapshot(created.id);
    await refused(await team.uploadRaw(salon, tokens, person.id, SMALL_PHOTO()), status, code, `${label} upload a photo`);
    expect(teamSnapshot(created.id), `${label} upload: nothing changed`).toBe(before);
  };
  for (const { role, tokens } of actors) {
    await test.step(role, async () => {
      if (role === 'ACCOUNTANT') { await refusedAll(role, 403, 'permission_denied', tokens, readRoutes(person)); return; }
      const listed = (await team.list(salon, tokens)).find(m => m.id === person.id)!;
      const read = await team.get(salon, tokens, person.id);
      expect(read, `${role}: the list and a read agree`).toEqual(listed);
      if (role === 'RECEPTIONIST' || role === 'PROFESSIONAL') {
        for (const field of ['signInAccountId', 'signInLabel']) expect(read, `${role}: ${field} is omitted`).not.toHaveProperty(field);
        await refused(await team.accountsRaw(salon, tokens), 403, 'permission_denied', `${role} accounts list`);
        await refusedAll(role, 403, 'permission_denied', tokens, []);
        return;
      }
      expect(read.signInAccountId, `${role}: sees the link`).toBe(account.id);
      expect(read.signInLabel, `${role}: and its label`).toBe(account.email);
      expect((await team.accounts(salon, tokens)).some(a => a.id === account.id), `${role}: reads the accounts`).toBe(true);
      const own = await team.add(salon, tokens, memberInput({ name: `QA ${role}` }));
      const edited = await team.save(salon, tokens, own, { title: 'Edited' });
      const pictured = await team.upload(salon, tokens, edited.id, SMALL_PHOTO());
      expect([200, 204]).toContain((await team.removePhotoRaw(salon, tokens, pictured.id)).status());
      await team.remove(salon, tokens, await team.get(salon, tokens, pictured.id));
    });
  }
  await test.step('a customer', async () => {
    const customer = await new Customers(identity).login(salon);
    await refusedAll('customer', 403, 'permission_denied', customer.tokens, readRoutes(person));
  });
  await test.step('signed out', async () => { await refusedAll('signed out', 401, 'invalid_session', undefined, readRoutes(person)); });
  await test.step('the public list and a photo need no sign-in', async () => {
    expect((await team.publicRaw(salon.hostname)).status()).toBe(200);
    expect((await team.photo(salon.hostname, person.photoId!)).status()).toBe(200);
  });
  await team.dispose();
});

test('TC-F06-S01-022 AC-13: a support session reads the team as the Owner does, including sign-in details, cannot list the accounts, and every change is 403 support_read_only, changing nothing', async ({ identity, operator }) => {
  const team = new Team(identity);
  const { created, salon, owner } = await teamSalon(operator, identity);
  const account = await team.createAccount(salon, owner.tokens);
  const person = await team.add(salon, owner.tokens, memberInput({ signInAccountId: account.id }));
  const support = await operator.actor('OPERATOR_SUPPORT');
  const session = await supportJson<SupportStart>(await operator.startSupport(support, created.id, { reason: 'QA F06 support reads the team' }), 'supportStart', 201);
  const tokens: Tokens = await operator.supportCredential(await operator.exchange(session.entryCode, salon.hostname));
  expect(await team.list(salon, tokens), 'the same team').toEqual(await team.list(salon, owner.tokens));
  expect(await team.get(salon, tokens, person.id)).toEqual(await team.get(salon, owner.tokens, person.id));
  await refused(await team.accountsRaw(salon, tokens), 403, 'permission_denied', 'the accounts list needs team.manage');
  for (const route of changeRoutes(person)) {
    const before = teamSnapshot(created.id);
    await refused(await team.call(salon, route.method, route.path, tokens, route.data), 403, 'support_read_only', `support ${route.label}`);
    expect(teamSnapshot(created.id), `support ${route.label}: nothing changed`).toBe(before);
  }
  await team.dispose();
});

test('TC-F06-S01-023 AC-13: a suspended salon answers 403 salon_suspended on every team endpoint, and a read-only salon reads, serves its public list and photos and refuses every change with salon_read_only', async ({ identity, operator, clock }) => {
  test.setTimeout(240_000);
  const team = new Team(identity);
  const cat = new Catalogue(identity);
  await clock.set(afterNewestPlan());
  await test.step('suspended', async () => {
    const { admin, created, salon, owner } = await teamSalon(operator, identity);
    const person = await team.upload(salon, owner.tokens, (await team.add(salon, owner.tokens)).id, SMALL_PHOTO());
    await operatorJson(await operator.suspend(admin, created.id, await operator.version(admin, created.id)), 'salon');
    const before = teamSnapshot(created.id);
    for (const route of readRoutes(person)) await refused(await team.call(salon, 'GET', route.path, owner.tokens), 403, 'salon_suspended', `suspended ${route.label}`);
    for (const route of changeRoutes(person)) await refused(await team.call(salon, route.method, route.path, owner.tokens, route.data), 403, 'salon_suspended', `suspended ${route.label}`);
    await refused(await team.publicRaw(salon.hostname), 403, 'salon_suspended', 'suspended public list');
    await refused(await team.photo(salon.hostname, person.photoId!), 403, 'salon_suspended', 'suspended photo');
    await refused(await team.uploadRaw(salon, owner.tokens, person.id, SMALL_PHOTO()), 403, 'salon_suspended', 'suspended upload');
    expect(teamSnapshot(created.id), 'nothing changed').toBe(before);
  });
  await test.step('read-only', async () => {
    const { admin, created, salon, owner } = await teamSalon(operator, identity);
    const { colour } = await arrangeServices(cat, salon, owner.tokens);
    const person = await team.upload(salon, owner.tokens, (await team.add(salon, owner.tokens, memberInput(servicesOf([colour.id])))).id, SMALL_PHOTO());
    await readOnlySalon(operator, clock, admin, created.id);
    const now = await identity.login(salon, owner.account);
    expect((await team.list(salon, now.tokens)).map(m => m.id), 'still readable').toEqual([person.id]);
    expect((await team.publicList(salon)).map(m => m.id), 'customers still read the list').toEqual([person.id]);
    expect((await team.photo(salon.hostname, person.photoId!)).status(), 'and the photo').toBe(200);
    const before = teamSnapshot(created.id);
    for (const route of changeRoutes(person)) await refused(await team.call(salon, route.method, route.path, now.tokens, route.data), 403, 'salon_read_only', `read-only ${route.label}`);
    await refused(await team.uploadRaw(salon, now.tokens, person.id, SMALL_PHOTO()), 403, 'salon_read_only', 'read-only upload');
    expect(teamSnapshot(created.id), 'nothing changed').toBe(before);
  });
  await team.dispose();
  await cat.dispose();
});

// ---------------------------------------------------------------- AC-14 tenant isolation

test('TC-F06-S01-024 AC-14: signed in to one salon, another salon\'s member and photo are "not found" to every read and change exactly as unknown ids are, and are never listed; another salon\'s staff reach nothing', async ({ identity, operator }) => {
  test.setTimeout(180_000);
  const team = new Team(identity);
  const a = await teamSalon(operator, identity);
  const b = await teamSalon(operator, identity);
  const theirs = await team.upload(b.salon, b.owner.tokens, (await team.add(b.salon, b.owner.tokens, memberInput({ name: `Theirs ${marker()}` }))).id, SMALL_PHOTO());
  const mine = await team.add(a.salon, a.owner.tokens, memberInput({ name: `Mine ${marker()}` }));
  expect((await team.get(b.salon, b.owner.tokens, theirs.id)).id, 'the other salon reads its own member').toBe(theirs.id);
  expect((await team.list(b.salon, b.owner.tokens)).map(m => m.id), 'the other salon has a record to hide').toEqual([theirs.id]);
  const aBefore = teamSnapshot(a.created.id);
  const bBefore = teamSnapshot(b.created.id);
  await test.step('every route with their ids answers as with unknown ids', async () => {
    const none = { ...theirs, id: randomUUID() };
    const paired = [
      { label: 'read', method: 'GET', theirs: memberPath(theirs.id), unknown: memberPath(none.id), data: undefined as unknown },
      { label: 'edit', method: 'PUT', theirs: memberPath(theirs.id), unknown: memberPath(none.id), data: updateOf(theirs, { title: 'Reached across' }) },
      { label: 'remove photo', method: 'DELETE', theirs: memberPhotoPath(theirs.id), unknown: memberPhotoPath(none.id), data: undefined },
      { label: 'remove', method: 'DELETE', theirs: `${memberPath(theirs.id)}?version=${theirs.version}`, unknown: `${memberPath(none.id)}?version=${theirs.version}`, data: undefined },
    ];
    for (const route of paired) {
      const reached = await team.call(a.salon, route.method, route.theirs, a.owner.tokens, route.data);
      const unknown = await team.call(a.salon, route.method, route.unknown, a.owner.tokens, route.data);
      await refused(reached, 404, 'not_found', `their id: ${route.label}`);
      expect(comparable(await reached.text()), `${route.label}: the same as an unknown id`).toBe(comparable(await unknown.text()));
    }
    await refused(await team.uploadRaw(a.salon, a.owner.tokens, theirs.id, SMALL_PHOTO()), 404, 'not_found', 'their id: upload a photo');
    expect((await team.photo(a.salon.hostname, theirs.photoId!)).status(), 'their photo on this hostname').toBe(404);
  });
  await test.step('lists never show them', async () => {
    const seen = (await team.list(a.salon, a.owner.tokens)).map(m => m.id);
    expect(seen, 'this salon\'s own only').toEqual([mine.id]);
    const text = await (await team.listRaw(a.salon, a.owner.tokens)).text();
    expect(text).not.toContain(theirs.name);
    expect((await team.accounts(a.salon, a.owner.tokens)).map(x => x.id), 'nor their accounts').toEqual([a.owner.principal.id]);
    expect(await (await team.publicRaw(a.salon.hostname)).text(), 'nor the public list').not.toContain(theirs.id);
  });
  await test.step('their Owner on this hostname', async () => {
    for (const route of readRoutes(mine)) await refused(await team.call(a.salon, 'GET', route.path, b.owner.tokens), 404, 'not_found', `their Owner: ${route.label}`);
    for (const route of changeRoutes(mine)) await refused(await team.call(a.salon, route.method, route.path, b.owner.tokens, route.data), 404, 'not_found', `their Owner: ${route.label}`);
  });
  expect(teamSnapshot(a.created.id), 'this salon changed in no way').toBe(aBefore);
  expect(teamSnapshot(b.created.id), 'the other salon changed in no way').toBe(bBefore);
  await team.dispose();
});

test('TC-F06-S01-025 AC-14: with no tenant context every team table returns no rows to the application role, a salon\'s context shows only its own, every row is on the salon\'s default branch, and the count function is the platform role\'s alone', async ({ identity, operator }) => {
  const team = new Team(identity);
  const cat = new Catalogue(identity);
  const a = await teamSalon(operator, identity);
  const b = await teamSalon(operator, identity);
  for (const x of [a, b]) {
    const { colour, n1 } = await arrangeServices(cat, x.salon, x.owner.tokens);
    await team.upload(x.salon, x.owner.tokens, (await team.add(x.salon, x.owner.tokens, memberInput(servicesOf([colour.id], [n1.id])))).id, SMALL_PHOTO());
  }
  const aRows = teamRows(a.created.id);
  const bRows = teamRows(b.created.id);
  expect(teamTables(), 'each table this stage adds is found').toEqual(expect.arrayContaining([PENDING_F06_S01.memberTable]));
  for (const table of teamTables()) {
    await test.step(table, async () => {
      expect(aRows[table], `${table}: the salon has rows`).toBeGreaterThan(0);
      expect(bRows[table], `${table}: the other salon has rows`).toBeGreaterThan(0);
      expect(identitySql(`SELECT count(*) FROM ${table}`, null), `${table}: no tenant, no rows`).toBe('0');
      expect(Number(identitySql(`SELECT count(*) FROM ${table}`, a.created.id)), `${table}: the salon sees its rows`).toBe(aRows[table]);
      expect(identitySql(`SELECT count(*) FROM ${table} WHERE tenant_id <> ${sqlLiteral(a.created.id)}`, a.created.id), `${table}: and no other salon's`).toBe('0');
      const offBranch = identitySql(`SELECT count(*) FROM ${table} x WHERE tenant_id = ${sqlLiteral(a.created.id)}
        AND branch_id IS DISTINCT FROM (SELECT id FROM branding_branch b WHERE b.tenant_id = x.tenant_id AND b.is_default)`);
      expect(offBranch, `${table}: every row carries the default branch`).toBe('0');
    });
  }
  await test.step('the count function', async () => {
    const asApplication = identitySql(`SELECT ${PENDING_F06_S01.countFunction}(${sqlLiteral(a.created.id)})`, a.created.id, false);
    expect(asApplication, 'the application role may not execute it').toMatch(/permission denied/i);
  });
  await team.dispose();
  await cat.dispose();
});

// ---------------------------------------------------------------- AC-15 audit

test('TC-F06-S01-026 AC-15: every change to a member is audited with the actor, the time and the before and after values, including services, the online setting, the link, its clearing with an account, photos and removal; a refused change is not', async ({ identity, operator }) => {
  const team = new Team(identity);
  const cat = new Catalogue(identity);
  const { salon, owner, created } = await teamSalon(operator, identity);
  const { colour, nails } = await arrangeServices(cat, salon, owner.tokens);
  const account = await team.createAccount(salon, owner.tokens);
  const t0 = await identity.now();
  let person!: TeamMember;
  let seen = 0;
  /** Runs a change and returns the details it wrote: at least one entry, by the Owner, inside the change's time. */
  const audited = async (label: string, change: () => Promise<unknown>) => {
    const from = await identity.now();
    await change();
    const to = await identity.now();
    const rows = auditOf(created.id, person.id, t0).slice(seen);
    seen += rows.length;
    expect(rows.length, `${label}: audited`).toBeGreaterThan(0);
    for (const row of rows) {
      expect(row.actorId, `${label}: the actor`).toBe(owner.principal.id);
      expect(Date.parse(row.at)).toBeGreaterThanOrEqual(Date.parse(from) - 1000);
      expect(Date.parse(row.at)).toBeLessThanOrEqual(Date.parse(to) + 1000);
    }
    return rows.map(r => r.details).join('\n');
  };
  const refreshed = async () => { person = await team.get(salon, owner.tokens, person.id); };
  expect(await audited('add', async () => { person = await team.add(salon, owner.tokens, memberInput({ name: 'Audit Person', title: 'Junior', ...servicesOf([colour.id]) })); }), 'names the person').toContain('Audit Person');
  const title = await audited('edit', async () => { person = await team.save(salon, owner.tokens, person, { title: 'Senior' }); });
  expect(title, 'before').toContain('Junior');
  expect(title, 'after').toContain('Senior');
  const services = await audited('services', async () => { person = await team.save(salon, owner.tokens, person, servicesOf([nails.id])); });
  expect(services, 'before').toContain(colour.id);
  expect(services, 'after').toContain(nails.id);
  await audited('online setting', async () => { person = await team.save(salon, owner.tokens, person, { shownOnline: false }); });
  expect(await audited('link', async () => { person = await team.link(salon, owner.tokens, person, account.id); }), 'the account').toContain(account.id);
  expect(await audited('unlink', async () => { person = await team.link(salon, owner.tokens, person, null); }), 'the account').toContain(account.id);
  await audited('add a photo', async () => { person = await team.upload(salon, owner.tokens, person.id, SMALL_PHOTO()); });
  await audited('remove the photo', async () => {
    expect([200, 204]).toContain((await team.removePhotoRaw(salon, owner.tokens, person.id)).status());
    await refreshed();
  });
  await audited('relink', async () => { person = await team.link(salon, owner.tokens, person, account.id); });
  expect(await audited('the link cleared by removing the account', async () => {
    await team.removeAccount(salon, owner.tokens, account.id);
    await refreshed();
  }), 'the account').toContain(account.id);
  await test.step('a refused change is not audited', async () => {
    await refused(await team.saveRaw(salon, owner.tokens, person.id, { ...updateOf(person, { title: 'Stale' }), version: person.version - 1 }), 409, 'conflict', 'stale');
    await refused(await team.saveRaw(salon, owner.tokens, person.id, updateOf(person)), 409, 'invalid_state', 'no change');
    expect(auditOf(created.id, person.id, t0).length, 'nothing more audited').toBe(seen);
  });
  expect(await audited('removal', () => team.remove(salon, owner.tokens, person)), 'names the person').toContain('Audit Person');
  await team.dispose();
  await cat.dispose();
});
