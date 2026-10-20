/**
 * F03-S02 the billing cycle: invoice issue and numbering, the monthly anchor, card charges through
 * the fake provider, signed provider events, bank transfers, due/reminder/read-only boundaries,
 * restoring a paid salon, plan moves and an interrupted job.
 * Cases TC-F03-S02-004 to -013, -016, -019, -028 (cases.md).
 *
 * Time moves only through the test clock and the billing job runs through the run-job hook at the
 * instant under test (S02 handoff), so every boundary is checked one second either side. Charges go
 * to the local fake provider and emails to the local capture; nothing real is charged or sent.
 */
import { randomUUID } from 'node:crypto';

import {
  BOUNDARY, attempts, auditsNaming, billAt, chargeAttemptsOfSalon, crossBoundary, firstInvoice, invoiceNumber, lastInvoiceNumber, numbersAfter,
  paymentRows, providerEventRows, settled,
} from '../../shared/billing';
import { syntheticAccount } from '../../shared/identity';
import { identitySql } from '../../shared/identity-storage';
import {
  test, expect, alpha, DAY, SECOND, exact, fieldsOf, operatorJson, plusExact, schedulerSettled, sqlLiteral, operatorProblem, ownerSession, plusMs, CONSOLE_HOST,
  type Operator, type Payment, type SalonInput, type TestClock,
} from '../../shared/operator';

const id = (n: number) => `TC-F03-S02-${String(n).padStart(3, '0')}`;

/** A salon created at a fixed instant with the given method and zone; returns its trial end. */
async function billed(operator: Operator, clock: TestClock, createdAt: string, overrides: Partial<SalonInput> = {}, plan: Partial<{ staffSeats: number }> = {}) {
  await clock.set(createdAt);
  const admin = await operator.actor();
  const planDef = await operator.plan(admin, plan);
  const made = await operator.salon(admin, planDef.id, overrides);
  const subscription = await operator.subscription(admin, made.created.id);
  return { admin, plan: planDef, ...made, subscription, owner: made.input.owner.email };
}

function eventBody(invoiceId: string, attemptKey: string, outcome: 'success' | 'failure' = 'success', eventId = `evt_qa_${randomUUID().replace(/-/g, '')}`) {
  return { eventId, body: JSON.stringify({ eventId, invoiceId, attemptKey, outcome, occurredAt: new Date().toISOString() }) };
}

test(`${id(4)} Crossing trial end issues exactly one correct first invoice`, async ({ operator, clock }) => {
  const { admin, plan, created, subscription } = await billed(operator, clock, '2026-11-02T08:15:30Z');
  const trialEnd = subscription.trialEndsAt;
  await billAt(operator, clock, plusMs(trialEnd, -SECOND));
  await operator.renew(admin);
  expect((await operator.invoices(admin, created.id)).total, 'nothing one second before trial end').toBe(0);
  const before = lastInvoiceNumber();
  await billAt(operator, clock, trialEnd);
  await operator.runBilling();
  await operator.runBilling();
  await operator.renew(admin);
  const page = await operator.invoices(admin, created.id);
  expect(page.total, 'exactly one invoice despite three passes').toBe(1);
  const invoice = await operator.invoice(admin, page.items[0].id);
  expect(invoice).toMatchObject({
    number: invoiceNumber(before + 1), salonId: created.id, planKey: plan.key, planName: plan.name, amount: 250000, currency: 'LKR',
    status: 'open', paidAt: null, payments: [],
  });
  // Asia/Colombo has no clock change, so one calendar month is the same wall time a month later.
  expect(exact(invoice.periodStart)).toBe(exact(trialEnd));
  expect(exact(invoice.periodEnd)).toBe(plusExact(trialEnd, 30 * DAY));
  expect(exact(invoice.issuedAt), 'issued at the boundary').toBe(exact(trialEnd));
  expect(Date.parse(invoice.dueAt) - Date.parse(invoice.issuedAt), 'due seven days after issue').toBe(7 * DAY);
  expect(numbersAfter(before), 'no gap and no repeat').toEqual([before + 1]);
});

test(`${id(5)} Concurrent billing job runs issue no duplicate invoice`, async ({ operator, clock }) => {
  const { admin, created, subscription } = await billed(operator, clock, '2026-11-03T06:00:00Z');
  const before = lastInvoiceNumber();
  await clock.set(plusMs(subscription.trialEndsAt, SECOND));
  const passes = await Promise.all(Array.from({ length: 8 }, () => operator.runBilling()));
  expect(passes.length).toBe(8);
  await operator.renew(admin);
  expect((await operator.invoices(admin, created.id)).total, 'one invoice for the period').toBe(1);
  const issued = numbersAfter(before);
  expect(issued.length, 'the counter advanced for this salon only once').toBeGreaterThanOrEqual(1);
  expect(issued, 'consecutive numbers with no gap or repeat').toEqual(issued.map((_, n) => before + 1 + n));
});

test(`${id(6)} Monthly invoices follow the anchor day, including a 31st anchor in shorter months`, async ({ operator, clock }) => {
  test.setTimeout(240_000);
  // Trial ends on 31 January at 10:00 in Colombo; then 28 Feb, 31 Mar, 30 Apr at the same wall time.
  // Trial ends on 15 February at 10:00 in London; British Summer Time starts on 28 March 2027.
  const rows = [
    { label: 'Colombo 31st', createdAt: '2027-01-17T04:30:00Z', timeZone: 'Asia/Colombo', offsets: [28 * DAY, 59 * DAY, 89 * DAY] },
    { label: 'London across DST', createdAt: '2027-02-01T10:00:00Z', timeZone: 'Europe/London', offsets: [28 * DAY, 59 * DAY - 3_600_000, 89 * DAY - 3_600_000] },
  ];
  for (const row of rows) {
    await test.step(row.label, async () => {
      const { admin, created, subscription } = await billed(operator, clock, row.createdAt, { timeZone: row.timeZone });
      const trialEnd = subscription.trialEndsAt;
      const issues = [trialEnd, ...row.offsets.map(o => plusExact(trialEnd, o))];
      for (const [n, instant] of issues.entries()) {
        await billAt(operator, clock, plusMs(instant, -SECOND));
        await operator.renew(admin);
        expect((await operator.invoices(admin, created.id)).total, `${row.label}: nothing one second before issue ${n + 1}`).toBe(n);
        await billAt(operator, clock, instant);
        await operator.runBilling();
        await operator.renew(admin);
        const page = await operator.invoices(admin, created.id);
        expect(page.total, `${row.label}: one invoice for period ${n + 1}`).toBe(n + 1);
        const latest = page.items.reduce((a, b) => (Date.parse(a.issuedAt) > Date.parse(b.issuedAt) ? a : b));
        expect(exact(latest.issuedAt), `${row.label}: issue ${n + 1} at the local anchor`).toBe(exact(instant));
        expect(latest.periodStart).toBe(latest.issuedAt);
        await operator.pay(admin, latest);
      }
    });
  }
});

test(`${id(7)} A successful card charge pays the invoice and keeps the salon active`, async ({ operator, clock }) => {
  const { admin, created, owner } = await billed(operator, clock, '2026-11-04T07:00:00Z', { subscription: { monthlyAmount: 180000, paymentMethod: 'card' } });
  const invoice = await firstInvoice(operator, clock, admin, created.id);
  await settled(operator, admin, invoice.id, 'paid');
  const detail = await operator.invoice(admin, invoice.id);
  expect(detail.payments.length, 'one payment').toBe(1);
  expect(detail.payments[0]).toMatchObject({ method: 'card', amount: 180000, reference: null, receivedOn: null });
  expect(detail.payments[0].providerEventId).not.toBeNull();
  expect(paymentRows(invoice.id)).toBe(1);
  expect((await operator.subscription(admin, created.id)).state).toBe('active');
  expect((await operator.emails(owner)).filter(e => e.kind === 'billing.failed_charge'), 'no failed-charge notice').toEqual([]);
});

test(`${id(8)} A failed card charge sends one notice and is retried on each reminder day`, async ({ operator, clock }) => {
  test.setTimeout(180_000);
  const { admin, created, owner } = await billed(operator, clock, '2026-11-05T07:00:00Z', { subscription: { monthlyAmount: 180000, paymentMethod: 'card' } });
  await operator.providerOutcome(created.id, 'failure');
  const invoice = await firstInvoice(operator, clock, admin, created.id);
  await operator.runBilling();
  await settled(operator, admin, invoice.id, 'open');
  await operator.emailsOfKind(owner, 'billing.failed_charge', 1);
  for (const [n, boundary] of (['due', 'due_3', 'due_10'] as const).entries()) {
    await crossBoundary(operator, clock, admin, invoice, boundary);
    await operator.runBilling();
    await settled(operator, admin, invoice.id, 'open', n + 2);
    await operator.emailsOfKind(owner, 'billing.failed_charge', n + 2);
  }
  const made = attempts(invoice.id);
  expect(made.map(a => a.occasion), 'one attempt at issue and on each reminder day').toEqual(['issue', 'due', 'due_3', 'due_10']);
  expect(new Set(made.map(a => a.attemptKey)).size, 'each attempt has its own key').toBe(4);
  expect(made.every(a => a.status === 'failed')).toBe(true);
  expect(paymentRows(invoice.id)).toBe(0);
  const all = await operator.emails(owner);
  expect(all.every(e => e.recipient.toLowerCase() === owner.toLowerCase()), 'only the Owner contact').toBe(true);
});

test(`${id(9)} Repeated, late, unsigned or tampered provider events pay an invoice at most once`, async ({ operator, clock }) => {
  const { admin, created } = await billed(operator, clock, '2026-11-06T07:00:00Z', { subscription: { monthlyAmount: 120000, paymentMethod: 'card' } });
  await operator.providerOutcome(created.id, 'failure');
  const invoice = await firstInvoice(operator, clock, admin, created.id);
  await settled(operator, admin, invoice.id, 'open');
  const [attempt] = attempts(invoice.id);
  const ack = async (body: string, signature: string | null, result: string) => {
    const r = await operator.postEvent(body, signature);
    expect((await operatorJson<{ result: string }>(r, 'acknowledged')).result).toBe(result);
  };
  // Bad signatures first: refused with no effect and no record.
  const bad = eventBody(invoice.id, attempt.attemptKey);
  await operatorProblem(await operator.postEvent(bad.body, null), 401, 'invalid_credentials');
  await operatorProblem(await operator.postEvent(bad.body, await operator.sign(eventBody(invoice.id, attempt.attemptKey).body)), 401, 'invalid_credentials');
  const signedThenAltered = await operator.sign(bad.body);
  await operatorProblem(await operator.postEvent(bad.body.replace('"success"', '"failure"'), signedThenAltered), 401, 'invalid_credentials');
  expect(providerEventRows(bad.eventId), 'no provider event recorded for a bad signature').toBe(0);
  expect(paymentRows(invoice.id)).toBe(0);
  // Not found on any host but the bare platform host.
  for (const host of [alpha.hostname, CONSOLE_HOST, `${created.slug}.localhost`]) {
    await operatorProblem(await operator.postEvent(bad.body, signedThenAltered, host), 404, 'not_found');
  }
  // A signed success pays; its repeat and a new event for the paid invoice change nothing.
  const good = eventBody(invoice.id, attempt.attemptKey);
  const signature = await operator.sign(good.body);
  await ack(good.body, signature, 'paid');
  await ack(good.body, signature, 'duplicate');
  const again = eventBody(invoice.id, attempt.attemptKey);
  await ack(again.body, await operator.sign(again.body), 'already_paid');
  const unknown = eventBody(randomUUID(), randomUUID());
  await ack(unknown.body, await operator.sign(unknown.body), 'unknown');
  expect(paymentRows(invoice.id), 'exactly one payment').toBe(1);
  expect((await operator.invoice(admin, invoice.id)).status).toBe('paid');
  // A late success after a manual transfer on the next invoice is recorded already_paid.
  const subscription = await operator.subscription(admin, created.id);
  await billAt(operator, clock, subscription.nextInvoiceAt);
  await operator.renew(admin);
  const second = (await operator.invoices(admin, created.id)).items.find(i => i.id !== invoice.id)!;
  expect(second, 'the second invoice exists').toBeTruthy();
  await settled(operator, admin, second.id, 'open');
  await operator.pay(admin, second);
  const late = eventBody(second.id, attempts(second.id)[0].attemptKey);
  await ack(late.body, await operator.sign(late.body), 'already_paid');
  expect(paymentRows(second.id), 'no second payment for a late success').toBe(1);
  expect((await operator.invoice(admin, second.id)).payments[0].method).toBe('bank_transfer');
});

test(`${id(10)} Simultaneous duplicate events and a racing transfer record one payment`, async ({ operator, clock }) => {
  const { admin, created } = await billed(operator, clock, '2026-11-07T07:00:00Z', { subscription: { monthlyAmount: 90000, paymentMethod: 'card' } });
  await operator.providerOutcome(created.id, 'failure');
  const invoice = await firstInvoice(operator, clock, admin, created.id);
  await settled(operator, admin, invoice.id, 'open');
  const event = eventBody(invoice.id, attempts(invoice.id)[0].attemptKey);
  const signature = await operator.sign(event.body);
  const receivedOn = (await operator.now()).slice(0, 10);
  const [transfer, ...events] = await Promise.all([
    operator.recordTransfer(admin, invoice.id, { reference: 'QA-RACE', amount: invoice.amount, receivedOn }),
    ...Array.from({ length: 8 }, () => operator.postEvent(event.body, signature)),
  ]);
  const results = await Promise.all(events.map(async r => { expect(r.status()).toBe(200); return (await r.json()).result as string; }));
  const transferWon = transfer.status() === 201;
  if (!transferWon) await operatorProblem(transfer, 409, 'invalid_state');
  const paidByEvent = results.filter(r => r === 'paid').length;
  expect(paidByEvent + (transferWon ? 1 : 0), 'exactly one winner').toBe(1);
  expect(results.every(r => ['paid', 'duplicate', 'already_paid'].includes(r)), `event results ${results}`).toBe(true);
  expect(paymentRows(invoice.id), 'exactly one payment row').toBe(1);
  expect((await operator.invoice(admin, invoice.id)).status).toBe('paid');
  expect(auditsNaming('payment.recorded', invoice.id).length, 'one payment audit').toBe(1);
});

test(`${id(11)} A recorded bank transfer pays an open invoice once per key`, async ({ operator, clock }) => {
  const { admin, created } = await billed(operator, clock, '2026-11-08T07:00:00Z');
  const invoice = await firstInvoice(operator, clock, admin, created.id);
  const key = randomUUID();
  const body = { reference: 'QA-TRANSFER-1', amount: invoice.amount, receivedOn: (await operator.now()).slice(0, 10) };
  const first = await operatorJson<Payment>(await operator.recordTransfer(admin, invoice.id, body, key), 'payment', 201);
  expect(first).toMatchObject({ method: 'bank_transfer', amount: invoice.amount, reference: 'QA-TRANSFER-1', receivedOn: body.receivedOn, providerEventId: null });
  const replay = await operatorJson<Payment>(await operator.recordTransfer(admin, invoice.id, body, key), 'payment', 201);
  expect(replay, 'the same key and input replay the original').toEqual(first);
  await operatorProblem(await operator.recordTransfer(admin, invoice.id, { ...body, reference: 'QA-TRANSFER-2' }, key), 409, 'idempotency_conflict');
  const detail = await operator.invoice(admin, invoice.id);
  expect(detail.status).toBe('paid');
  expect(detail.payments).toEqual([first]);
  expect(paymentRows(invoice.id)).toBe(1);
  expect(auditsNaming('payment.recorded', invoice.id).length, 'one payment audit').toBe(1);
});

test(`${id(12)} Partial, excess, future-dated or paid-invoice transfers are refused without change`, async ({ operator, clock }) => {
  const { admin, created } = await billed(operator, clock, '2026-11-09T07:00:00Z');
  const plan = (await operator.salonDetail(admin, created.id)).plan!;
  const other = await operator.salon(admin, plan.id);
  const open = await firstInvoice(operator, clock, admin, created.id);
  await billAt(operator, clock, plusMs((await operator.subscription(admin, other.created.id)).trialEndsAt, SECOND));
  await operator.renew(admin);
  const paidOne = (await operator.invoices(admin, other.created.id)).items[0];
  expect(paidOne, 'the second salon was invoiced at the same trial end').toBeTruthy();
  await operator.pay(admin, paidOne);
  const today = (await operator.now()).slice(0, 10);
  const future = new Date(Date.parse(today) + 2 * DAY).toISOString().slice(0, 10);
  const valid = { reference: 'QA-REF', amount: open.amount, receivedOn: today };
  const rows: [string, Record<string, unknown>, string][] = [
    ['amount - 1', { ...valid, amount: open.amount - 1 }, 'amount'],
    ['amount + 1', { ...valid, amount: open.amount + 1 }, 'amount'],
    ['received in the future', { ...valid, receivedOn: future }, 'receivedOn'],
    ['missing reference', { amount: valid.amount, receivedOn: today }, 'reference'],
    ['blank reference', { ...valid, reference: '   ' }, 'reference'],
  ];
  for (const [label, body, field] of rows) {
    await test.step(label, async () => {
      expect(fieldsOf(await operatorProblem(await operator.recordTransfer(admin, open.id, body), 400, 'bad_request')), label).toContain(field);
    });
  }
  const noKey = await operator.call('POST', `/invoices/${open.id}/payments`, { tokens: admin.tokens, data: valid });
  expect(fieldsOf(await operatorProblem(noKey, 400, 'bad_request')).join(' ')).toMatch(/Idempotency-Key/i);
  await operatorProblem(await operator.recordTransfer(admin, paidOne.id, { ...valid, amount: paidOne.amount }), 409, 'invalid_state');
  await operatorProblem(await operator.recordTransfer(admin, randomUUID(), valid), 404, 'not_found');
  expect((await operator.invoice(admin, open.id)).payments, 'the open invoice is unchanged').toEqual([]);
  expect((await operator.invoice(admin, open.id)).status).toBe('open');
  expect(paymentRows(paidOne.id), 'the paid invoice keeps its one payment').toBe(1);
});

test(`${id(13)} Due, reminder and read-only boundaries change state and send each notice exactly once`, async ({ operator, clock }) => {
  test.setTimeout(180_000);
  const { admin, created, owner } = await billed(operator, clock, '2026-11-10T07:00:00Z');
  const invoice = await firstInvoice(operator, clock, admin, created.id);
  const expectations = [
    { boundary: 'due', state: 'past_due', kind: 'billing.due' },
    { boundary: 'due_3', state: 'past_due', kind: 'billing.due_3' },
    { boundary: 'due_10', state: 'past_due', kind: 'billing.due_10' },
    { boundary: 'read_only', state: 'read_only', kind: 'billing.read_only' },
  ] as const;
  const kinds = async () => (await operator.emails(owner)).map(e => e.kind).filter(k => k.startsWith('billing.')).sort();
  for (const step of expectations) {
    await test.step(step.boundary, async () => {
      await crossBoundary(operator, clock, admin, invoice, step.boundary, -SECOND);
      const before = await operator.subscription(admin, created.id);
      const emailsBefore = await kinds();
      expect(emailsBefore, `nothing for ${step.kind} one second before`).not.toContain(step.kind);
      if (step.boundary === 'due') expect(before.state, 'not past due before the due instant').not.toBe('past_due');
      if (step.boundary === 'read_only') expect(before.state, 'not read-only one second before').toBe('past_due');
      await crossBoundary(operator, clock, admin, invoice, step.boundary);
      await operator.runBilling();
      const after = await operator.subscription(admin, created.id);
      expect(after.state).toBe(step.state);
      await operator.emailsOfKind(owner, step.kind, 1);
    });
  }
  const final = await operator.subscription(admin, created.id);
  expect(exact(final.readOnlyAt!), 'read-only from 14 days after the due instant').toBe(plusExact(invoice.dueAt, BOUNDARY.read_only));
  expect(await kinds(), 'each notice exactly once').toEqual(['billing.due', 'billing.due_10', 'billing.due_3', 'billing.read_only']);
});

test(`${id(16)} Paying every overdue invoice restores the salon; paying one of two does not`, async ({ operator, identity, clock }) => {
  test.setTimeout(180_000);
  const { admin, created, input, salon } = await billed(operator, clock, '2026-11-11T07:00:00Z');
  const owner = await ownerSession(identity, salon, input);
  const first = await firstInvoice(operator, clock, admin, created.id);
  const subscription = await operator.subscription(admin, created.id);
  // The second month is issued, then both invoices are past due and the first 14 days overdue.
  await billAt(operator, clock, subscription.nextInvoiceAt);
  await billAt(operator, clock, plusMs(subscription.nextInvoiceAt, 8 * DAY));
  await operator.renew(admin);
  expect((await operator.subscription(admin, created.id)).state).toBe('read_only');
  const invoices = (await operator.invoices(admin, created.id)).items;
  expect(invoices.length).toBe(2);
  const newer = invoices.find(i => i.id !== first.id)!;
  const signedIn = await identity.login(salon, owner.account);
  const mutate = () => identity.call(salon, 'POST', '/staff', syntheticAccount('RECEPTIONIST'), signedIn.tokens);
  await operator.pay(admin, newer);
  expect((await operator.subscription(admin, created.id)).state, 'one of two overdue invoices paid').toBe('read_only');
  await operatorProblem(await mutate(), 403, 'salon_read_only');
  await operator.pay(admin, first);
  expect((await operator.subscription(admin, created.id)).state, 'every overdue invoice paid').toBe('active');
  expect((await mutate()).status(), 'mutations work on the next request, without a job run').toBe(201);
});

test(`${id(19)} A plan move is refused above the new limits and applies only to future invoices`, async ({ operator, identity, clock }) => {
  const { admin, created, salon, plan } = await billed(operator, clock, '2026-11-12T07:00:00Z');
  await identity.seed(salon, [syntheticAccount('MANAGER'), syntheticAccount('RECEPTIONIST')]);
  const small = await operator.plan(admin, { staffSeats: 2 });
  const large = await operator.plan(admin, { staffSeats: 10 });
  const issued = await firstInvoice(operator, clock, admin, created.id);
  const before = await operator.salonDetail(admin, created.id);
  const refused = await operatorProblem(await operator.movePlan(admin, created.id, before.version, small.id), 409, 'entitlement_exceeded');
  expect(fieldsOf(refused), 'names the exceeded limit').toContain('staffSeats');
  expect(await operator.salonDetail(admin, created.id), 'no change').toEqual(before);
  await operatorProblem(await operator.movePlan(admin, created.id, before.version + 1, large.id), 409, 'conflict');
  const moved = await operatorJson<{ plan: { id: string }; version: number }>(await operator.movePlan(admin, created.id, before.version, large.id), 'salon');
  expect(moved.plan.id).toBe(large.id);
  expect(moved.version).toBe(before.version + 1);
  const subscription = await operator.subscription(admin, created.id);
  expect((await operatorJson<{ monthlyAmount: number }>(await operator.changeSubscription(admin, created.id, { version: subscription.version, monthlyAmount: 333300 }), 'subscription')).monthlyAmount).toBe(333300);
  await billAt(operator, clock, subscription.nextInvoiceAt);
  await operator.renew(admin);
  const all = await Promise.all((await operator.invoices(admin, created.id)).items.map(i => operator.invoice(admin, i.id)));
  const old = all.find(i => i.id === issued.id)!;
  const next = all.find(i => i.id !== issued.id)!;
  expect(old, 'the issued invoice never changes').toMatchObject({ planKey: plan.key, amount: issued.amount });
  expect(next, 'the next invoice carries the new plan and amount').toMatchObject({ planKey: large.key, planName: large.name, amount: 333300 });
});

test(`${id(28)} A billing job interrupted after invoice issue resumes without a partial invoice or payment`, async ({ operator, clock }) => {
  const { admin, created, subscription, owner } = await billed(operator, clock, '2026-11-13T07:00:00Z', { subscription: { monthlyAmount: 75000, paymentMethod: 'card' } });
  await clock.set(subscription.trialEndsAt);
  await operator.armBillingInterrupt('after-invoice-issue');
  const failed = await operator.runBilling();
  expect(failed.failed.length, 'the armed pass failed for a subscription').toBeGreaterThanOrEqual(1);
  // The scheduler's own billing pass is due at the same instant and may issue the invoice beside
  // the interrupted one, so the interrupted state is read as an invariant in one snapshot: no
  // invoice without its issue charge attempt and no attempt without its invoice (no partial issue).
  const snapshot = identitySql(`SELECT (SELECT count(*) FROM invoice WHERE salon_id = ${sqlLiteral(created.id)}) || ':' ||
    (SELECT count(*) FROM charge_attempt a JOIN invoice i ON i.id = a.invoice_id WHERE i.salon_id = ${sqlLiteral(created.id)} AND a.occasion = 'issue')`);
  const [issued, attempted] = snapshot.split(':').map(Number);
  expect(issued, 'at most one invoice').toBeLessThanOrEqual(1);
  expect(attempted, 'no partial invoice: each issued invoice has its charge attempt').toBe(issued);
  await operator.runBilling();
  await schedulerSettled(operator);
  await operator.renew(admin);
  const page = await operator.invoices(admin, created.id);
  expect(page.total, 'the next pass issues it once').toBe(1);
  await settled(operator, admin, page.items[0].id, 'paid');
  expect(attempts(page.items[0].id).length, 'one attempt').toBe(1);
  expect(paymentRows(page.items[0].id), 'one payment').toBe(1);
});

