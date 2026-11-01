/**
 * F04-S04 booking rules from outside: the public read (a JSON null before the first save), the
 * settings read and save with every allowed value and every boundary, immutable versions and their
 * history, identical, stale and simultaneous saves, roles, tenant isolation through the API and at
 * the database, a support session, suspended and read-only salons, the audit trail, and the
 * salon's currency.
 *
 * Expected results are the F04-S04 spec's (acceptance criteria, "API and data changes", "Design
 * notes", "Corrections at close"), decision 091, docs/qa/test-types.md and the generated contract.
 * A salon keeps every version for ever, so a test that must see "never saved" uses a salon the
 * operator creates now (shared/booking-rules.ts).
 */
import { readOnlySalon } from '../../shared/billing';
import {
  ALLOWED, BookingRules, PUBLIC_RULES, RULE_NAMES, RULES_HISTORY, RULES_SETTINGS, RULES_TABLE, SUGGESTED,
  distinct, fieldErrors, refused, rules, rulesAudit, rulesSnapshot, stored, versionRow, versionRows,
  type RulesInput,
} from '../../shared/booking-rules';
import type { Identity, Role, Tokens } from '../../shared/identity';
import { identitySql } from '../../shared/identity-storage';
import {
  test, expect, alpha, beta, operatorJson, ownerSession, sqlLiteral, supportJson,
  type Operator, type SupportStart,
} from '../../shared/operator';
import { logOffsets, logsSince } from '../../shared/product-logs';

test.describe.configure({ timeout: 120_000 });

/** A salon the operator creates now, with its Owner signed in (MFA enrolled). */
async function newSalon(operator: Operator, identity: Identity, overrides: { currency?: string } = {}) {
  const admin = await operator.actor();
  const plan = await operator.plan(admin);
  const { input, created, salon } = await operator.salon(admin, plan.id, overrides);
  const owner = await ownerSession(identity, salon, input);
  return { admin, input, created, salon, owner };
}

/** Every settings route with a body to send, the save marked as the one write. */
const settingsRoutes = (version: number) => [
  { label: 'settings', method: 'GET', path: RULES_SETTINGS, data: undefined, write: false },
  { label: 'history', method: 'GET', path: RULES_HISTORY, data: undefined, write: false },
  { label: 'save', method: 'PUT', path: RULES_SETTINGS, data: { version, rules: distinct() }, write: true },
];

test('TC-F04-S04-001 AC-1, AC-5: before the first save the public read is a JSON null and the settings say version 0 with no rules in LKR; saving the suggested rules makes version 1 and the next public read returns them', async ({ identity, operator }) => {
  const book = new BookingRules(identity);
  const { salon, owner } = await newSalon(operator, identity);
  await test.step('never saved', async () => {
    const pub = await book.publicRaw(salon);
    expect(pub.status, 'public: no credential needed').toBe(200);
    expect(pub.contentType).toBe('application/json');
    expect(pub.text.trim(), 'the body is the JSON literal null').toBe('null');
    const settings = await book.settings(salon, owner.tokens);
    expect(settings).toEqual({ version: 0, rules: null, currency: 'LKR' });
    expect((await book.history(salon, owner.tokens)).total, 'no versions').toBe(0);
    expect(versionRows(salon.id), 'nothing is stored before the first save').toBe(0);
  });
  await test.step('Save booking rules with the suggested rules', async () => {
    const saved = await book.save(salon, owner.tokens, SUGGESTED, 0);
    expect(saved).toEqual({ version: 1, rules: stored(SUGGESTED), currency: 'LKR' });
    const pub = await book.published(salon);
    expect(pub.rules, 'the next public read returns the saved rules').toEqual(stored(SUGGESTED));
    expect(pub.currency).toBe('LKR');
    const [entry] = (await book.history(salon, owner.tokens)).items;
    expect(pub.versionId, 'the public read names the version just made').toBe(entry.versionId);
    expect(identitySql(`SELECT deposit_cap_minor || ' ' || currency FROM ${RULES_TABLE} WHERE tenant_id = ${sqlLiteral(salon.id)}`),
      'Rs. 2,000 is stored as 200000 minor units in LKR').toBe('200000 LKR');
  });
});

test('TC-F04-S04-002 AC-2: every interval, notice and horizon of the prototype is accepted and read back, and any other value is a 400 on that field that saves nothing', async ({ identity }) => {
  const book = new BookingRules(identity);
  const manager = await identity.staff(alpha, 'MANAGER');
  const choices: [keyof RulesInput, number[]][] = [
    ['slotIntervalMinutes', ALLOWED.interval], ['minimumNoticeMinutes', ALLOWED.notice], ['bookingHorizonDays', ALLOWED.horizon],
  ];
  for (const [field, values] of choices) {
    await test.step(`${field}: ${values.join(', ')} accepted`, async () => {
      for (const value of values) {
        const input = distinct({ [field]: value });
        const saved = await book.save(alpha, manager.tokens, input);
        expect(saved.rules?.[field], `${field} ${value} saved`).toBe(value);
        expect((await book.published(alpha)).rules[field], `${field} ${value} public`).toBe(value);
      }
    });
  }
  const refusedRows: [keyof RulesInput, unknown[]][] = [
    ['slotIntervalMinutes', [0, 5, 25, 45, 60, '15', null]],
    ['minimumNoticeMinutes', [-30, 15, 45, 90, 2880, '60', null]],
    ['bookingHorizonDays', [0, 7, 21, 31, 61, 365, '60', null]],
  ];
  for (const [field, values] of refusedRows) {
    await test.step(`${field}: ${values.map(v => JSON.stringify(v)).join(', ')} refused`, async () => {
      for (const value of values) {
        const { version } = await book.settings(alpha, manager.tokens);
        const before = rulesSnapshot(alpha.id);
        const { errors } = await fieldErrors(await book.saveRaw(alpha, manager.tokens, version, { ...distinct(), [field]: value }), `${field} ${JSON.stringify(value)}`);
        expect(errors.length, `${field} ${JSON.stringify(value)}: names the field`).toBeGreaterThan(0);
        for (const e of errors) expect(e.field, `${field} ${JSON.stringify(value)}: the error is on that field`).toMatch(new RegExp(`(^|\\.)${field}$`));
        expect(rulesSnapshot(alpha.id), `${field} ${JSON.stringify(value)}: nothing saved`).toBe(before);
      }
    });
  }
});

test('TC-F04-S04-003 AC-3: every deposit, cancellation, no-show and rescheduling choice and every accepted boundary is saved, stored in minor units with no rounding and served publicly as saved', async ({ identity }) => {
  const book = new BookingRules(identity);
  const manager = await identity.staff(alpha, 'MANAGER');
  const accepted: { label: string; input: RulesInput; columns?: string }[] = [
    { label: 'no deposit', input: rules({ deposit: { kind: 'none' }, lateCancellation: { kind: 'nothing' }, freeCancellationHours: 2 }) },
    { label: 'percent 1, cap Rs. 500', input: rules({ deposit: { kind: 'percentage', percent: 1, capMinor: 50000 } }), columns: '1 50000 -' },
    { label: 'percent 100, a large cap', input: rules({ deposit: { kind: 'percentage', percent: 100, capMinor: 123456789 * 100 } }), columns: `100 ${123456789 * 100} -` },
    { label: 'fixed Rs. 100', input: rules({ deposit: { kind: 'amount', amountMinor: 10000 } }), columns: '- - 10000' },
    { label: 'fixed Rs. 100,000', input: rules({ deposit: { kind: 'amount', amountMinor: 10000000 } }), columns: '- - 10000000' },
    { label: 'fixed Rs. 1,234', input: rules({ deposit: { kind: 'amount', amountMinor: 123400 } }), columns: '- - 123400' },
    { label: 'late share 1%, no-show share 100%', input: distinct({ lateCancellation: { kind: 'share', percent: 1 }, noShow: { kind: 'share', percent: 100 } }) },
    { label: 'late share 100%, no-show share 1%', input: distinct({ lateCancellation: { kind: 'share', percent: 100 }, noShow: { kind: 'share', percent: 1 } }) },
    { label: 'rescheduling not online', input: distinct({ reschedule: { limit: 0, untilHours: null } }) },
    { label: 'rescheduling once', input: distinct({ reschedule: { limit: 1, untilHours: 2 } }) },
    { label: 'rescheduling any number of times', input: distinct({ reschedule: { limit: null, untilHours: 24 } }) },
    ...ALLOWED.free.map(hours => ({ label: `free cancellation ${hours} hours`, input: distinct({ freeCancellationHours: hours }) })),
    ...ALLOWED.until.map(hours => ({ label: `reschedule up to ${hours} hours`, input: distinct({ reschedule: { limit: 2, untilHours: hours } }) })),
  ];
  for (const row of accepted) {
    await test.step(row.label, async () => {
      let current = await book.settings(alpha, manager.tokens);
      // alpha keeps its rules between runs: move off a fixed row's values first, so its save is never an identical one.
      if (JSON.stringify(current.rules) === JSON.stringify(stored(row.input))) current = await book.save(alpha, manager.tokens, distinct(), current.version);
      const saved = await book.save(alpha, manager.tokens, row.input, current.version);
      expect(saved.rules, `${row.label}: saved as sent`).toEqual(stored(row.input));
      const pub = await book.published(alpha);
      expect(pub.rules, `${row.label}: served publicly as saved (contract-checked)`).toEqual(stored(row.input));
      expect(pub.currency).toBe('LKR');
      if (row.columns) {
        expect(identitySql(`SELECT coalesce(deposit_percent::text, '-') || ' ' || coalesce(deposit_cap_minor::text, '-') || ' ' || coalesce(deposit_amount_minor::text, '-')
          FROM ${RULES_TABLE} WHERE tenant_id = ${sqlLiteral(alpha.id)} AND id = ${sqlLiteral(pub.versionId)}`), `${row.label}: the stored minor units`).toBe(row.columns);
      }
    });
  }
});

test('TC-F04-S04-004 AC-3: a percentage of 0 or 101, a cap under Rs. 500, an amount under Rs. 100 or over Rs. 100,000, part of a rupee, a field of another kind, an unlisted window or retention, or an impossible rescheduling is a 400 on that field that saves nothing', async ({ identity }) => {
  const book = new BookingRules(identity);
  const manager = await identity.staff(alpha, 'MANAGER');
  const pct = (percent: unknown, capMinor: unknown = 200000) => ({ deposit: { kind: 'percentage', percent, capMinor } });
  const rows: { label: string; change: Record<string, unknown>; field: RegExp }[] = [
    { label: 'percent 0', change: pct(0), field: /deposit\.percent$/ },
    { label: 'percent 101', change: pct(101), field: /deposit\.percent$/ },
    { label: 'percent missing', change: { deposit: { kind: 'percentage', capMinor: 200000 } }, field: /deposit\.percent$/ },
    { label: 'percent 20.5', change: pct(20.5), field: /deposit\.percent$/ },
    { label: 'cap Rs. 499', change: pct(20, 49900), field: /deposit\.capMinor$/ },
    { label: 'cap Rs. 500.50 (part of a rupee)', change: pct(20, 50050), field: /deposit\.capMinor$/ },
    { label: 'cap above the safe-integer bound', change: pct(20, 9007199254740992), field: /deposit\.capMinor$/ },
    { label: 'cap missing', change: { deposit: { kind: 'percentage', percent: 20 } }, field: /deposit\.capMinor$/ },
    { label: 'amount Rs. 99', change: { deposit: { kind: 'amount', amountMinor: 9900 } }, field: /deposit\.amountMinor$/ },
    { label: 'amount Rs. 100,001', change: { deposit: { kind: 'amount', amountMinor: 10000100 } }, field: /deposit\.amountMinor$/ },
    { label: 'amount Rs. 100.50 (part of a rupee)', change: { deposit: { kind: 'amount', amountMinor: 10050 } }, field: /deposit\.amountMinor$/ },
    { label: 'amount with a percentage beside it', change: { deposit: { kind: 'amount', amountMinor: 100000, percent: 20 } }, field: /deposit(\.percent)?$/ },
    { label: 'no deposit with a cap beside it', change: { deposit: { kind: 'none', capMinor: 50000 } }, field: /deposit(\.capMinor)?$/ },
    { label: 'an unknown deposit kind', change: { deposit: { kind: 'flat', amountMinor: 100000 } }, field: /deposit(\.kind)?$/ },
    { label: 'free cancellation 0 hours', change: { freeCancellationHours: 0 }, field: /(^|\.)freeCancellationHours$/ },
    { label: 'free cancellation 72 hours', change: { freeCancellationHours: 72 }, field: /(^|\.)freeCancellationHours$/ },
    { label: 'late share 0%', change: { lateCancellation: { kind: 'share', percent: 0 } }, field: /lateCancellation\.percent$/ },
    { label: 'late share 101%', change: { lateCancellation: { kind: 'share', percent: 101 } }, field: /lateCancellation\.percent$/ },
    { label: 'late keeps nothing with a share beside it', change: { lateCancellation: { kind: 'nothing', percent: 50 } }, field: /lateCancellation(\.percent)?$/ },
    { label: 'no-show keeps nothing', change: { noShow: { kind: 'nothing' } }, field: /noShow(\.kind)?$/ },
    { label: 'no-show share 0%', change: { noShow: { kind: 'share', percent: 0 } }, field: /noShow\.percent$/ },
    { label: 'no-show share 101%', change: { noShow: { kind: 'share', percent: 101 } }, field: /noShow\.percent$/ },
    { label: 'reschedule 3 times', change: { reschedule: { limit: 3, untilHours: 12 } }, field: /reschedule\.limit$/ },
    { label: 'reschedule up to 48 hours', change: { reschedule: { limit: 2, untilHours: 48 } }, field: /reschedule\.untilHours$/ },
    { label: 'reschedule with no time', change: { reschedule: { limit: 1, untilHours: null } }, field: /reschedule\.untilHours$/ },
    { label: 'not online with a time', change: { reschedule: { limit: 0, untilHours: 12 } }, field: /reschedule\.untilHours$/ },
    { label: 'an unknown rule', change: { refundDays: 3 }, field: /refundDays$/ },
  ];
  for (const row of rows) {
    await test.step(row.label, async () => {
      const { version } = await book.settings(alpha, manager.tokens);
      const before = rulesSnapshot(alpha.id);
      const { errors, body } = await fieldErrors(await book.saveRaw(alpha, manager.tokens, version, { ...distinct(), ...row.change }), row.label);
      expect(errors.length, `${row.label}: names the field`).toBeGreaterThan(0);
      for (const e of errors) expect(e.field, `${row.label}: the error is on that field (${body.slice(0, 300)})`).toMatch(row.field);
      expect(rulesSnapshot(alpha.id), `${row.label}: nothing saved`).toBe(before);
    });
  }
});

test('TC-F04-S04-005 AC-5: each save makes a new current version and leaves the earlier ones unchanged; an identical save is 409 invalid_state with no version, and a stale version is 409 conflict that changes nothing', async ({ identity, operator }) => {
  const book = new BookingRules(identity);
  const { salon, owner } = await newSalon(operator, identity);
  const first = await book.save(salon, owner.tokens, SUGGESTED, 0);
  const v1 = (await book.published(salon)).versionId;
  const v1Row = versionRow(salon.id, v1);
  const second = rules({ deposit: { kind: 'amount', amountMinor: 150000 }, reschedule: { limit: 0, untilHours: null } });
  const saved = await book.save(salon, owner.tokens, second, first.version);
  expect(saved.version, 'the next version').toBe(2);
  const pub = await book.published(salon);
  expect(pub.rules, 'the new version is current').toEqual(stored(second));
  expect(pub.versionId, 'with a new version id').not.toBe(v1);
  expect(versionRow(salon.id, v1), 'version 1 is unchanged').toBe(v1Row);
  const history = await book.history(salon, owner.tokens);
  expect(history.items.map(i => i.versionId), 'both versions are in the history, newest first').toEqual([pub.versionId, v1]);
  await test.step('saving values identical to the current version', async () => {
    const before = rulesSnapshot(salon.id);
    const audits = rulesAudit(salon.id).length;
    await refused(await book.saveRaw(salon, owner.tokens, 2, second), 409, 'invalid_state', 'identical save');
    expect(rulesSnapshot(salon.id), 'no version created').toBe(before);
    expect((await book.history(salon, owner.tokens)).total).toBe(2);
    expect(rulesAudit(salon.id).length, 'nothing audited').toBe(audits);
  });
  await test.step('a stale version', async () => {
    const before = rulesSnapshot(salon.id);
    await refused(await book.saveRaw(salon, owner.tokens, 1, distinct()), 409, 'conflict', 'stale save');
    await refused(await book.saveRaw(salon, owner.tokens, 0, distinct()), 409, 'conflict', 'save as if never saved');
    await refused(await book.saveRaw(salon, owner.tokens, 7, distinct()), 409, 'conflict', 'a version from the future');
    expect(rulesSnapshot(salon.id), 'nothing changed').toBe(before);
    expect((await book.published(salon)).rules, 'the current rules are as they were').toEqual(stored(second));
  });
});

test('TC-F04-S04-006 AC-5: of eight saves of one version sent at once exactly one succeeds, the rest are 409 conflict, and exactly one version is added', async ({ identity }) => {
  const book = new BookingRules(identity);
  const people = [await identity.staff(alpha, 'MANAGER'), await identity.staff(alpha, 'OWNER')];
  const current = await book.settings(alpha, people[0].tokens);
  const rows = versionRows(alpha.id);
  const inputs = Array.from({ length: 8 }, () => distinct());
  const responses = await Promise.all(inputs.map((input, n) => book.saveRaw(alpha, people[n % 2].tokens, current.version, input)));
  const ok = responses.map((r, n) => ({ r, n })).filter(x => x.r.status() === 200);
  expect(ok.length, 'exactly one save').toBe(1);
  for (const r of responses.filter(r => r.status() !== 200)) await refused(r, 409, 'conflict', 'losing save');
  expect(versionRows(alpha.id), 'exactly one version added').toBe(rows + 1);
  const after = await book.settings(alpha, people[0].tokens);
  expect(after.version).toBe(current.version + 1);
  expect(after.rules, 'the winner\'s rules, whole').toEqual(stored(inputs[ok[0].n]));
});

test('TC-F04-S04-007 AC-6, AC-5: a version\'s stored row is unchanged after three later saves, the application role can neither change nor delete one, and a salon\'s context never reaches another salon\'s version by id', async ({ identity, operator }) => {
  const book = new BookingRules(identity);
  const { salon, owner } = await newSalon(operator, identity);
  await book.save(salon, owner.tokens, SUGGESTED, 0);
  const v1 = (await book.published(salon)).versionId;
  const original = versionRow(salon.id, v1);
  expect(original, 'version 1 is stored').toContain(v1);
  for (const input of [distinct(), distinct({ deposit: { kind: 'none' } }), distinct({ noShow: { kind: 'share', percent: 40 } })]) await book.save(salon, owner.tokens, input);
  expect((await book.settings(salon, owner.tokens)).version, 'three later saves').toBe(4);
  expect(versionRow(salon.id, v1), 'version 1 has its original values exactly').toBe(original);
  expect(JSON.parse(original)).toMatchObject({ version: 1, slot_interval_minutes: 15, minimum_notice_minutes: 60, booking_horizon_days: 60,
    deposit_kind: 'percentage', deposit_percent: 20, deposit_cap_minor: 200000, free_cancellation_hours: 24, late_cancellation_kind: 'deposit',
    no_show_kind: 'deposit', reschedule_limit: 2, reschedule_until_hours: 12, currency: 'LKR' });
  await test.step('the application role cannot change or delete a version, even its own salon\'s', async () => {
    const update = identitySql(`UPDATE ${RULES_TABLE} SET deposit_percent = 99 WHERE id = ${sqlLiteral(v1)}`, salon.id, false);
    expect(update, 'an update is refused for want of the grant').toMatch(/ERROR:\s+permission denied for table booking_rules_version/);
    const del = identitySql(`DELETE FROM ${RULES_TABLE} WHERE id = ${sqlLiteral(v1)}`, salon.id, false);
    expect(del, 'a delete is refused for want of the grant').toMatch(/ERROR:\s+permission denied for table booking_rules_version/);
    expect(versionRow(salon.id, v1), 'still the original').toBe(original);
  });
  await test.step('another salon\'s context does not reach the version by id', async () => {
    expect(identitySql(`SELECT count(*) FROM ${RULES_TABLE} WHERE id = ${sqlLiteral(v1)}`, salon.id), 'its own salon sees it').toBe('1');
    expect(identitySql(`SELECT count(*) FROM ${RULES_TABLE} WHERE id = ${sqlLiteral(v1)}`, alpha.id), 'alpha does not').toBe('0');
  });
});

test('TC-F04-S04-008 AC-7: the history lists each version newest first with its time, author id and label and the rules it changed, paged by page and size', async ({ identity, operator }) => {
  const book = new BookingRules(identity);
  const { salon, owner, input } = await newSalon(operator, identity);
  const t0 = await identity.now();
  await book.save(salon, owner.tokens, SUGGESTED, 0);
  await book.save(salon, owner.tokens, rules({ deposit: { kind: 'none' }, reschedule: { limit: null, untilHours: 6 } }));
  await book.save(salon, owner.tokens, rules({ deposit: { kind: 'none' }, reschedule: { limit: null, untilHours: 6 }, slotIntervalMinutes: 30 }));
  const t1 = await identity.now();
  const history = await book.history(salon, owner.tokens);
  expect(history).toMatchObject({ page: 0, size: 20, total: 3 });
  expect(history.items.map(i => i.version), 'newest first').toEqual([3, 2, 1]);
  for (const item of history.items) {
    expect(item.createdBy, `v${item.version}: the author's staff account`).toBe(owner.principal.id);
    expect(item.createdByLabel, `v${item.version}: the author's email, as the staff list shows it`).toBe(input.owner.email);
    expect(Date.parse(item.createdAt)).toBeGreaterThanOrEqual(Date.parse(t0) - 1000);
    expect(Date.parse(item.createdAt)).toBeLessThanOrEqual(Date.parse(t1) + 1000);
  }
  expect([...history.items[2].changedFields].sort(), 'the first version: every rule').toEqual([...RULE_NAMES].sort());
  expect([...history.items[1].changedFields].sort(), 'the second: the deposit and rescheduling').toEqual(['deposit', 'reschedule']);
  expect(history.items[0].changedFields, 'the third: the start times').toEqual(['slotIntervalMinutes']);
  await test.step('pages', async () => {
    const first = await book.history(salon, owner.tokens, '?page=0&size=2');
    const second = await book.history(salon, owner.tokens, '?page=1&size=2');
    expect(first).toMatchObject({ page: 0, size: 2, total: 3 });
    expect(first.items.map(i => i.version)).toEqual([3, 2]);
    expect(second.items.map(i => i.version)).toEqual([1]);
    expect((await book.history(salon, owner.tokens, '?page=5&size=2')).items, 'past the end: empty').toEqual([]);
    for (const query of ['?size=0', '?size=101', '?page=-1', '?size=x']) {
      await refused(await book.historyRaw(salon, owner.tokens, query), 400, 'bad_request', `history ${query}`);
    }
  });
});

test('TC-F04-S04-009 AC-7: the Owner and Manager read and save the rules and history; the Receptionist, Professional and Accountant are 403 permission_denied and signed out is 401, changing nothing; everyone reads the public rules', async ({ identity }) => {
  const book = new BookingRules(identity);
  const expected: Record<Role, 'allowed' | 'refused'> = { OWNER: 'allowed', MANAGER: 'allowed', RECEPTIONIST: 'refused', PROFESSIONAL: 'refused', ACCOUNTANT: 'refused' };
  for (const role of Object.keys(expected) as Role[]) {
    await test.step(`${role}: ${expected[role]}`, async () => {
      const actor = await identity.staff(alpha, role);
      const pub = await book.publicRaw(alpha, actor.tokens);
      expect(pub.status, `${role} reads the public rules`).toBe(200);
      if (expected[role] === 'allowed') {
        const current = await book.settings(alpha, actor.tokens);
        await book.history(alpha, actor.tokens);
        const input = distinct();
        expect((await book.save(alpha, actor.tokens, input, current.version)).rules).toEqual(stored(input));
        return;
      }
      const { version } = await book.settings(alpha, (await identity.staff(alpha, 'MANAGER')).tokens);
      for (const route of settingsRoutes(version)) {
        const before = rulesSnapshot(alpha.id);
        await refused(await book.call(alpha, route.method, route.path, actor.tokens, route.data), 403, 'permission_denied', `${role} ${route.label}`);
        expect(rulesSnapshot(alpha.id), `${role} ${route.label}: nothing changed`).toBe(before);
      }
    });
  }
  await test.step('signed out', async () => {
    expect((await book.publicRaw(alpha)).status, 'the public read needs no credential').toBe(200);
    for (const route of settingsRoutes(1)) {
      const before = rulesSnapshot(alpha.id);
      await refused(await book.call(alpha, route.method, route.path, undefined, route.data), 401, 'invalid_session', `signed out ${route.label}`);
      expect(rulesSnapshot(alpha.id)).toBe(before);
    }
  });
});

test('TC-F04-S04-010 AC-8: another salon\'s Manager gets "not found" from every settings endpoint and changes nothing; each salon\'s public rules and history name only its own versions and its own staff', async ({ identity }) => {
  const book = new BookingRules(identity);
  const alphaManager = await identity.staff(alpha, 'MANAGER');
  const betaManager = await identity.staff(beta, 'MANAGER');
  const alphaRules = distinct({ freeCancellationHours: 6 });
  const betaRules = distinct({ freeCancellationHours: 48, deposit: { kind: 'amount', amountMinor: (100 + Math.floor(Math.random() * 99_000)) * 100 } });
  await book.save(alpha, alphaManager.tokens, alphaRules);
  await book.save(beta, betaManager.tokens, betaRules);
  const alphaPub = await book.published(alpha);
  const betaPub = await book.published(beta);
  const betaBefore = rulesSnapshot(beta.id);
  await test.step('beta\'s Manager on alpha\'s hostname', async () => {
    const { version } = await book.settings(alpha, alphaManager.tokens);
    for (const route of settingsRoutes(version)) {
      const before = rulesSnapshot(alpha.id);
      await refused(await book.call(alpha, route.method, route.path, betaManager.tokens, route.data), 404, 'not_found', `beta on alpha: ${route.label}`);
      expect(rulesSnapshot(alpha.id), `${route.label}: alpha unchanged`).toBe(before);
    }
  });
  await test.step('each public read and history is its own salon\'s', async () => {
    expect(alphaPub.rules).toEqual(stored(alphaRules));
    expect(betaPub.rules).toEqual(stored(betaRules));
    expect(alphaPub.versionId).not.toBe(betaPub.versionId);
    const alphaHistory = await book.history(alpha, alphaManager.tokens, '?size=100');
    const betaHistory = await book.history(beta, betaManager.tokens, '?size=100');
    expect(alphaHistory.items.length, 'alpha has versions').toBeGreaterThan(0);
    expect(betaHistory.items.length, 'beta has versions').toBeGreaterThan(0);
    const alphaIds = new Set(alphaHistory.items.map(i => i.versionId));
    for (const item of betaHistory.items) expect(alphaIds.has(item.versionId), 'no beta version in alpha\'s history').toBe(false);
    expect(alphaIds.has(alphaPub.versionId)).toBe(true);
  });
  await test.step('the history\'s author labels are only the salon\'s own staff', async () => {
    const alphaLabels = new Set((await identity.list(alphaManager)).flatMap(s => [s.email, s.mobile]).filter(Boolean));
    const betaLabels = (await identity.list(betaManager)).flatMap(s => [s.email, s.mobile]).filter(Boolean);
    expect(betaLabels.length, 'beta has staff to leak').toBeGreaterThan(0);
    for (const item of (await book.history(alpha, alphaManager.tokens, '?size=100')).items) {
      if (item.createdByLabel === null) continue;
      expect(alphaLabels.has(item.createdByLabel), `v${item.version}: the label is one of alpha's staff`).toBe(true);
      expect(betaLabels, `v${item.version}: never one of beta's staff`).not.toContain(item.createdByLabel);
    }
    const latest = (await book.history(alpha, alphaManager.tokens, '?size=1')).items[0];
    expect(latest.createdByLabel, 'the latest version names alpha\'s Manager').toBe(alphaManager.account.email);
  });
  expect(rulesSnapshot(beta.id), 'beta changed in no way').toBe(betaBefore);
  expect((await book.published(beta)).rules, 'beta\'s public rules are its own still').toEqual(stored(betaRules));
});

test('TC-F04-S04-011 AC-8: with no tenant context the booking rules table returns no rows to the application role, and a salon\'s context shows only its own', async ({ identity }) => {
  const book = new BookingRules(identity);
  for (const salon of [alpha, beta]) await book.save(salon, (await identity.staff(salon, 'MANAGER')).tokens, distinct());
  expect(versionRows(alpha.id), 'alpha has versions').toBeGreaterThan(0);
  expect(versionRows(beta.id), 'beta has versions').toBeGreaterThan(0);
  expect(identitySql(`SELECT count(*) FROM ${RULES_TABLE}`, null), 'no tenant, no rows').toBe('0');
  expect(Number(identitySql(`SELECT count(*) FROM ${RULES_TABLE}`, alpha.id)), 'alpha sees its rows').toBe(versionRows(alpha.id));
  expect(identitySql(`SELECT count(*) FROM ${RULES_TABLE} WHERE tenant_id <> ${sqlLiteral(alpha.id)}`, alpha.id), 'and no other salon\'s').toBe('0');
});

test('TC-F04-S04-012 AC-8: a support session reads the rules and the history as the Owner does and saving is 403 support_read_only', async ({ identity, operator }) => {
  const book = new BookingRules(identity);
  const { created, salon, owner } = await newSalon(operator, identity);
  await book.save(salon, owner.tokens, SUGGESTED, 0);
  const support = await operator.actor('OPERATOR_SUPPORT');
  const session = await supportJson<SupportStart>(await operator.startSupport(support, created.id, { reason: 'QA F04 booking rules support reason' }), 'supportStart', 201);
  const tokens: Tokens = await operator.supportCredential(await operator.exchange(session.entryCode, salon.hostname));
  expect(await book.settings(salon, tokens), 'the same rules').toEqual(await book.settings(salon, owner.tokens));
  expect(await book.history(salon, tokens), 'the same history').toEqual(await book.history(salon, owner.tokens));
  const before = rulesSnapshot(salon.id);
  await refused(await book.saveRaw(salon, tokens, 1, distinct()), 403, 'support_read_only', 'support save');
  expect(rulesSnapshot(salon.id), 'nothing changed').toBe(before);
});

test('TC-F04-S04-013 AC-8: a suspended salon answers 403 salon_suspended on every booking rules endpoint, and a read-only salon reads but refuses saving with salon_read_only', async ({ identity, operator, clock }) => {
  test.setTimeout(240_000);
  const book = new BookingRules(identity);
  await clock.set('2026-11-20T07:00:00Z');
  await test.step('suspended', async () => {
    const { admin, created, salon, owner } = await newSalon(operator, identity);
    await book.save(salon, owner.tokens, SUGGESTED, 0);
    await operatorJson(await operator.suspend(admin, created.id, await operator.version(admin, created.id)), 'salon');
    const before = rulesSnapshot(salon.id);
    for (const route of [{ label: 'public', method: 'GET', path: PUBLIC_RULES, data: undefined }, ...settingsRoutes(1)]) {
      await refused(await book.call(salon, route.method, route.path, owner.tokens, route.data), 403, 'salon_suspended', `suspended ${route.label}`);
    }
    expect(rulesSnapshot(salon.id), 'nothing changed').toBe(before);
  });
  await test.step('read-only', async () => {
    const { admin, created, salon, owner } = await newSalon(operator, identity);
    await book.save(salon, owner.tokens, SUGGESTED, 0);
    await readOnlySalon(operator, clock, admin, created.id);
    const now = await identity.login(salon, owner.account);
    expect((await book.settings(salon, now.tokens)).rules, 'the rules are still readable').toEqual(stored(SUGGESTED));
    expect((await book.history(salon, now.tokens)).total).toBe(1);
    expect((await book.published(salon)).rules, 'customers still read them').toEqual(stored(SUGGESTED));
    const before = rulesSnapshot(salon.id);
    await refused(await book.saveRaw(salon, now.tokens, 1, distinct()), 403, 'salon_read_only', 'read-only save');
    expect(rulesSnapshot(salon.id), 'nothing changed').toBe(before);
  });
});

test('TC-F04-S04-014 AC-9: every save is audited with the actor, the time, the version created and the changed rule names, and no rule value reaches the audit or the logs', async ({ identity, operator }) => {
  const book = new BookingRules(identity);
  const { salon, owner } = await newSalon(operator, identity);
  const offsets = logOffsets();
  const t0 = await identity.now();
  const marker = 7_654_321 * 100;
  await book.save(salon, owner.tokens, rules({ deposit: { kind: 'percentage', percent: 37, capMinor: marker } }), 0);
  await book.save(salon, owner.tokens, rules({ deposit: { kind: 'percentage', percent: 37, capMinor: marker }, freeCancellationHours: 48, noShow: { kind: 'share', percent: 63 } }));
  await refused(await book.saveRaw(salon, owner.tokens, 1, distinct()), 409, 'conflict', 'a stale save');
  const t1 = await identity.now();
  const history = await book.history(salon, owner.tokens);
  const rows = rulesAudit(salon.id);
  expect(rows.length, 'one entry per save; the refused save has none').toBe(2);
  for (const [n, row] of rows.entries()) {
    const version = history.items.find(i => i.version === n + 1)!;
    expect(row.action).toBe('booking.rules_saved');
    expect(row.actorKind).toBe('staff');
    expect(row.actorId, 'the acting staff account').toBe(owner.principal.id);
    expect(row.subjectId, 'the version created').toBe(version.versionId);
    expect(Date.parse(row.at)).toBeGreaterThanOrEqual(Date.parse(t0) - 1000);
    expect(Date.parse(row.at)).toBeLessThanOrEqual(Date.parse(t1) + 1000);
    for (const value of [String(marker), '7654321', '"percent":37', '"percent":63']) expect(row.details, `v${n + 1}: no rule value (${value})`).not.toContain(value);
  }
  for (const name of RULE_NAMES) expect(rows[0].details, `the first entry names ${name}`).toContain(name);
  expect(rows[1].details).toContain('freeCancellationHours');
  expect(rows[1].details).toContain('noShow');
  for (const name of ['slotIntervalMinutes', 'deposit"', 'reschedule']) expect(rows[1].details, `the second names only what changed, not ${name}`).not.toContain(name);
  const logs = logsSince(offsets);
  expect(logs.length, 'the logs were written to').toBeGreaterThan(0);
  expect(logs, 'no amount in the logs').not.toContain(String(marker));
});

test('TC-F04-S04-015 AC-3: a salon configured with another currency (NFR-13) reads, saves and serves its rules in that currency, with the limits in its whole units', async ({ identity, operator }) => {
  // The operator provisions only currencies of two fraction digits (ProvisioningRegions), so the limits are the LKR numbers in that currency.
  const book = new BookingRules(identity);
  const { salon, owner } = await newSalon(operator, identity, { currency: 'USD' });
  expect(await book.settings(salon, owner.tokens), 'before the first save: the salon\'s currency').toEqual({ version: 0, rules: null, currency: 'USD' });
  for (const [label, deposit, field] of [
    ['99 units', { kind: 'amount', amountMinor: 9900 }, /deposit\.amountMinor$/],
    ['100,001 units', { kind: 'amount', amountMinor: 10000100 }, /deposit\.amountMinor$/],
    ['part of a unit', { kind: 'amount', amountMinor: 10050 }, /deposit\.amountMinor$/],
    ['a cap of 499 units', { kind: 'percentage', percent: 20, capMinor: 49900 }, /deposit\.capMinor$/],
  ] as const) {
    const { errors } = await fieldErrors(await book.saveRaw(salon, owner.tokens, 0, rules({ deposit: deposit as RulesInput['deposit'] })), label);
    expect(errors.length, `${label}: names the field`).toBeGreaterThan(0);
    for (const e of errors) expect(e.field, label).toMatch(field);
    expect(versionRows(salon.id), `${label}: nothing saved`).toBe(0);
  }
  const saved = await book.save(salon, owner.tokens, rules({ deposit: { kind: 'amount', amountMinor: 10000 } }), 0);
  expect(saved.currency).toBe('USD');
  expect((await book.published(salon)).currency, 'customers read the amounts in the salon\'s currency').toBe('USD');
  expect(identitySql(`SELECT currency FROM ${RULES_TABLE} WHERE tenant_id = ${sqlLiteral(salon.id)}`), 'the version keeps its currency').toBe('USD');
});
