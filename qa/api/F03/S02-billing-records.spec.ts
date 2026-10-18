/**
 * F03-S02 billing records: every billing event in the platform audit with its actor and trace,
 * and no provider secret or credential in audit, logs, emails or errors.
 * Case TC-F03-S02-023 (cases.md).
 *
 * The provider key value is read from the retained local key file and never printed; matches are
 * reported as booleans only.
 */
import fs from 'node:fs';
import path from 'node:path';
import { randomUUID } from 'node:crypto';

import { attempts, crossBoundary, firstInvoice, settled } from '../../shared/billing';
import { PRODUCT_ROOT } from '../../shared/env';
import { identitySql } from '../../shared/identity-storage';
import { logOffsets, logsSince } from '../../shared/product-logs';
import {
  test, expect, operatorJson, operatorProblem, platformAuditWhere, sqlLiteral,
} from '../../shared/operator';

function providerKeyValues(): string[] {
  const file = process.env.SALON_BILLING_LOCAL_KEY_FILE ?? path.join(PRODUCT_ROOT, '.local', 'billing-keys.properties');
  if (!fs.existsSync(file)) throw new Error('ENVIRONMENT: the retained local billing key file is absent');
  const values = fs.readFileSync(file, 'utf8').split('\n').map(l => l.split('=')[1]?.trim() ?? '').filter(v => /^[0-9a-fA-F]{64}$/.test(v));
  if (!values.length) throw new Error('ENVIRONMENT: no provider key value in the retained billing key file');
  return values;
}

test('TC-F03-S02-023 Billing events are audited with actor or system and no secret reaches audit, logs, emails or errors', async ({ operator, clock }) => {
  test.setTimeout(240_000);
  await clock.set('2026-11-20T07:00:00Z');
  const offsets = logOffsets();
  const admin = await operator.actor();
  const plan = await operator.plan(admin);
  const larger = await operator.plan(admin, { staffSeats: 20 });
  const { created, input } = await operator.salon(admin, plan.id, { subscription: { monthlyAmount: 210000, paymentMethod: 'card' } });
  const owner = input.owner.email;
  const errors: string[] = [];
  let version = (await operator.subscription(admin, created.id)).version;
  for (const change of [{ monthlyAmount: 220000 }, { paymentMethod: 'bank_transfer' }, { paymentMethod: 'card' }]) {
    version = (await operatorJson<{ version: number }>(await operator.changeSubscription(admin, created.id, { version, ...change }), 'subscription')).version;
  }
  await operatorJson(await operator.movePlan(admin, created.id, await operator.version(admin, created.id), larger.id), 'salon');
  await operator.providerOutcome(created.id, 'failure');
  const invoice = await firstInvoice(operator, clock, admin, created.id);
  await settled(operator, admin, invoice.id, 'open');
  const forged = JSON.stringify({ eventId: `evt_${randomUUID()}`, invoiceId: invoice.id, attemptKey: attempts(invoice.id)[0].attemptKey, outcome: 'success', occurredAt: new Date().toISOString() });
  const refused = await operator.postEvent(forged, 'f'.repeat(64));
  await operatorProblem(refused, 401, 'invalid_credentials');
  errors.push(await refused.text());
  const badTransfer = await operator.recordTransfer(admin, invoice.id, { reference: 'QA', amount: 1, receivedOn: '2026-01-01' });
  await operatorProblem(badTransfer, 400, 'bad_request');
  errors.push(await badTransfer.text());
  await crossBoundary(operator, clock, admin, invoice, 'due');
  await crossBoundary(operator, clock, admin, invoice, 'due_3');
  await crossBoundary(operator, clock, admin, invoice, 'due_10');
  await crossBoundary(operator, clock, admin, invoice, 'read_only');
  expect((await operator.subscription(admin, created.id)).state).toBe('read_only');
  const key = attempts(invoice.id).at(-1)!.attemptKey;
  const success = JSON.stringify({ eventId: `evt_${randomUUID()}`, invoiceId: invoice.id, attemptKey: key, outcome: 'success', occurredAt: new Date().toISOString() });
  const signature = await operator.sign(success);
  expect((await operatorJson<{ result: string }>(await operator.postEvent(success, signature), 'acknowledged')).result).toBe('paid');
  expect((await operator.subscription(admin, created.id)).state).toBe('active');

  const subscriptionId = identitySql(`SELECT id FROM subscription WHERE salon_id = ${sqlLiteral(created.id)}`);
  const subjects = [created.id, subscriptionId, invoice.id].map(sqlLiteral).join(', ');
  const audit = platformAuditWhere(`subject_id IN (${subjects}) OR details::text LIKE ${sqlLiteral(`%${invoice.id}%`)}`);
  const actions = new Set(audit.map(e => e.action));
  for (const action of ['subscription.started', 'subscription.changed', 'salon.plan_changed', 'invoice.issued', 'charge.requested', 'charge.failed',
    'provider_event.received', 'payment.recorded', 'subscription.state_changed', 'billing.reminder_sent']) {
    expect(actions.has(action), `${action} is in the platform audit`).toBe(true);
  }
  for (const entry of audit) {
    if (entry.actorKind === 'operator') expect(entry.actorId, `${entry.action}: the Admin`).toBe(admin.principal.id);
    else expect(entry.actorKind, `${entry.action}: system`).toBe('system');
    expect(entry.traceId, `${entry.action}: trace id`).toMatch(/^[0-9a-f]{32}$/);
  }
  const reminders = audit.filter(e => e.action === 'billing.reminder_sent');
  expect(reminders.length, 'each reminder kind recorded once').toBeGreaterThanOrEqual(4);

  // AC-14: no provider secret or credential (the provider key; operator and Owner passwords and tokens).
  const secrets = [...providerKeyValues(), admin.account.password, admin.account.totpSecret!, admin.tokens.accessToken, admin.tokens.refreshToken,
    input.owner.password];
  const places: [string, string][] = [
    ['audit', audit.map(e => e.details).join('\n')],
    ['logs', logsSince(offsets)],
    ['emails', JSON.stringify(await operator.emails(owner))],
    ['errors', errors.join('\n')],
  ];
  expect(places[2][1].length, 'emails were captured to search').toBeGreaterThan(2);
  for (const [place, text] of places) {
    for (const [n, secret] of secrets.entries()) expect(text.includes(secret), `${place}: secret #${n} absent`).toBe(false);
  }
});
