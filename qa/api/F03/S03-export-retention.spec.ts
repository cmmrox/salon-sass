/**
 * F03-S03 export and retention: the archive's completeness and exactness, secrets left out, the
 * Owner's link, console download authority, deletion at 30 days, interrupted and concurrent jobs,
 * and the audit of the whole lifecycle.
 * Cases TC-F03-S03-006 to -012 and -014 (cases.md).
 *
 * The export and deletion jobs run through the run-job hooks at the test-clock instant under test,
 * the export-ready email is read through S02's email capture, and every tenant table is taken from
 * the database catalogue (any table with `tenant_id`), not from the product's exporter registry.
 */
import { createHash, randomBytes } from 'node:crypto';

import { firstInvoice } from '../../shared/billing';
import { Customers } from '../../shared/customer-identity';
import { Profiles } from '../../shared/customer-profile';
import { ROLES, syntheticAccount, type Identity, type Tokens } from '../../shared/identity';
import { identitySql } from '../../shared/identity-storage';
import { logOffsets, logsSince } from '../../shared/product-logs';
import {
  test, expect, alpha, beta, gamma, CONSOLE_HOST, SECOND, WRONG_HOSTS, comparable, countRows, operatorJson, operatorProblem,
  ownerSession, platformAudit, plusMs, sqlLiteral, tenantRowCounts, tenantTables,
  type ExportView, type Operator, type OperatorActor, type SalonDetail, type TestClock,
} from '../../shared/operator';
import { rowsOf, unzip } from '../../shared/zip';

const id = (n: number) => `TC-F03-S03-${String(n).padStart(3, '0')}`;
const sha256 = (bytes: Buffer | string) => createHash('sha256').update(bytes).digest('hex');
const EXPORT_KEYS = ['createdAt', 'deleteAt', 'readyAt', 'sha256', 'sizeBytes', 'status'];

type Manifest = {
  exportedAt: string; salon: Record<string, unknown>; note: string;
  tables: { name: string; file: string; rows: number; excludedColumns: string[] }[];
  excluded: { table: string; column: string; reason: string }[];
};

/** A provisioned salon with rows across the tenant tables: staff, sessions, audit, a customer, messages. */
async function populated(operator: Operator, identity: Identity, admin: OperatorActor, planId: string) {
  const made = await operator.salon(admin, planId);
  const owner = await ownerSession(identity, made.salon, made.input);
  await identity.seed(made.salon, [syntheticAccount('MANAGER')]);
  expect((await identity.call(made.salon, 'POST', '/staff', syntheticAccount('RECEPTIONIST'), owner.tokens)).status()).toBe(201);
  const customer = await new Customers(identity).login(made.salon);
  await new Profiles(identity).saved(customer);
  return { ...made, owner, customer };
}

async function arrange(operator: Operator, identity: Identity, clock: TestClock, createdAt: string, withInvoice = false) {
  await clock.set(createdAt);
  const admin = await operator.actor();
  const plan = await operator.plan(admin);
  const target = await populated(operator, identity, admin, plan.id);
  const other = await populated(operator, identity, admin, plan.id);
  if (withInvoice) {
    const invoice = await firstInvoice(operator, clock, admin, target.created.id);
    await operator.pay(admin, invoice);
  }
  const closed = await operatorJson<SalonDetail>(await operator.close(admin, target.created.id, await operator.version(admin, target.created.id), target.created.slug,
    'QA closure for export'), 'salon');
  return { admin, plan, target, other, closed };
}

async function ready(operator: Operator, admin: OperatorActor, salonId: string): Promise<ExportView> {
  await operator.runExports();
  const view = await operator.exportStatus(admin, salonId);
  expect(view.status, 'the export is ready after a run').toBe('ready');
  return view;
}

async function download(operator: Operator, admin: OperatorActor, salonId: string) {
  const r = await operator.archiveRaw(admin, salonId);
  expect(r.status()).toBe(200);
  const headers = r.headers();
  expect(headers['content-type']).toContain('application/zip');
  expect(headers['content-disposition'] ?? '', 'an attachment').toMatch(/^attachment/);
  expect(headers['cache-control'] ?? '', 'no-store').toContain('no-store');
  return { bytes: await r.body(), sha: headers['x-content-sha256'] };
}

function columnsOf(table: string): string[] {
  return identitySql(`SELECT column_name FROM information_schema.columns WHERE table_schema = 'public' AND table_name = ${sqlLiteral(table)}`).split('\n').filter(Boolean);
}
const camel = (name: string) => name.replace(/_([a-z])/g, (_, c: string) => c.toUpperCase());
const SECRET_COLUMN = /hash|secret|payload|encrypted|lease_token|credential/;

async function exportToken(operator: Operator, ownerEmail: string): Promise<string> {
  const [email] = await operator.emailsOfKind(ownerEmail, 'export.ready', 1);
  expect(email.links.length, 'one download link').toBe(1);
  const match = /^http:\/\/localhost:3100\/exports#([A-Za-z0-9_-]{43})$/.exec(email.links[0]);
  expect(match, `the link is the platform-host page with a 256-bit token in the fragment: ${email.links[0].replace(/#.*/, '#…')}`).not.toBeNull();
  return match![1];
}

test(`${id(6)} The export contains every tenant table with exactly that salon's rows`, async ({ operator, identity, clock }) => {
  const { admin, target, other } = await arrange(operator, identity, clock, '2026-12-10T07:00:00Z');
  const expected = tenantRowCounts(target.created.id);
  expect(Object.values(expected).filter(n => n > 0).length, 'the salon has rows in several tenant tables').toBeGreaterThanOrEqual(5);
  const view = await ready(operator, admin, target.created.id);
  const { bytes, sha } = await download(operator, admin, target.created.id);
  expect(sha256(bytes)).toBe(view.sha256);
  expect(sha).toBe(view.sha256);
  expect(bytes.length).toBe(view.sizeBytes);
  const files = unzip(bytes);
  const manifest = JSON.parse(files.get('manifest.json')!.toString('utf8')) as Manifest;
  expect(manifest.tables.map(t => t.name).sort(), 'every tenant table of the catalogue').toEqual(tenantTables());
  for (const table of manifest.tables) {
    expect(table.rows, `${table.name}: row count`).toBe(expected[table.name]);
    const rows = rowsOf(files.get(table.file)!);
    expect(rows.length, `${table.name}: rows in the file`).toBe(table.rows);
  }
  expect(manifest.salon).toMatchObject({ id: target.created.id, slug: target.created.slug, name: target.input.name });
  expect(Number.isFinite(Date.parse(manifest.exportedAt)), 'the export instant').toBe(true);
  const everything = [...files.values()].map(b => b.toString('utf8')).join('\n');
  for (const foreign of [other.created.id, other.input.owner.email, other.input.name, alpha.id, beta.id, gamma.id]) {
    expect(everything, 'nothing of another salon').not.toContain(foreign);
  }
});

test(`${id(7)} The export excludes every secret and says so`, async ({ operator, identity, clock }) => {
  const { admin, target } = await arrange(operator, identity, clock, '2026-12-11T07:00:00Z');
  await ready(operator, admin, target.created.id);
  const files = unzip((await download(operator, admin, target.created.id)).bytes);
  const manifest = JSON.parse(files.get('manifest.json')!.toString('utf8')) as Manifest;
  const everything = [...files.values()].map(b => b.toString('utf8')).join('\n');
  let secretColumns = 0;
  for (const table of tenantTables()) {
    for (const column of columnsOf(table).filter(c => SECRET_COLUMN.test(c))) {
      secretColumns++;
      const values = identitySql(`SELECT ${column}::text FROM ${table} WHERE tenant_id = ${sqlLiteral(target.created.id)} AND ${column} IS NOT NULL`)
        .split('\n').filter(v => v.length >= 8);
      for (const value of values) expect(everything.includes(value), `${table}.${column}: a stored secret value is not in the archive`).toBe(false);
      const file = manifest.tables.find(t => t.name === table)!;
      for (const row of rowsOf(files.get(file.file)!)) {
        expect(Object.keys(row), `${table}: no ${column} field`).not.toContain(column);
        expect(Object.keys(row)).not.toContain(camel(column));
      }
      expect(manifest.excluded.some(e => e.table === table && e.column === column && e.reason.length > 0), `${table}.${column} listed as excluded with a reason`).toBe(true);
    }
  }
  expect(secretColumns, 'secret-bearing columns were found to check').toBeGreaterThanOrEqual(5);
  expect(manifest.note, 'the manifest says secrets are excluded').toMatch(/secret/i);
});

test(`${id(8)} The Owner's email link downloads the archive until deletion and bad tokens reveal nothing`, async ({ operator, identity, clock }) => {
  test.setTimeout(120_000);
  const { admin, target, closed } = await arrange(operator, identity, clock, '2026-12-12T07:00:00Z');
  const offsets = logOffsets();
  const view = await ready(operator, admin, target.created.id);
  const token = await exportToken(operator, target.input.owner.email);
  const before = platformAudit(target.created.id).filter(e => e.action === 'export.downloaded').length;
  for (let n = 0; n < 2; n++) {
    const r = await operator.linkRaw(token);
    expect(r.status(), 'the link downloads').toBe(200);
    expect(sha256(await r.body()), 'the checksum matches').toBe(view.sha256);
  }
  const metadata = await operatorJson<{ sha256: string; deleteAt: string }>(await operator.linkMetadataRaw(token), 'linkView');
  expect(metadata).toMatchObject({ sha256: view.sha256, deleteAt: closed.deleteAt });
  expect(platformAudit(target.created.id).filter(e => e.action === 'export.downloaded').length, 'each download audited').toBe(before + 2);
  const unknown = comparable(await (await operator.linkRaw(randomBytes(32).toString('base64url'))).text());
  const altered = token.slice(0, -1) + (token.endsWith('A') ? 'B' : 'A');
  for (const [label, response] of [
    ['altered', await operator.linkRaw(altered)], ['malformed', await operator.linkRaw('abc')],
    ['on the salon host', await operator.linkRaw(token, target.salon.hostname)], ['on alpha', await operator.linkRaw(token, alpha.hostname)],
    ['on the console', await operator.linkRaw(token, CONSOLE_HOST)], ['metadata altered', await operator.linkMetadataRaw(altered)],
  ] as const) {
    await operatorProblem(response, 404, 'not_found');
    expect(comparable(await response.text()), `${label}: the same answer as an unknown token`).toBe(unknown);
  }
  const logs = logsSince(offsets);
  expect(logs.length, 'the product logged the requests').toBeGreaterThan(0);
  expect(logs.includes(token), 'the token appears in no log').toBe(false);
  // After deletion the link is gone.
  await clock.set(closed.deleteAt!);
  await operator.runDeletions();
  await operatorProblem(await operator.linkRaw(token), 404, 'not_found');
});

test(`${id(9)} Only an Admin on the console sees export status and downloads the archive`, async ({ operator, identity, clock }) => {
  const { admin, target, closed } = await arrange(operator, identity, clock, '2026-12-13T07:00:00Z');
  const pending = await operator.exportStatus(admin, target.created.id);
  expect(pending.status, 'pending until the job runs').toBe('pending');
  await operatorProblem(await operator.archiveRaw(admin, target.created.id), 409, 'invalid_state');
  const view = await ready(operator, admin, target.created.id);
  expect(Object.keys(view).sort()).toEqual(EXPORT_KEYS);
  expect(view.deleteAt).toBe(closed.deleteAt);
  expect(sha256((await download(operator, admin, target.created.id)).bytes)).toBe(view.sha256);
  const support = await operator.actor('OPERATOR_SUPPORT');
  const tenant: Tokens[] = [];
  for (const role of ROLES) tenant.push((await identity.staff(alpha, role)).tokens);
  tenant.push((await new Customers(identity).login(alpha)).tokens);
  for (const route of [`/salons/${target.created.id}/export`, `/salons/${target.created.id}/export/archive`]) {
    await operatorProblem(await operator.call('GET', route, { tokens: support.tokens }), 403, 'permission_denied');
    for (const tokens of tenant) await operatorProblem(await operator.call('GET', route, { tokens }), 401, 'invalid_session');
    for (const host of WRONG_HOSTS) await operatorProblem(await operator.call('GET', route, { tokens: admin.tokens, host }), 404, 'not_found');
  }
});

test(`${id(10)} Thirty days after closure the salon's tenant data is deleted and nothing else`, async ({ operator, identity, clock }) => {
  test.setTimeout(120_000);
  const { admin, target, other, closed } = await arrange(operator, identity, clock, '2026-12-14T07:00:00Z', true);
  await ready(operator, admin, target.created.id);
  const counts = tenantRowCounts(target.created.id);
  const others = { other: tenantRowCounts(other.created.id), alpha: tenantRowCounts(alpha.id), beta: tenantRowCounts(beta.id), gamma: tenantRowCounts(gamma.id) };
  const invoicesBefore = countRows('invoice', `salon_id = ${sqlLiteral(target.created.id)}`);
  const paymentsBefore = countRows('invoice_payment', `invoice_id IN (SELECT id FROM invoice WHERE salon_id = ${sqlLiteral(target.created.id)})`);
  const auditBefore = platformAudit(target.created.id).length;
  expect(invoicesBefore, 'the salon has an invoice').toBe(1);
  expect(paymentsBefore, 'and its payment').toBe(1);
  await clock.set(plusMs(closed.deleteAt!, -SECOND));
  await operator.runDeletions();
  expect(tenantRowCounts(target.created.id), 'nothing deleted one second earlier').toEqual(counts);
  await operator.renew(admin);
  expect((await operator.salonDetail(admin, target.created.id)).status).toBe('closed');
  await clock.set(closed.deleteAt!);
  const pass = await operator.runDeletions();
  expect(pass.salons.find(s => s.salonId === target.created.id)?.deleted, 'the salon was deleted in this pass').toBe(true);
  const after = tenantRowCounts(target.created.id);
  expect(Object.values(after).every(n => n === 0), `every tenant row removed: ${JSON.stringify(after)}`).toBe(true);
  await operator.renew(admin);
  const tombstone = await operator.salonDetail(admin, target.created.id);
  expect(tombstone).toMatchObject({ status: 'deleted', slug: target.created.slug });
  expect(tombstone.deletedAt).not.toBeNull();
  expect((await operator.exportStatus(admin, target.created.id)).status, 'the archive is removed').toBe('deleted');
  await operatorProblem(await operator.archiveRaw(admin, target.created.id), 404, 'not_found');
  expect(countRows('salon_export_part', `export_id IN (SELECT id FROM salon_export WHERE salon_id = ${sqlLiteral(target.created.id)})`)).toBe(0);
  expect(countRows('invoice', `salon_id = ${sqlLiteral(target.created.id)}`), 'invoices kept').toBe(invoicesBefore);
  expect(countRows('invoice_payment', `invoice_id IN (SELECT id FROM invoice WHERE salon_id = ${sqlLiteral(target.created.id)})`), 'payments kept').toBe(paymentsBefore);
  expect(platformAudit(target.created.id).length, 'platform audit kept').toBeGreaterThanOrEqual(auditBefore);
  expect(platformAudit(target.created.id).filter(e => e.action === 'salon.deleted').length, 'exactly one salon.deleted').toBe(1);
  expect({ other: tenantRowCounts(other.created.id), alpha: tenantRowCounts(alpha.id), beta: tenantRowCounts(beta.id), gamma: tenantRowCounts(gamma.id) },
    'nothing of any other salon').toEqual(others);
});

test(`${id(11)} An interrupted export or deletion resumes to the same final result`, async ({ operator, identity, clock }) => {
  test.setTimeout(120_000);
  const { admin, target, closed } = await arrange(operator, identity, clock, '2026-12-15T07:00:00Z');
  const counts = tenantRowCounts(target.created.id);
  await operator.armExportInterrupt(3);
  const interrupted = await operator.runExports();
  expect(interrupted.failed, 'the armed export failed').toContain(target.created.id);
  expect((await operator.exportStatus(admin, target.created.id)).status, 'not presented as ready').toBe('pending');
  await operatorProblem(await operator.archiveRaw(admin, target.created.id), 409, 'invalid_state');
  const view = await ready(operator, admin, target.created.id);
  const manifest = JSON.parse(unzip((await download(operator, admin, target.created.id)).bytes).get('manifest.json')!.toString('utf8')) as Manifest;
  expect(Object.fromEntries(manifest.tables.map(t => [t.name, t.rows])), 'the resumed archive is complete').toEqual(counts);
  expect(view.sha256).toMatch(/^[0-9a-f]{64}$/);
  await clock.set(closed.deleteAt!);
  await operator.armDeletionInterrupt(1);
  const stopped = await operator.runDeletions();
  expect(stopped.failed, 'the armed deletion failed').toContain(target.created.id);
  await operator.renew(admin);
  expect((await operator.salonDetail(admin, target.created.id)).status, 'not presented as deleted').toBe('closed');
  expect(Object.values(tenantRowCounts(target.created.id)).some(n => n > 0), 'rows remain after the interruption').toBe(true);
  await operator.runDeletions();
  expect(Object.values(tenantRowCounts(target.created.id)).every(n => n === 0), 'the resumed deletion removes every row').toBe(true);
  expect((await operator.salonDetail(admin, target.created.id)).status).toBe('deleted');
  expect(platformAudit(target.created.id).filter(e => e.action === 'salon.deleted').length).toBe(1);
  expect((await operator.lifecycle(admin, target.created.id)).items.filter(e => e.kind === 'deleted').length).toBe(1);
});

test(`${id(12)} Running export or deletion twice or concurrently produces one archive and one deletion`, async ({ operator, identity, clock }) => {
  test.setTimeout(120_000);
  const { admin, target, closed } = await arrange(operator, identity, clock, '2026-12-16T07:00:00Z');
  await Promise.all(Array.from({ length: 8 }, () => operator.runExports()));
  const first = await operator.exportStatus(admin, target.created.id);
  expect(first.status).toBe('ready');
  await operator.runExports();
  await operator.runExports();
  expect((await operator.exportStatus(admin, target.created.id)).sha256, 'one archive').toBe(first.sha256);
  await operator.emailsOfKind(target.input.owner.email, 'export.ready', 1);
  expect(platformAudit(target.created.id).filter(e => e.action === 'export.ready').length, 'one ready record').toBe(1);
  expect(countRows('salon_export', `salon_id = ${sqlLiteral(target.created.id)}`), 'one export').toBe(1);
  await clock.set(closed.deleteAt!);
  await Promise.all(Array.from({ length: 8 }, () => operator.runDeletions()));
  await operator.runDeletions();
  expect(Object.values(tenantRowCounts(target.created.id)).every(n => n === 0)).toBe(true);
  expect(platformAudit(target.created.id).filter(e => e.action === 'salon.deleted').length, 'one deletion').toBe(1);
  await operator.renew(admin);
  expect((await operator.lifecycle(admin, target.created.id)).items.filter(e => e.kind === 'deleted').length, 'one deleted event').toBe(1);
});

test(`${id(14)} Lifecycle, export, email and deletion are audited without content, tokens or personal fields`, async ({ operator, identity, clock }) => {
  test.setTimeout(180_000);
  await clock.set('2026-12-17T07:00:00Z');
  const admin = await operator.actor();
  const plan = await operator.plan(admin);
  const target = await populated(operator, identity, admin, plan.id);
  const offsets = logOffsets();
  const reasons = { suspend: 'QA audited suspension', reactivate: 'QA audited reactivation', close: 'QA audited closure' };
  await operatorJson(await operator.suspend(admin, target.created.id, await operator.version(admin, target.created.id), reasons.suspend), 'salon');
  await operatorJson(await operator.reactivate(admin, target.created.id, await operator.version(admin, target.created.id), reasons.reactivate), 'salon');
  const closed = await operatorJson<SalonDetail>(await operator.close(admin, target.created.id, await operator.version(admin, target.created.id), target.created.slug, reasons.close), 'salon');
  await ready(operator, admin, target.created.id);
  const token = await exportToken(operator, target.input.owner.email);
  const archive = await operator.linkRaw(token);
  expect(archive.status()).toBe(200);
  const bytes = await archive.body();
  await download(operator, admin, target.created.id);
  await clock.set(closed.deleteAt!);
  await operator.runDeletions();
  const audit = platformAudit(target.created.id);
  const actions = audit.map(e => e.action);
  for (const action of ['salon.suspended', 'salon.reactivated', 'salon.closed', 'export.created', 'export.ready', 'export.email_sent', 'salon.deleted']) {
    expect(actions.filter(a => a === action).length, `${action} recorded once`).toBe(1);
  }
  expect(actions.filter(a => a === 'export.downloaded').length, 'both downloads').toBe(2);
  for (const entry of audit) {
    expect(['operator', 'system'], `${entry.action}: actor`).toContain(entry.actorKind);
    expect(entry.traceId, `${entry.action}: trace id`).toMatch(/^[0-9a-f]{32}$/);
  }
  const byAction = (a: string) => audit.find(e => e.action === a)!.details;
  expect(byAction('salon.suspended')).toContain(reasons.suspend);
  expect(byAction('salon.reactivated')).toContain(reasons.reactivate);
  expect(byAction('salon.closed')).toContain(reasons.close);
  const details = audit.map(e => e.details).join('\n');
  const logs = logsSince(offsets);
  for (const secret of [token, sha256(token), createHash('sha256').update(token).digest('base64'), target.input.owner.email, target.input.contactEmail!,
    target.customer.mobile]) {
    expect(details.includes(secret), 'no token, hash or personal field in the audit').toBe(false);
    expect(logs.includes(secret), 'no token, hash or personal field in the logs').toBe(false);
  }
  expect(details.includes(bytes.subarray(0, 64).toString('base64')), 'no archive content').toBe(false);
  await operator.renew(admin);
  const history = (await operator.lifecycle(admin, target.created.id)).items;
  expect(history.map(e => e.kind).sort()).toEqual(['closed', 'deleted', 'reactivated', 'suspended']);
  expect(history.find(e => e.kind === 'suspended')).toMatchObject({ reason: reasons.suspend, actor: { kind: 'operator', id: admin.principal.id } });
  expect(history.find(e => e.kind === 'deleted')!.actor.kind).toBe('system');
});

