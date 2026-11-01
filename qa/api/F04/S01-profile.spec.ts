/**
 * F04-S01 the salon profile from outside: draft, publish and discard, validation for the
 * configured region, versions and races, roles, a support session, suspended and read-only
 * salons, tenant isolation through the API and at the database, the audit trail, and the default
 * branch of every salon.
 *
 * Expected results are the F04-S01 spec's (acceptance criteria, "API and data changes", "Design
 * notes"), docs/qa/test-types.md and the generated contract. Fresh salons come from the operator
 * (F03), so "before the first publication" is real; the fixed salons keep whatever earlier tests
 * published, so every test reads the current draft and works from its version.
 */
import { randomUUID } from 'node:crypto';

import { readOnlySalon } from '../../shared/billing';
import type { Identity, Role, Tokens } from '../../shared/identity';
import { identitySql } from '../../shared/identity-storage';
import {
  test, expect, alpha, beta, gamma, comparable, operatorJson, ownerSession, sqlLiteral, supportJson,
  type Operator, type SupportStart,
} from '../../shared/operator';
import { logOffsets, logsSince } from '../../shared/product-logs';
import {
  ENDPOINTS, PROFILE_TABLES, WRITES, Profiles, comparableProfile, e164, expectedProfile, fieldErrors, fullInput, mobile,
  profileAudit, profileRowCounts, profileSnapshot, refused, unique,
  type Endpoint, type ProfileDraft, type ProfileInput,
} from '../../shared/salon-profile';
import type { Salon } from '../../shared/tenants';

/** A salon the operator creates now, with its Owner signed in (MFA enrolled). */
async function newSalon(operator: Operator, identity: Identity) {
  const admin = await operator.actor();
  const plan = await operator.plan(admin);
  const { input, created, salon } = await operator.salon(admin, plan.id);
  const owner = await ownerSession(identity, salon, input);
  return { admin, created, salon, owner };
}

const EMPTY = (branchId: string) => ({
  description: null,
  links: { instagram: null, facebook: null, website: null },
  branch: { id: branchId, isDefault: true, address: null, location: null, phone: null, whatsapp: null },
});

const phonesOf = (input: ProfileInput) => ({
  phone: input.branches[0].phone ? e164(input.branches[0].phone) : null,
  whatsapp: input.branches[0].whatsapp ? e164(input.branches[0].whatsapp) : null,
});

/** The body each endpoint takes, for the version given. */
function bodyFor(endpoint: Endpoint, version: number, branchId: string): unknown {
  if (endpoint.name === 'read') return undefined;
  if (endpoint.name === 'save') return { version, profile: fullInput(branchId) };
  return { version };
}

test('TC-F04-S01-001 AC-3: a saved draft survives a re-read and the public salon keeps serving what was published before', async ({ identity, operator }) => {
  const profiles = new Profiles(identity);
  await test.step('before the first publication: the draft is kept and the public profile stays null', async () => {
    const { salon, owner } = await newSalon(operator, identity);
    const before = await profiles.draft(salon, owner.tokens);
    expect(before.hasUnpublishedChanges, 'a new salon has nothing unpublished').toBe(false);
    expect(before.publishedAt).toBeNull();
    expect(before.publishedBy).toBeNull();
    const branch = await profiles.defaultBranch(salon, owner.tokens);
    const input = fullInput(branch);
    const saved = await profiles.save(salon, owner.tokens, before.version, input);
    expect(saved.version, 'saving moves the version on').toBeGreaterThan(before.version);
    expect(saved.hasUnpublishedChanges).toBe(true);
    const reread = await profiles.draft(salon, owner.tokens);
    expect(reread.version).toBe(saved.version);
    expect(reread.hasUnpublishedChanges).toBe(true);
    expect(comparableProfile(reread.profile), 'the re-read draft holds the saved values').toEqual(expectedProfile(input, phonesOf(input)));
    expect((await profiles.published(salon)).profile, 'nothing is served before the first publication').toBeNull();
  });
  await test.step('after a publication: a new draft leaves the published profile as it was', async () => {
    const manager = await identity.staff(alpha, 'MANAGER');
    const { input: first } = await profiles.publishFresh(alpha, manager.tokens);
    const publishedBefore = (await profiles.published(alpha)).profile;
    expect(comparableProfile(publishedBefore), 'the earlier publication is served').toEqual(expectedProfile(first, phonesOf(first)));
    const current = await profiles.draft(alpha, manager.tokens);
    const second = fullInput(first.branches[0].id);
    await profiles.save(alpha, manager.tokens, current.version, second);
    const reread = await profiles.draft(alpha, manager.tokens);
    expect(reread.hasUnpublishedChanges).toBe(true);
    expect(comparableProfile(reread.profile)).toEqual(expectedProfile(second, phonesOf(second)));
    expect((await profiles.published(alpha)).profile, 'the public salon still serves the earlier publication').toEqual(publishedBefore);
  });
});

test('TC-F04-S01-002 AC-4: publishing serves exactly the saved values, complete or partial, and records who published', async ({ identity, operator }) => {
  const profiles = new Profiles(identity);
  await test.step('a complete profile on a new salon', async () => {
    const { salon, owner } = await newSalon(operator, identity);
    const before = await profiles.draft(salon, owner.tokens);
    const input = fullInput(await profiles.defaultBranch(salon, owner.tokens));
    const saved = await profiles.save(salon, owner.tokens, before.version, input);
    const t0 = Date.parse(await identity.now());
    const published = await profiles.publish(salon, owner.tokens, saved.version);
    expect(published.hasUnpublishedChanges, 'everything is published').toBe(false);
    expect(published.publishedBy, 'the publishing staff account').toBe(owner.principal.id);
    expect(Date.parse(published.publishedAt!), 'published now').toBeGreaterThanOrEqual(t0 - 1000);
    const served = (await profiles.published(salon)).profile;
    expect(comparableProfile(served), 'the public salon serves exactly the saved values').toEqual(expectedProfile(input, phonesOf(input)));
    expect(served, 'the published copy equals the draft at publication').toEqual(published.profile);
  });
  await test.step('a partial profile: unset fields are served as null', async () => {
    const manager = await identity.staff(alpha, 'MANAGER');
    const current = await profiles.draft(alpha, manager.tokens);
    const branch = await profiles.defaultBranch(alpha, manager.tokens);
    const input: ProfileInput = {
      description: unique('Partial profile'),
      links: { instagram: null, facebook: null, website: null },
      branches: [{ id: branch, address: null, location: null, phone: null, whatsapp: null }],
    };
    const saved = await profiles.save(alpha, manager.tokens, current.version, input);
    const published = await profiles.publish(alpha, manager.tokens, saved.version);
    expect(published.publishedBy).toBe(manager.principal.id);
    const served = (await profiles.published(alpha)).profile;
    expect(comparableProfile(served)).toEqual({ ...EMPTY(branch), description: input.description });
  });
});

test('TC-F04-S01-003 AC-4: publishing with nothing changed is 409 invalid_state and changes nothing', async ({ identity, operator }) => {
  const profiles = new Profiles(identity);
  await test.step('an empty profile on a new salon', async () => {
    const { salon, owner } = await newSalon(operator, identity);
    const draft = await profiles.draft(salon, owner.tokens);
    const before = profileSnapshot(salon.id);
    await refused(await profiles.publishRaw(salon, owner.tokens, draft.version), 409, 'invalid_state', 'empty profile');
    expect(profileSnapshot(salon.id), 'nothing changed').toBe(before);
    expect((await profiles.published(salon)).profile).toBeNull();
  });
  const manager = await identity.staff(alpha, 'MANAGER');
  const { input, draft } = await profiles.publishFresh(alpha, manager.tokens);
  await test.step('straight after a publication', async () => {
    const before = profileSnapshot(alpha.id);
    await refused(await profiles.publishRaw(alpha, manager.tokens, draft.version), 409, 'invalid_state', 'published again');
    expect(profileSnapshot(alpha.id), 'nothing changed').toBe(before);
    expect((await profiles.draft(alpha, manager.tokens)).publishedAt, 'the publication time is kept').toBe(draft.publishedAt);
  });
  await test.step('after saving the same values as are published', async () => {
    const same = await profiles.save(alpha, manager.tokens, draft.version, input);
    expect(same.hasUnpublishedChanges, 'the draft equals the published profile').toBe(false);
    const before = profileSnapshot(alpha.id);
    await refused(await profiles.publishRaw(alpha, manager.tokens, same.version), 409, 'invalid_state', 'same values');
    expect(profileSnapshot(alpha.id), 'nothing changed').toBe(before);
  });
});

test('TC-F04-S01-004 AC-5: discarding returns the draft to the published profile, or to empty, and never changes the published profile', async ({ identity, operator }) => {
  const profiles = new Profiles(identity);
  await test.step('before the first publication the draft empties', async () => {
    const { salon, owner } = await newSalon(operator, identity);
    const branch = await profiles.defaultBranch(salon, owner.tokens);
    const saved = await profiles.save(salon, owner.tokens, (await profiles.draft(salon, owner.tokens)).version, fullInput(branch));
    const discarded = await profiles.discard(salon, owner.tokens, saved.version);
    expect(discarded.hasUnpublishedChanges).toBe(false);
    expect(comparableProfile(discarded.profile)).toEqual(EMPTY(branch));
    expect(comparableProfile((await profiles.draft(salon, owner.tokens)).profile), 'the empty draft is stored').toEqual(EMPTY(branch));
    expect((await profiles.published(salon)).profile).toBeNull();
  });
  await test.step('after a publication the draft returns to it', async () => {
    const manager = await identity.staff(alpha, 'MANAGER');
    const { input, draft } = await profiles.publishFresh(alpha, manager.tokens);
    const servedBefore = (await profiles.published(alpha)).profile;
    const saved = await profiles.save(alpha, manager.tokens, draft.version, fullInput(input.branches[0].id));
    const discarded = await profiles.discard(alpha, manager.tokens, saved.version);
    expect(discarded.hasUnpublishedChanges).toBe(false);
    expect(comparableProfile(discarded.profile), 'the draft is the published profile again').toEqual(expectedProfile(input, phonesOf(input)));
    expect(discarded.publishedAt, 'the publication is untouched').toBe(draft.publishedAt);
    expect((await profiles.published(alpha)).profile, 'the public salon serves the same profile').toEqual(servedBefore);
  });
});

test('TC-F04-S01-005 AC-6: Sri Lankan numbers in any common form are stored once in E.164, and values at their limits are accepted', async ({ identity }) => {
  const profiles = new Profiles(identity);
  const manager = await identity.staff(alpha, 'MANAGER');
  const branch = await profiles.defaultBranch(alpha, manager.tokens);
  const save = async (override: Parameters<typeof fullInput>[1]) => {
    const current = await profiles.draft(alpha, manager.tokens);
    const saved = await profiles.save(alpha, manager.tokens, current.version, fullInput(branch, override));
    return comparableProfile(saved.profile)!;
  };
  const phones: [string, string][] = [
    ['077 123 4567', '+94771234567'], ['+94771234567', '+94771234567'], ['0771234567', '+94771234567'],
    ['0112504417', '+94112504417'], ['011 250 4417', '+94112504417'], ['+94 11 250 4417', '+94112504417'],
  ];
  for (const [typed, stored] of phones) {
    await test.step(`phone ${typed}`, async () => expect((await save({ branch: { phone: typed } })).branch!.phone).toBe(stored));
  }
  for (const [typed, stored] of phones.slice(0, 3)) {
    await test.step(`WhatsApp ${typed}`, async () => expect((await save({ branch: { whatsapp: typed } })).branch!.whatsapp).toBe(stored));
  }
  await test.step('lengths at their limits, trimmed', async () => {
    const address = 'A'.repeat(200);
    const description = 'D'.repeat(500);
    const website = `https://example.com/${'w'.repeat(200 - 'https://example.com/'.length)}`;
    const saved = await save({ branch: { address: `  ${address}  ` }, description: ` ${description} `,
      links: { instagram: null, facebook: null, website } });
    expect(saved.branch!.address).toBe(address);
    expect(saved.description).toBe(description);
    expect(saved.links.website).toBe(website);
  });
  for (const location of [{ latitude: 90, longitude: 180 }, { latitude: -90, longitude: -180 }, { latitude: 0, longitude: 0 }]) {
    await test.step(`map position ${location.latitude}, ${location.longitude}`, async () =>
      expect((await save({ branch: { location } })).branch!.location).toEqual(location));
  }
  await test.step('empty and blank text means "not set"', async () => {
    const saved = await save({ branch: { address: '   ', phone: '', whatsapp: null }, description: '',
      links: { instagram: '', facebook: '  ', website: null } });
    expect(saved.branch!.address).toBeNull();
    expect(saved.branch!.phone).toBeNull();
    expect(saved.description).toBeNull();
    expect(saved.links).toEqual({ instagram: null, facebook: null, website: null });
  });
});

test('TC-F04-S01-006 AC-6: an invalid number, a landline as WhatsApp, a position out of range, a non-https link or text over its length is a 400 on that field and saves nothing', async ({ identity }) => {
  const profiles = new Profiles(identity);
  const manager = await identity.staff(alpha, 'MANAGER');
  const branch = await profiles.defaultBranch(alpha, manager.tokens);
  const branchField = (leaf: string) => new RegExp(`branches(\\[0\\]|\\.0)\\.${leaf}(\\.(latitude|longitude))?$`);
  const linkField = (leaf: string) => new RegExp(`links\\.${leaf}$`);
  const rows: { label: string; input: unknown; field: RegExp }[] = [
    { label: 'phone too short', input: fullInput(branch, { branch: { phone: '12345' } }), field: branchField('phone') },
    { label: 'phone that is words', input: fullInput(branch, { branch: { phone: 'call the salon' } }), field: branchField('phone') },
    { label: 'phone with too many digits', input: fullInput(branch, { branch: { phone: '077 123 45678 99' } }), field: branchField('phone') },
    { label: 'WhatsApp that is a landline', input: fullInput(branch, { branch: { whatsapp: '0112504417' } }), field: branchField('whatsapp') },
    { label: 'WhatsApp that is not a number', input: fullInput(branch, { branch: { whatsapp: '0771' } }), field: branchField('whatsapp') },
    { label: 'latitude 91', input: fullInput(branch, { branch: { location: { latitude: 91, longitude: 79.8611 } } }), field: branchField('location') },
    { label: 'latitude -90.5', input: fullInput(branch, { branch: { location: { latitude: -90.5, longitude: 79.8611 } } }), field: branchField('location') },
    { label: 'longitude 181', input: fullInput(branch, { branch: { location: { latitude: 6.9, longitude: 181 } } }), field: branchField('location') },
    { label: 'longitude -180.01', input: fullInput(branch, { branch: { location: { latitude: 6.9, longitude: -180.01 } } }), field: branchField('location') },
    { label: 'coordinates as text', input: fullInput(branch, { branch: { location: { latitude: '6.9' as unknown as number, longitude: '79.8' as unknown as number } } }), field: branchField('location') },
    { label: 'Instagram over http', input: fullInput(branch, { links: { instagram: 'http://instagram.com/qa', facebook: null, website: null } }), field: linkField('instagram') },
    { label: 'Facebook with no scheme', input: fullInput(branch, { links: { instagram: null, facebook: 'facebook.com/qa', website: null } }), field: linkField('facebook') },
    { label: 'website with a script scheme', input: fullInput(branch, { links: { instagram: null, facebook: null, website: 'javascript:alert(1)' } }), field: linkField('website') },
    { label: 'website of 201 characters', input: fullInput(branch, { links: { instagram: null, facebook: null, website: `https://example.com/${'w'.repeat(201 - 20)}` } }), field: linkField('website') },
    { label: 'address of 201 characters', input: fullInput(branch, { branch: { address: 'A'.repeat(201) } }), field: branchField('address') },
    { label: 'description of 501 characters', input: fullInput(branch, { description: 'D'.repeat(501) }), field: /(^|\.)description$/ },
    { label: 'a branch that does not exist', input: fullInput(randomUUID()), field: branchField('id') },
  ];
  for (const row of rows) {
    await test.step(row.label, async () => {
      const current = await profiles.draft(alpha, manager.tokens);
      const before = profileSnapshot(alpha.id);
      const { fields } = await fieldErrors(await profiles.saveRaw(alpha, manager.tokens, current.version, row.input), row.label);
      expect(fields.length, `${row.label}: names the field`).toBeGreaterThan(0);
      for (const field of fields) expect(field, `${row.label}: the error is on that field`).toMatch(row.field);
      expect(profileSnapshot(alpha.id), `${row.label}: nothing saved`).toBe(before);
      expect((await profiles.draft(alpha, manager.tokens)).version, `${row.label}: the version did not move`).toBe(current.version);
    });
  }
});

test('TC-F04-S01-007 AC-7: a save, publish or discard sent with an older version is 409 conflict and changes nothing', async ({ identity }) => {
  const profiles = new Profiles(identity);
  const first = await identity.staff(alpha, 'MANAGER');
  const second = await identity.staff(alpha, 'MANAGER');
  const branch = await profiles.defaultBranch(alpha, first.tokens);
  const seen = await profiles.draft(alpha, second.tokens);
  const moved = await profiles.save(alpha, first.tokens, seen.version, fullInput(branch));
  expect(moved.hasUnpublishedChanges, 'there is something to publish, so only the version can refuse it').toBe(true);
  const before = profileSnapshot(alpha.id);
  for (const [label, call] of [
    ['save', () => profiles.saveRaw(alpha, second.tokens, seen.version, fullInput(branch))],
    ['publish', () => profiles.publishRaw(alpha, second.tokens, seen.version)],
    ['discard', () => profiles.discardRaw(alpha, second.tokens, seen.version)],
  ] as const) {
    await test.step(label, async () => {
      await refused(await call(), 409, 'conflict', `stale ${label}`);
      expect(profileSnapshot(alpha.id), `stale ${label}: nothing changed`).toBe(before);
    });
  }
  expect((await profiles.draft(alpha, first.tokens)).version, 'the first person\'s version stands').toBe(moved.version);
});

test('TC-F04-S01-008 AC-7: of simultaneous publishes or saves carrying one version exactly one succeeds and the rest are 409 conflict', async ({ identity }) => {
  const profiles = new Profiles(identity);
  const people = [await identity.staff(alpha, 'MANAGER'), await identity.staff(alpha, 'OWNER')];
  const branch = await profiles.defaultBranch(alpha, people[0].tokens);
  const ATTEMPTS = 10;
  await test.step('publishes', async () => {
    const saved = await profiles.save(alpha, people[0].tokens, (await profiles.draft(alpha, people[0].tokens)).version, fullInput(branch));
    const responses = await Promise.all(Array.from({ length: ATTEMPTS }, (_, n) => profiles.publishRaw(alpha, people[n % 2].tokens, saved.version)));
    const ok = responses.filter(r => r.status() === 200);
    expect(ok.length, 'exactly one publication').toBe(1);
    for (const r of responses.filter(r => r.status() !== 200)) await refused(r, 409, 'conflict', 'losing publish');
    const winner = await ok[0].json() as ProfileDraft;
    const after = await profiles.draft(alpha, people[0].tokens);
    expect(after.version, 'the version moved once, to the winner\'s').toBe(winner.version);
    expect(after.hasUnpublishedChanges).toBe(false);
    expect((await profiles.published(alpha)).profile, 'the published copy is the draft').toEqual(after.profile);
  });
  await test.step('saves', async () => {
    const current = await profiles.draft(alpha, people[0].tokens);
    const inputs = Array.from({ length: ATTEMPTS }, () => fullInput(branch));
    const responses = await Promise.all(inputs.map((input, n) => profiles.saveRaw(alpha, people[n % 2].tokens, current.version, input)));
    const ok = responses.map((r, n) => ({ r, n })).filter(x => x.r.status() === 200);
    expect(ok.length, 'exactly one save').toBe(1);
    for (const r of responses.filter(r => r.status() !== 200)) await refused(r, 409, 'conflict', 'losing save');
    const after = await profiles.draft(alpha, people[0].tokens);
    expect(after.version).toBe((await ok[0].r.json() as ProfileDraft).version);
    expect(after.profile.description, 'the stored draft is the winner\'s, whole').toBe(inputs[ok[0].n].description);
    expect(after.profile.branches.find(b => b.isDefault)!.address).toBe(inputs[ok[0].n].branches[0].address);
  });
});

test('TC-F04-S01-009 AC-2: only the Owner and Manager may read and change the profile; every other role and no credential are refused and change nothing', async ({ identity }) => {
  const profiles = new Profiles(identity);
  const expected: Record<Role, 'allowed' | 'refused'> = {
    OWNER: 'allowed', MANAGER: 'allowed', RECEPTIONIST: 'refused', PROFESSIONAL: 'refused', ACCOUNTANT: 'refused',
  };
  const keeper = await identity.staff(alpha, 'MANAGER');
  const branch = await profiles.defaultBranch(alpha, keeper.tokens);
  for (const role of Object.keys(expected) as Role[]) {
    await test.step(`${role}: ${expected[role]}`, async () => {
      const actor = await identity.staff(alpha, role);
      if (expected[role] === 'allowed') {
        const read = await profiles.draft(alpha, actor.tokens);
        const saved = await profiles.save(alpha, actor.tokens, read.version, fullInput(branch));
        const published = await profiles.publish(alpha, actor.tokens, saved.version);
        const again = await profiles.save(alpha, actor.tokens, published.version, fullInput(branch));
        await profiles.discard(alpha, actor.tokens, again.version);
        return;
      }
      for (const endpoint of ENDPOINTS) {
        const version = (await profiles.draft(alpha, keeper.tokens)).version;
        const before = profileSnapshot(alpha.id);
        await refused(await profiles.call(alpha, endpoint, actor.tokens, bodyFor(endpoint, version, branch)), 403, 'permission_denied', `${role} ${endpoint.name}`);
        expect(profileSnapshot(alpha.id), `${role} ${endpoint.name}: nothing changed`).toBe(before);
      }
    });
  }
  await test.step('no credential', async () => {
    for (const endpoint of ENDPOINTS) {
      const version = (await profiles.draft(alpha, keeper.tokens)).version;
      const before = profileSnapshot(alpha.id);
      const r = await profiles.call(alpha, endpoint, undefined, bodyFor(endpoint, version, branch));
      expect(r.status(), `signed out ${endpoint.name}`).toBe(401);
      expect(profileSnapshot(alpha.id), `signed out ${endpoint.name}: nothing changed`).toBe(before);
    }
  });
});

test('TC-F04-S01-010 AC-8, AC-1: another salon\'s staff, a salon named in headers and another salon\'s branch reach nothing beyond the hostname\'s salon', async ({ identity }) => {
  const profiles = new Profiles(identity);
  const alphaManager = await identity.staff(alpha, 'MANAGER');
  const betaManager = await identity.staff(beta, 'MANAGER');
  const { input: betaPublished } = await profiles.publishFresh(beta, betaManager.tokens);
  const betaBranch = betaPublished.branches[0].id;
  const betaDraftInput = fullInput(betaBranch);
  await profiles.save(beta, betaManager.tokens, (await profiles.draft(beta, betaManager.tokens)).version, betaDraftInput);
  const { input: alphaPublished } = await profiles.publishFresh(alpha, alphaManager.tokens);
  const alphaBranch = alphaPublished.branches[0].id;
  // beta has a published profile and an unpublished draft: there is something to leak.
  expect((await profiles.published(beta)).profile?.description).toBe(betaPublished.description);
  expect((await profiles.draft(beta, betaManager.tokens)).hasUnpublishedChanges).toBe(true);
  expect(alphaBranch, 'the salons have different branches').not.toBe(betaBranch);
  const betaBefore = profileSnapshot(beta.id);

  await test.step('beta\'s Manager on alpha\'s hostname gets "not found" from every settings endpoint', async () => {
    const betaVersion = (await profiles.draft(beta, betaManager.tokens)).version;
    for (const endpoint of ENDPOINTS) {
      const alphaBefore = profileSnapshot(alpha.id);
      await refused(await profiles.call(alpha, endpoint, betaManager.tokens, bodyFor(endpoint, betaVersion, betaBranch)), 404, 'not_found', `beta on alpha ${endpoint.name}`);
      expect(profileSnapshot(alpha.id), `${endpoint.name}: alpha unchanged`).toBe(alphaBefore);
    }
  });
  await test.step('a salon named in headers is ignored: alpha\'s endpoints act on alpha only', async () => {
    const naming = { 'X-Tenant-Id': beta.id, 'X-Salon-Id': beta.id, 'X-Salon-Slug': beta.slug, 'X-Forwarded-Host': beta.hostname, Forwarded: `host=${beta.hostname}` };
    const read = await profiles.call(alpha, 'read', alphaManager.tokens, undefined, naming);
    expect(read.status()).toBe(200);
    const draft = await read.json() as ProfileDraft;
    expect(draft.profile.branches.map(b => b.id), 'alpha\'s branch, never beta\'s').toEqual([alphaBranch]);
    const input = fullInput(alphaBranch);
    expect((await profiles.call(alpha, 'save', alphaManager.tokens, { version: draft.version, profile: input }, naming)).status()).toBe(200);
    expect((await profiles.draft(alpha, alphaManager.tokens)).profile.description).toBe(input.description);
  });
  await test.step('beta\'s branch named in alpha\'s save is the same field error as a branch that does not exist', async () => {
    const version = (await profiles.draft(alpha, alphaManager.tokens)).version;
    const alphaBefore = profileSnapshot(alpha.id);
    const foreign = await fieldErrors(await profiles.saveRaw(alpha, alphaManager.tokens, version, fullInput(betaBranch)), 'beta\'s branch');
    const unknown = await fieldErrors(await profiles.saveRaw(alpha, alphaManager.tokens, version, fullInput(randomUUID())), 'unknown branch');
    expect(foreign.fields.length).toBeGreaterThan(0);
    for (const field of foreign.fields) expect(field).toMatch(/branches(\[0\]|\.0)\.id$/);
    expect(comparable(foreign.body).replace(betaBranch, 'ID'), 'indistinguishable from an unknown branch').toBe(comparable(unknown.body).replace(/[0-9a-f-]{36}/g, 'ID'));
    expect(profileSnapshot(alpha.id)).toBe(alphaBefore);
  });
  await test.step('each public salon serves only its own published profile', async () => {
    const alphaServed = JSON.stringify((await profiles.published(alpha)).profile);
    const betaServed = (await profiles.published(beta)).profile;
    for (const value of [betaBranch, betaPublished.description!, betaDraftInput.description!, betaPublished.branches[0].address!]) {
      expect(alphaServed, 'alpha\'s public salon names nothing of beta').not.toContain(value);
    }
    expect(JSON.stringify(betaServed), 'beta\'s public salon names nothing of alpha').not.toContain(alphaBranch);
    expect(betaServed?.description, 'beta serves its own publication, not its draft').toBe(betaPublished.description);
  });
  expect(profileSnapshot(beta.id), 'beta changed in no way').toBe(betaBefore);
});

test('TC-F04-S01-011 AC-8: with no tenant context the profile and branch tables return no rows to the application role', async ({ identity }) => {
  const profiles = new Profiles(identity);
  for (const salon of [alpha, beta]) await profiles.publishFresh(salon, (await identity.staff(salon, 'MANAGER')).tokens);
  for (const table of PROFILE_TABLES) {
    await test.step(table, async () => {
      expect(profileRowCounts(alpha.id)[table], `${table}: alpha has rows`).toBeGreaterThan(0);
      expect(profileRowCounts(beta.id)[table], `${table}: beta has rows`).toBeGreaterThan(0);
      expect(identitySql(`SELECT count(*) FROM ${table}`, null), `${table}: no tenant, no rows`).toBe('0');
      expect(Number(identitySql(`SELECT count(*) FROM ${table}`, alpha.id)), `${table}: alpha sees its rows`).toBeGreaterThan(0);
      expect(identitySql(`SELECT count(*) FROM ${table} WHERE tenant_id <> ${sqlLiteral(alpha.id)}`, alpha.id), `${table}: alpha sees no other salon's row`).toBe('0');
    });
  }
});

test('TC-F04-S01-012 AC-8: requests for alpha and beta sent together each answer with their own salon\'s profile', async ({ identity }) => {
  const profiles = new Profiles(identity);
  const staff = { alpha: await identity.staff(alpha, 'MANAGER'), beta: await identity.staff(beta, 'MANAGER') };
  const published = {
    alpha: (await profiles.publishFresh(alpha, staff.alpha.tokens)).input,
    beta: (await profiles.publishFresh(beta, staff.beta.tokens)).input,
  };
  const calls = Array.from({ length: 24 }, (_, n) => {
    const key = n % 2 === 0 ? 'alpha' as const : 'beta' as const;
    const salon = key === 'alpha' ? alpha : beta;
    return { key, response: n % 4 < 2 ? profiles.call(salon, 'read', staff[key].tokens) : identity.api.getOn(salon.hostname, '/api/v1/salon') };
  });
  const answers = await Promise.all(calls.map(async c => ({ key: c.key, body: await (await c.response).json() as { profile: ProfileDraft['profile'] } })));
  for (const { key, body } of answers) {
    const own = published[key];
    expect(body.profile.branches.map(b => b.id), `${key}: its own branch`).toEqual([own.branches[0].id]);
    expect(body.profile.description, `${key}: its own description`).toBe(own.description);
  }
});

test('TC-F04-S01-013 AC-9: a support session reads the draft and is refused saving, publishing and discarding with support_read_only', async ({ identity, operator }) => {
  const profiles = new Profiles(identity);
  const { created, salon, owner } = await newSalon(operator, identity);
  const branch = await profiles.defaultBranch(salon, owner.tokens);
  await profiles.save(salon, owner.tokens, (await profiles.draft(salon, owner.tokens)).version, fullInput(branch));
  const ownerView = await profiles.draft(salon, owner.tokens);
  const support = await operator.actor('OPERATOR_SUPPORT');
  const session = await supportJson<SupportStart>(await operator.startSupport(support, created.id, { reason: 'QA F04 profile support reason' }), 'supportStart', 201);
  const tokens: Tokens = await operator.supportCredential(await operator.exchange(session.entryCode, salon.hostname));
  expect(await profiles.draft(salon, tokens), 'the support session reads the same draft').toEqual(ownerView);
  for (const endpoint of WRITES) {
    const before = profileSnapshot(salon.id);
    await refused(await profiles.call(salon, endpoint, tokens, bodyFor(endpoint, ownerView.version, branch)), 403, 'support_read_only', `support ${endpoint.name}`);
    expect(profileSnapshot(salon.id), `support ${endpoint.name}: nothing changed`).toBe(before);
  }
});

test('TC-F04-S01-014 AC-9: a suspended salon\'s settings endpoints answer 403 salon_suspended and change nothing', async ({ identity, operator }) => {
  const profiles = new Profiles(identity);
  const { admin, created, salon, owner } = await newSalon(operator, identity);
  const branch = await profiles.defaultBranch(salon, owner.tokens);
  const saved = await profiles.save(salon, owner.tokens, (await profiles.draft(salon, owner.tokens)).version, fullInput(branch));
  await operatorJson(await operator.suspend(admin, created.id, await operator.version(admin, created.id)), 'salon');
  for (const endpoint of ENDPOINTS) {
    const before = profileSnapshot(salon.id);
    await refused(await profiles.call(salon, endpoint, owner.tokens, bodyFor(endpoint, saved.version, branch)), 403, 'salon_suspended', `suspended ${endpoint.name}`);
    expect(profileSnapshot(salon.id), `suspended ${endpoint.name}: nothing changed`).toBe(before);
  }
});

test('TC-F04-S01-015 AC-9: a read-only salon reads its draft and refuses saving, publishing and discarding with salon_read_only', async ({ identity, operator, clock }) => {
  test.setTimeout(180_000);
  await clock.set('2026-11-20T07:00:00Z');
  const profiles = new Profiles(identity);
  const { admin, created, salon, owner } = await newSalon(operator, identity);
  const branch = await profiles.defaultBranch(salon, owner.tokens);
  const input = fullInput(branch);
  await profiles.save(salon, owner.tokens, (await profiles.draft(salon, owner.tokens)).version, input);
  await readOnlySalon(operator, clock, admin, created.id);
  const ownerNow = await identity.login(salon, owner.account);
  const draft = await profiles.draft(salon, ownerNow.tokens);
  expect(comparableProfile(draft.profile), 'the draft is still readable').toEqual(expectedProfile(input, phonesOf(input)));
  for (const endpoint of WRITES) {
    const before = profileSnapshot(salon.id);
    await refused(await profiles.call(salon, endpoint, ownerNow.tokens, bodyFor(endpoint, draft.version, branch)), 403, 'salon_read_only', `read-only ${endpoint.name}`);
    expect(profileSnapshot(salon.id), `read-only ${endpoint.name}: nothing changed`).toBe(before);
  }
});

test('TC-F04-S01-016 AC-10: save, publish and discard are audited with actor, time, action and changed field names, and no field value reaches the audit, the logs or an error', async ({ identity }) => {
  const profiles = new Profiles(identity);
  const manager = await identity.staff(alpha, 'MANAGER');
  const branch = await profiles.defaultBranch(alpha, manager.tokens);
  const t0 = await identity.now();
  const offsets = logOffsets();
  const whatsapp = mobile();
  const marker = unique('QAVALUE').replace(' ', '');
  const input = fullInput(branch, {
    description: `Description ${marker}`,
    branch: { address: `Address ${marker}`, whatsapp, location: { latitude: 7.123457, longitude: 80.654321 } },
    links: { instagram: `https://instagram.com/${marker}`, facebook: null, website: null },
  });
  const saved = await profiles.save(alpha, manager.tokens, (await profiles.draft(alpha, manager.tokens)).version, input);
  const published = await profiles.publish(alpha, manager.tokens, saved.version);
  const second = await profiles.save(alpha, manager.tokens, published.version, { ...input, description: `Second ${marker}` });
  await profiles.discard(alpha, manager.tokens, second.version);
  const errors = [
    await profiles.saveRaw(alpha, manager.tokens, second.version + 1, fullInput(branch, { branch: { phone: `9${marker}` }, description: `${marker}`.repeat(60) })),
    await profiles.saveRaw(alpha, manager.tokens, 0, input),
  ];
  const t1 = await identity.now();
  const secrets = [marker, whatsapp.replace(/\D/g, ''), e164(whatsapp), '7.123457', '80.654321'];

  const rows = profileAudit(alpha.id, t0);
  expect(rows.length, 'one entry per save, publish and discard').toBe(4);
  const [save1, publish, save2, discard] = rows;
  expect(save1.action, 'both saves are the same action').toBe(save2.action);
  expect(new Set([save1.action, publish.action, discard.action]).size, 'save, publish and discard are different actions').toBe(3);
  for (const row of rows) {
    expect(row.actorId, `${row.action}: the acting staff account`).toBe(manager.principal.id);
    expect(row.actorKind.toLowerCase()).toContain('staff');
    expect(Date.parse(row.at), `${row.action}: when`).toBeGreaterThanOrEqual(Date.parse(t0) - 1000);
    expect(Date.parse(row.at)).toBeLessThanOrEqual(Date.parse(t1) + 1000);
    for (const secret of secrets) expect(row.details, `${row.action}: no field value in the audit`).not.toContain(secret);
  }
  for (const name of ['description', 'address', 'whatsapp', 'location', 'instagram']) {
    expect(save1.details, `the first save names the changed field ${name}`).toContain(name);
  }
  expect(save2.details, 'the second save names the field it changed').toContain('description');
  expect(save2.details, 'and not one it did not change').not.toContain('address');

  for (const r of errors) {
    const body = await r.text();
    expect(r.status(), 'the refused requests were refused').toBeGreaterThanOrEqual(400);
    for (const secret of secrets) expect(body, 'no field value in an error').not.toContain(secret);
  }
  const logs = logsSince(offsets);
  for (const secret of secrets) expect(logs, 'no field value in the API or web log').not.toContain(secret);
});

test('TC-F04-S01-017 AC-1: every salon has exactly one default branch, a new salon has it and an empty draft from its creation, and the draft names it', async ({ identity, operator }) => {
  const profiles = new Profiles(identity);
  await test.step('every live salon of the database has exactly one default branch', async () => {
    expect(Number(identitySql('SELECT count(*) FROM tenant WHERE deleted_at IS NULL')), 'salons exist').toBeGreaterThanOrEqual(3);
    expect(identitySql(`SELECT count(*) FROM tenant t WHERE t.deleted_at IS NULL
      AND (SELECT count(*) FROM branding_branch b WHERE b.tenant_id = t.id AND b.is_default) <> 1`), 'salons without exactly one default branch').toBe('0');
  });
  for (const salon of [alpha, beta, gamma]) {
    await test.step(`${salon.slug}: the draft names the stored default branch`, async () => {
      const manager = await identity.staff(salon, 'MANAGER');
      const stored = identitySql(`SELECT id FROM branding_branch WHERE tenant_id = ${sqlLiteral(salon.id)} AND is_default`);
      expect(await profiles.defaultBranch(salon, manager.tokens)).toBe(stored);
    });
  }
  await test.step('a salon the operator creates', async () => {
    const admin = await operator.actor();
    const plan = await operator.plan(admin);
    const { input, created, salon } = await operator.salon(admin, plan.id);
    // Read at once after the 201, before anyone signs in: the creating transaction made them.
    expect(profileRowCounts(created.id), 'one branch and its empty draft, nothing published').toEqual({
      branding_branch: 1, branding_profile_draft: 1, branding_profile_published: 0,
      branding_branch_contact_draft: 1, branding_branch_contact_published: 0,
    });
    const stored = identitySql(`SELECT id FROM branding_branch WHERE tenant_id = ${sqlLiteral(created.id)} AND is_default`);
    expect(stored, 'the one branch is the default').toMatch(/^[0-9a-f-]{36}$/);
    const owner = await ownerSession(identity, salon as Salon, input);
    const draft = await profiles.draft(salon, owner.tokens);
    expect(comparableProfile(draft.profile), 'an empty draft naming the default branch').toEqual(EMPTY(stored));
    expect(draft.hasUnpublishedChanges).toBe(false);
    expect(draft.publishedAt).toBeNull();
  });
});
