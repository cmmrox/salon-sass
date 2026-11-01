/**
 * F04 across its stages: a new salon set up from empty through Profile, Branding, Hours and Booking
 * rules, with what the API serves before and after publishing; and the operator outside a support
 * session kept out of every setting the feature adds.
 *
 * Expected results are the feature README's "Feature-level test focus" and "Saving vs publishing",
 * and the four stage specs. Each stage's own rules are proved in its own file; these cases assert
 * only what passes between the stages.
 */
import { BookingRules, RULES_HISTORY, RULES_SETTINGS, SUGGESTED, rules, rulesSnapshot, stored } from '../../shared/booking-rules';
import type { Identity, Tokens } from '../../shared/identity';
import { test, expect, ownerSession, type Operator, type OperatorActor } from '../../shared/operator';
import { Brands, brandInput, brandSnapshot, BRAND_ENDPOINTS } from '../../shared/salon-brand';
import { CLOSURES, HOLIDAYS, HOURS_SETTINGS, Hours, hoursSnapshot, localDate, plusDays, stored as storedWeek, week } from '../../shared/salon-hours';
import { ENDPOINTS as PROFILE_ENDPOINTS, Profiles, e164, expectedProfile, fullInput, profileSnapshot } from '../../shared/salon-profile';

test.describe.configure({ timeout: 120_000 });

async function newSalon(operator: Operator, identity: Identity) {
  const admin = await operator.actor();
  const plan = await operator.plan(admin);
  const { input, created, salon } = await operator.salon(admin, plan.id);
  const owner = await ownerSession(identity, salon, input);
  return { admin, input, created, salon, owner };
}

test('TC-F04-001 BRD-01, BRD-02, BRD-04, STF-04, BKG-02, BKG-09: a new salon\'s Owner sets up Profile, Branding, Hours and Booking rules; hours and rules are served at once, the profile and brand only once published, exactly as published, and a later draft never', async ({ identity, operator }) => {
  const profiles = new Profiles(identity);
  const brands = new Brands(identity);
  const hours = new Hours(identity);
  const book = new BookingRules(identity);
  const { salon, owner, input } = await newSalon(operator, identity);
  const fresh = await profiles.published(salon);
  expect(fresh.profile, 'a new salon has no published profile').toBeNull();

  const branch = await profiles.defaultBranch(salon, owner.tokens);
  const profileInput = fullInput(branch);
  const brand = brandInput({ colour: '#2F4B8A' });
  const weekly = week('08:30', '19:30', ['sunday'], { saturday: ['09:00', '17:00'] });
  const policy = rules({ deposit: { kind: 'amount', amountMinor: 75000 }, freeCancellationHours: 12, reschedule: { limit: 1, untilHours: 6 } });

  await test.step('every tab is set: profile and brand as drafts, hours and booking rules saved', async () => {
    await profiles.save(salon, owner.tokens, (await profiles.draft(salon, owner.tokens)).version, profileInput);
    await brands.edit(salon, owner.tokens, brand);
    await hours.save(salon, owner.tokens, weekly);
    await book.save(salon, owner.tokens, policy, 0);
  });
  await test.step('before publishing: hours and rules are served, the drafts are not', async () => {
    const served = await profiles.published(salon);
    expect(served.profile, 'the profile draft is not served').toBeNull();
    expect(served.name, 'the brand draft\'s name is not served').toBe(input.name);
    expect(served.name).not.toBe(brand.name);
    expect(served.brandColour, 'nor its colour').not.toBe(brand.colour);
    expect(JSON.stringify(served), 'nor its tagline').not.toContain(brand.tagline!);
    expect((await hours.published(salon)).weekly, 'the hours save at once').toEqual(storedWeek(weekly));
    expect((await book.published(salon)).rules, 'the booking rules save at once').toEqual(stored(policy));
  });
  await test.step('publishing serves exactly what was published', async () => {
    await profiles.publish(salon, owner.tokens, (await profiles.draft(salon, owner.tokens)).version);
    await brands.publish(salon, owner.tokens, (await brands.draft(salon, owner.tokens)).version);
    const served = await profiles.published(salon);
    expect({ name: served.name, tagline: served.tagline, brandColour: served.brandColour }).toEqual({ name: brand.name, tagline: brand.tagline, brandColour: brand.colour });
    const [b] = profileInput.branches;
    const own = served.profile!.branches.find(x => x.isDefault)!;
    expect({ description: served.profile!.description, links: served.profile!.links, branch: own })
      .toEqual(expectedProfile(profileInput, { phone: e164(b.phone!), whatsapp: e164(b.whatsapp!) }));
    expect((await hours.published(salon)).weekly).toEqual(storedWeek(weekly));
    expect((await book.published(salon)).rules).toEqual(stored(policy));
  });
  await test.step('a later draft is not served until it is published', async () => {
    const later = fullInput(branch);
    await profiles.save(salon, owner.tokens, (await profiles.draft(salon, owner.tokens)).version, later);
    await brands.edit(salon, owner.tokens, { name: `${brand.name} later`, colour: '#CC5500' });
    const served = await profiles.published(salon);
    expect(served.profile!.description, 'the published profile still').toBe(profileInput.description);
    expect(served.name, 'the published name still').toBe(brand.name);
    expect(served.brandColour).toBe(brand.colour);
  });
  await test.step('closing a day shows in the published opening information', async () => {
    const date = plusDays(localDate(await identity.now(), salon.timeZone), 9);
    await hours.add(salon, owner.tokens, date, null);
    expect((await hours.published(salon)).exceptions.filter(e => e.date === date).map(e => e.kind)).toEqual(['closed']);
  });
  await brands.dispose();
});

test('TC-F04-002 BRD-01, BRD-02, STF-04, BKG-09: an operator Admin or Support account outside a support session can neither read nor change any of a salon\'s settings', async ({ identity, operator }) => {
  const profiles = new Profiles(identity);
  const brands = new Brands(identity);
  const hours = new Hours(identity);
  const book = new BookingRules(identity);
  const { salon, owner } = await newSalon(operator, identity);
  const branch = await profiles.defaultBranch(salon, owner.tokens);
  await profiles.publishFresh(salon, owner.tokens);
  await brands.edit(salon, owner.tokens, brandInput());
  await hours.save(salon, owner.tokens, week());
  const date = plusDays(localDate(await identity.now(), salon.timeZone), 5);
  const closure = await hours.add(salon, owner.tokens, date, 'QA operator must not read this');
  await book.save(salon, owner.tokens, SUGGESTED, 0);
  const year = Number(date.slice(0, 4));
  const routes: { label: string; method: string; path: string; data?: unknown }[] = [
    ...PROFILE_ENDPOINTS.map(e => ({ label: `profile ${e.name}`, method: e.method, path: e.path, data: e.name === 'read' ? undefined : e.name === 'save' ? { version: 1, profile: fullInput(branch) } : { version: 1 } })),
    ...BRAND_ENDPOINTS.map(e => ({ label: `brand ${e.name}`, method: e.method, path: e.path, data: e.name === 'read' ? undefined : e.name === 'save' ? { version: 1, brand: brandInput() } : { version: 1 } })),
    { label: 'hours read', method: 'GET', path: HOURS_SETTINGS },
    { label: 'hours save', method: 'PUT', path: HOURS_SETTINGS, data: { version: 1, weekly: week('07:00') } },
    { label: 'holidays read', method: 'GET', path: HOLIDAYS },
    { label: 'holidays accept', method: 'POST', path: `${HOLIDAYS}/${year}/acceptance`, data: { version: 0, choices: [] } },
    { label: 'closures read', method: 'GET', path: CLOSURES },
    { label: 'closure add', method: 'POST', path: CLOSURES, data: { date: plusDays(date, 1), reason: null } },
    { label: 'closure reopen', method: 'DELETE', path: `${CLOSURES}/${closure.id}` },
    { label: 'closure impact', method: 'GET', path: `${CLOSURES}/impact?date=${date}` },
    { label: 'rules read', method: 'GET', path: RULES_SETTINGS },
    { label: 'rules history', method: 'GET', path: RULES_HISTORY },
    { label: 'rules save', method: 'PUT', path: RULES_SETTINGS, data: { version: 1, rules: rules({ freeCancellationHours: 2 }) } },
  ];
  const snapshot = () => [profileSnapshot(salon.id), brandSnapshot(salon.id), hoursSnapshot(salon.id), rulesSnapshot(salon.id)].join('/');
  const before = snapshot();
  const actors: [string, OperatorActor][] = [['Admin', await operator.actor()], ['Support', await operator.actor('OPERATOR_SUPPORT')]];
  for (const [who, actor] of actors) {
    await test.step(`${who} outside a support session`, async () => {
      for (const route of routes) {
        const r = await hours.call(salon, route.method, route.path, actor.tokens as Tokens, route.data);
        const body = await r.text();
        expect([401, 403], `${who} ${route.label} (${r.status()} ${body.slice(0, 160)})`).toContain(r.status());
        expect(JSON.parse(body).code, `${who} ${route.label}: a refusal`).toMatch(/^(invalid_session|permission_denied|not_found)$/);
        expect(body, `${who} ${route.label}: no closure reason`).not.toContain('QA operator must not read this');
      }
    });
  }
  expect(snapshot(), 'nothing changed').toBe(before);
  await brands.dispose();
});
