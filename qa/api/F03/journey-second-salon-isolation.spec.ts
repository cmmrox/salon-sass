/**
 * F03 journey TC-F03-002: a second salon (B) and alpha stay isolated and unaffected while salon A is
 * taken through provisioning, seat exhaustion, read-only, suspension, a support session, closure,
 * export and deletion. Stage rules are not repeated; after each transition B's and alpha's Owners
 * read and mutate once, and their tenant-table counts are compared.
 *
 * The support step uses the S04 handoff's credential shape (qa/shared/operator.ts PENDING_S04).
 */
import { crossBoundary, firstInvoice } from '../../shared/billing';
import { syntheticAccount, type Identity, type Tokens } from '../../shared/identity';
import { rowsOf, unzip } from '../../shared/zip';
import {
  test, expect, alpha, onSalonHost, operatorJson, operatorProblem, ownerSession, staffTokens, supportJson, tenantRowCounts,
  type SalonDetail, type SupportStart,
} from '../../shared/operator';
import type { Salon } from '../../shared/tenants';

type Witness = { salon: Salon; owner: () => Promise<Tokens>; label: string };

/** One read and one mutation each, and unchanged counts apart from the mutation's own rows. */
async function unaffected(identity: Identity, witnesses: Witness[], step: string) {
  for (const w of witnesses) {
    const tokens = await w.owner();
    const before = tenantRowCounts(w.salon.id);
    expect(Object.values(before).some(n => n > 0), `${step}: ${w.label} has records to protect`).toBe(true);
    for (const route of ['/api/v1/identity/staff?size=100', '/api/v1/identity/audit?size=100']) {
      expect((await onSalonHost(identity, w.salon, 'GET', route, tokens)).status(), `${step}: ${w.label} reads ${route}`).toBe(200);
    }
    expect((await identity.call(w.salon, 'POST', '/staff', syntheticAccount('PROFESSIONAL'), tokens)).status(), `${step}: ${w.label} mutates`).toBe(201);
    const after = tenantRowCounts(w.salon.id);
    for (const table of Object.keys(before)) expect(after[table], `${step}: ${w.label} ${table} never shrinks`).toBeGreaterThanOrEqual(before[table]);
  }
}

test('TC-F03-002 A second salon stays isolated and unaffected through every transition of the first', async ({ operator, identity, clock }) => {
  test.setTimeout(300_000);
  await clock.set('2027-02-02T07:00:00Z');
  const admin = await operator.actor();
  const plan = await operator.plan(admin, { staffSeats: 3 });
  const roomy = await operator.plan(admin, { staffSeats: 50 });
  const a = await operator.salon(admin, plan.id);
  const b = await operator.salon(admin, roomy.id);
  const ownerA = await ownerSession(identity, a.salon, a.input);
  const ownerB = await ownerSession(identity, b.salon, b.input);
  const alphaOwner = await identity.staff(alpha, 'OWNER');
  const witnesses: Witness[] = [
    { salon: b.salon, label: 'B', owner: staffTokens(identity, b.salon, ownerB.account, ownerB.tokens) },
    { salon: alpha, label: 'alpha', owner: staffTokens(identity, alpha, alphaOwner.account, alphaOwner.tokens) },
  ];
  await unaffected(identity, witnesses, 'provisioning');
  // Seat exhaustion in A.
  for (let n = 0; n < 2; n++) expect((await identity.call(a.salon, 'POST', '/staff', syntheticAccount('RECEPTIONIST'), ownerA.tokens)).status()).toBe(201);
  await operatorProblem(await identity.call(a.salon, 'POST', '/staff', syntheticAccount('RECEPTIONIST'), ownerA.tokens), 409, 'entitlement_exceeded');
  await unaffected(identity, witnesses, 'seat exhaustion');
  // Read-only A (B paid).
  const invoiceA = await firstInvoice(operator, clock, admin, a.created.id);
  const invoiceB = (await operator.invoices(admin, b.created.id)).items[0];
  expect(invoiceB, 'B was invoiced too').toBeTruthy();
  await operator.pay(admin, invoiceB);
  await crossBoundary(operator, clock, admin, invoiceA, 'read_only');
  expect((await operator.subscription(admin, a.created.id)).state).toBe('read_only');
  await unaffected(identity, witnesses, 'read-only');
  const invoicesB = await onSalonHost(identity, b.salon, 'GET', '/api/v1/salon/invoices', await witnesses[0].owner());
  expect(JSON.stringify(await invoicesB.json()), 'B lists no invoice of A').not.toContain(invoiceA.id);
  // Suspension and a support session on A.
  await operatorJson(await operator.suspend(admin, a.created.id, await operator.version(admin, a.created.id)), 'salon');
  await unaffected(identity, witnesses, 'suspension');
  await operatorJson(await operator.reactivate(admin, a.created.id, await operator.version(admin, a.created.id)), 'salon');
  const support = await operator.actor('OPERATOR_SUPPORT');
  const session = await supportJson<SupportStart>(await operator.startSupport(support, a.created.id, { reason: 'QA isolation support' }), 'supportStart', 201);
  const credential = await operator.supportCredential(await operator.exchange(session.entryCode, a.salon.hostname));
  for (const w of witnesses) await operatorProblem(await onSalonHost(identity, w.salon, 'GET', '/api/v1/identity/staff', credential), 404, 'not_found');
  await unaffected(identity, witnesses, 'support session');
  // Closure, export and deletion of A.
  const closed = await operatorJson<SalonDetail>(await operator.close(admin, a.created.id, await operator.version(admin, a.created.id), a.created.slug), 'salon');
  await operator.runExports();
  const archive = await operator.archiveRaw(admin, a.created.id);
  expect(archive.status()).toBe(200);
  const files = unzip(await archive.body());
  const text = [...files.entries()].filter(([name]) => name.startsWith('tables/')).map(([, file]) => JSON.stringify(rowsOf(file))).join('\n');
  for (const foreign of [b.created.id, alpha.id, b.input.owner.email, b.input.name]) expect(text, 'A\'s export holds nothing of B or alpha').not.toContain(foreign);
  await unaffected(identity, witnesses, 'closure and export');
  const beforeDeletion = { b: tenantRowCounts(b.created.id), alpha: tenantRowCounts(alpha.id) };
  await clock.set(closed.deleteAt!);
  await operator.runDeletions();
  expect({ b: tenantRowCounts(b.created.id), alpha: tenantRowCounts(alpha.id) }, 'A\'s deletion removes no B or alpha row').toEqual(beforeDeletion);
  await unaffected(identity, witnesses, 'deletion');
});
