/**
 * F03-S01 platform audit: operator security events and creations recorded with actor, subject,
 * application time and trace id, nothing for a rolled-back creation, and no credential or personal
 * field in audit, receipts, logs or error bodies. Case TC-F03-S01-029, API half (the database
 * half, that rows cannot be changed or removed, is in qa/checks/F03/S01-database-boundary.sh).
 */
import { randomUUID } from 'node:crypto';

import { identitySql } from '../../shared/identity-storage';
import { logOffsets, logsSince } from '../../shared/product-logs';
import {
  test, expect, operatorAccount, operatorProblem, platformAuditWhere, salonInput, sqlLiteral,
} from '../../shared/operator';

test('TC-F03-S01-029 Platform audit records operator and creation events immutably and without secrets', async ({ operator, clock }) => {
  const fixed = '2026-11-21T06:00:00.000Z';
  await clock.set(fixed);
  const offsets = logOffsets();
  const account = operatorAccount('OPERATOR_ADMIN');
  await operator.seed(account);
  const errors: string[] = [];
  const failed = await operator.signInRaw({ email: account.email, password: `${account.password}-wrong` });
  await operatorProblem(failed, 401, 'invalid_credentials');
  errors.push(await failed.text());
  const admin = await operator.login(account);
  const plan = await operator.plan(admin);
  const made = await operator.salon(admin, plan.id);
  await operator.bootstrapFailure('brand');
  const rolledBack = salonInput(plan.id);
  const failure = await operator.createSalonRaw(admin, rolledBack, randomUUID());
  await operatorProblem(failure, 500, 'internal_error');
  errors.push(await failure.text());
  await operator.bootstrapFailure(null);
  expect((await operator.signOut(admin.tokens)).status()).toBe(204);

  const audit = platformAuditWhere(`created_at >= ${sqlLiteral(fixed)} AND (actor_id = ${sqlLiteral(admin.principal.id)} OR subject_id IN (${
    [admin.principal.id, plan.id, made.created.id].map(sqlLiteral).join(', ')}))`);
  const actions = audit.map(e => e.action);
  // Action names as recorded (the spec does not name them; the S01 handoff abbreviates the operator. prefix).
  for (const action of ['operator.sign_in_failed', 'operator.signed_in', 'operator.signed_out', 'plan.created', 'salon.created']) {
    expect(actions.filter(a => a === action).length, `${action} recorded once`).toBe(1);
  }
  expect(audit.find(e => e.action === 'plan.created')!.subjectId).toBe(plan.id);
  expect(audit.find(e => e.action === 'salon.created')!.subjectId).toBe(made.created.id);
  expect(platformAuditWhere(`action = 'salon.created' AND details::text LIKE ${sqlLiteral(`%${rolledBack.slug}%`)}`), 'no success record for the rolled-back creation').toEqual([]);
  for (const entry of audit) {
    expect(entry.traceId, `${entry.action}: trace id`).toMatch(/^[0-9a-f]{32}$/);
    const offset = Date.parse(entry.at) - Date.parse(fixed);
    expect(offset >= 0 && offset < 120_000, `${entry.action}: application-clock time`).toBe(true);
  }
  const receipts = identitySql(`SELECT coalesce(string_agg(result::text || ' ' || fingerprint, ' '), '') FROM provisioning_receipt WHERE operator_id = ${sqlLiteral(admin.principal.id)}`);
  expect(receipts.length, 'receipts exist to search').toBeGreaterThan(0);
  const secrets = [account.password, account.totpSecret!, admin.tokens.accessToken, admin.tokens.refreshToken, made.input.owner.password,
    made.input.contactEmail!, made.input.contactPhone!, made.input.address!, made.input.owner.email, rolledBack.owner.password];
  const places: [string, string][] = [['audit', audit.map(e => e.details).join('\n')], ['receipts', receipts], ['logs', logsSince(offsets)], ['errors', errors.join('\n')]];
  for (const [place, text] of places) {
    for (const [n, secret] of secrets.entries()) expect(text.includes(secret), `${place}: secret or personal value #${n} absent`).toBe(false);
  }
});
