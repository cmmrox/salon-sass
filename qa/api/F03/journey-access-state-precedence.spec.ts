/**
 * F03 journey TC-F03-003: access states combine in the documented precedence across billing (S02),
 * lifecycle (S03) and support (S04): `closed` over `suspended` over `read_only` over billing states.
 *
 * The support steps use the S04 spec's routes and codes and the handoff's credential shape
 * (qa/shared/operator.ts PENDING_S04).
 */
import { syntheticAccount } from '../../shared/identity';
import { readOnlySalon } from '../../shared/billing';
import {
  test, expect, comparable, onSalonHost, operatorJson, operatorProblem, ownerSession, staffTokens, supportJson,
  type SupportStart,
} from '../../shared/operator';

const STAFF = '/api/v1/identity/staff?size=1';

test('TC-F03-003 Access states combine in the documented precedence across billing, lifecycle and support', async ({ operator, identity, clock }) => {
  test.setTimeout(240_000);
  await clock.set('2027-02-01T07:00:00Z');
  const admin = await operator.actor();
  const plan = await operator.plan(admin);
  const { created, input, salon } = await operator.salon(admin, plan.id);
  const owner = await ownerSession(identity, salon, input);
  const invoice = await readOnlySalon(operator, clock, admin, created.id);
  const support = await operator.actor('OPERATOR_SUPPORT');
  const begin = async () => supportJson<SupportStart>(await operator.startSupport(await operator.renew(support), created.id, { reason: 'QA precedence support' }), 'supportStart', 201);
  const ownerTokens = staffTokens(identity, salon, owner.account, owner.tokens);

  // read_only: support may start and read; mutations are refused by the support rule.
  const first = await begin();
  const credential = await operator.supportCredential(await operator.exchange(first.entryCode, salon.hostname));
  expect((await onSalonHost(identity, salon, 'GET', STAFF, credential)).status(), 'support reads a read-only salon').toBe(200);
  await operatorProblem(await identity.call(salon, 'POST', '/staff', syntheticAccount('MANAGER'), await ownerTokens()), 403, 'salon_read_only');

  // suspended over read_only: the support credential and new sessions are refused.
  await operatorJson(await operator.suspend(admin, created.id, await operator.version(admin, created.id)), 'salon');
  await operatorProblem(await onSalonHost(identity, salon, 'GET', STAFF, credential), 403, 'salon_suspended');
  await operatorProblem(await operator.startSupport(await operator.renew(support), created.id, { reason: 'QA precedence support' }), 409, 'invalid_state');

  // Reactivation returns to read_only; paying every overdue invoice returns to active.
  await operatorJson(await operator.reactivate(admin, created.id, await operator.version(admin, created.id)), 'salon');
  expect((await operator.subscription(admin, created.id)).state).toBe('read_only');
  await operatorProblem(await identity.call(salon, 'POST', '/staff', syntheticAccount('MANAGER'), await ownerTokens()), 403, 'salon_read_only');
  await operator.pay(admin, invoice);
  expect((await operator.subscription(admin, created.id)).state).toBe('active');
  expect((await identity.call(salon, 'POST', '/staff', syntheticAccount('MANAGER'), await ownerTokens())).status(), 'active again').toBe(201);

  // The first session was never ended (suspension refuses credentials but leaves sessions open,
  // decision 053): it reads again once the salon is active; the operator ends it before a new one.
  expect((await onSalonHost(identity, salon, 'GET', STAFF, credential)).status(), 'the open session reads again after reactivation').toBe(200);
  expect((await operator.endSupport(await operator.renew(support), first.id)).status()).toBe(204);

  // closed over everything: every credential gets the unknown-salon answer and no session starts.
  const second = await begin();
  const late = await operator.supportCredential(await operator.exchange(second.entryCode, salon.hostname));
  const tokens = await ownerTokens();
  await operatorJson(await operator.close(admin, created.id, await operator.version(admin, created.id), created.slug), 'salon');
  const unknown = comparable(await (await identity.api.getOn('qa-never-provisioned.localhost', '/api/v1/salon')).text());
  for (const [label, response] of [['support credential', await onSalonHost(identity, salon, 'GET', STAFF, late)],
    ['Owner session', await onSalonHost(identity, salon, 'GET', STAFF, tokens)], ['public salon', await identity.api.getOn(salon.hostname, '/api/v1/salon')]] as const) {
    await operatorProblem(response, 404, 'not_found');
    if (label === 'public salon') expect(comparable(await response.text()), 'the unknown-salon answer').toBe(unknown);
  }
  await operatorProblem(await operator.startSupport(await operator.renew(support), created.id, { reason: 'QA precedence support' }), 409, 'invalid_state');
});
