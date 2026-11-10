/**
 * F03-S02 billing timelines through the test clock and the run-job hook: issue the first invoice,
 * cross a due or read-only boundary, and read the database rows the API does not expose (charge
 * attempts, payments, the invoice counter). Read-only observations; nothing here writes the
 * database directly.
 */
import { identitySql } from './identity-storage';
import {
  expect, DAY, plusMs, schedulerSettled, sqlLiteral, platformAuditWhere,
  type BillingPass, type Operator, type OperatorActor, type InvoiceSummary, type TestClock,
} from './operator';

/** Days of 24 hours from an invoice's due instant (decision 049, operator-console.md "As built"). */
export const BOUNDARY = { due: 0, due_3: 3 * DAY, due_10: 10 * DAY, read_only: 14 * DAY } as const;

export const invoiceNumber = (n: number) => `INV-${String(n).padStart(6, '0')}`;
export const lastInvoiceNumber = () => Number(identitySql('SELECT last_number FROM invoice_number'));
/** Numbers of invoices issued after a counter value, in order. */
export const numbersAfter = (n: number) => identitySql(`SELECT number FROM invoice WHERE number > ${Number(n)} ORDER BY number`)
  .split('\n').filter(Boolean).map(Number);

export type Attempt = { occasion: string; attemptKey: string; status: string };
export function attempts(invoiceId: string): Attempt[] {
  const out = identitySql(`SELECT coalesce(json_agg(json_build_object('occasion', occasion, 'attemptKey', attempt_key, 'status', status) ORDER BY requested_at, id), '[]'::json)
    FROM charge_attempt WHERE invoice_id = ${sqlLiteral(invoiceId)}`);
  return JSON.parse(out || '[]');
}
export const paymentRows = (invoiceId: string) => Number(identitySql(`SELECT count(*) FROM invoice_payment WHERE invoice_id = ${sqlLiteral(invoiceId)}`));
export const providerEventRows = (eventId: string) => Number(identitySql(`SELECT count(*) FROM provider_event WHERE event_id = ${sqlLiteral(eventId)}`));
export const chargeAttemptsOfSalon = (salonId: string) => Number(identitySql(
  `SELECT count(*) FROM charge_attempt WHERE invoice_id IN (SELECT id FROM invoice WHERE salon_id = ${sqlLiteral(salonId)})`));
/** Platform audit entries of an action that name a subject, as subject or in their details. */
export const auditsNaming = (action: string, subjectId: string) => platformAuditWhere(
  `action = ${sqlLiteral(action)} AND (subject_id = ${sqlLiteral(subjectId)} OR details::text LIKE ${sqlLiteral(`%${subjectId}%`)})`);

/** What one billing pass did for one salon: nothing when the salon is not among its changes. */
export function passFor(pass: BillingPass, salonId: string): BillingPass['changed'][number] {
  return pass.changed.find(c => c.salonId === salonId) ?? { salonId, issued: [], reminders: [], charges: [], states: [] };
}

/**
 * How many of a salon's invoices the application issued before an instant, by its own clock at issue
 * (`created_at` is the pass's instant; `issued_at` is the period's anchor). The test clock runs on
 * from where it is set (docs/qa/test-plan.md), so a count read seconds after a pass at "one second
 * before" may legitimately include an invoice issued after the anchor; this count may not.
 */
export const invoicesIssuedBefore = (salonId: string, instant: string) => Number(identitySql(
  `SELECT count(*) FROM invoice WHERE salon_id = ${sqlLiteral(salonId)} AND created_at < ${sqlLiteral(instant)}::timestamptz`));

/**
 * The pass run one second before an instant: asserts it ran before the instant (two calls apart, so
 * well inside the second) and issued nothing to the salon.
 */
export function expectNothingIssuedBy(pass: BillingPass, salonId: string, instant: string, label: string) {
  expect(Date.parse(pass.at), `${label}: the pass ran before ${instant} (it ran at ${pass.at})`).toBeLessThan(Date.parse(instant));
  expect(passFor(pass, salonId).issued, `${label}: the pass issued nothing`).toEqual([]);
}

/** Sets the clock and runs one billing pass at that instant. */
export async function billAt(operator: Operator, clock: TestClock, instant: string) {
  await clock.set(instant);
  const pass = await operator.runBilling();
  // The scheduler's own pass of the same job is due too; let it finish before anything is read.
  await schedulerSettled(operator);
  return pass;
}

/** Crosses the trial end of a salon's subscription and returns its first invoice. */
export async function firstInvoice(operator: Operator, clock: TestClock, admin: OperatorActor, salonId: string): Promise<InvoiceSummary> {
  const subscription = await operator.subscription(await operator.renew(admin), salonId);
  await billAt(operator, clock, subscription.trialEndsAt);
  await operator.renew(admin);
  const page = await operator.invoices(admin, salonId);
  expect(page.total, 'the first invoice was issued at trial end').toBe(1);
  return page.items[0];
}

/** Moves an unpaid invoice past a boundary and runs the job there. */
export async function crossBoundary(operator: Operator, clock: TestClock, admin: OperatorActor, invoice: { dueAt: string },
  boundary: keyof typeof BOUNDARY, extraMs = 0): Promise<BillingPass> {
  const pass = await billAt(operator, clock, plusMs(invoice.dueAt, BOUNDARY[boundary] + extraMs));
  await operator.renew(admin);
  return pass;
}

/** A salon with an unpaid first invoice driven to `read_only`. */
export async function readOnlySalon(operator: Operator, clock: TestClock, admin: OperatorActor, salonId: string) {
  const invoice = await firstInvoice(operator, clock, admin, salonId);
  await crossBoundary(operator, clock, admin, invoice, 'read_only');
  expect((await operator.subscription(admin, salonId)).state, 'the salon became read-only').toBe('read_only');
  return invoice;
}

/** Waits for the outbox to deliver a card charge and the fake provider's event to settle it. */
export async function settled(operator: Operator, admin: OperatorActor, invoiceId: string, status: 'paid' | 'open', attemptsSettled = 1) {
  await expect.poll(() => attempts(invoiceId).filter(a => a.status !== 'requested').length,
    { message: `${attemptsSettled} charge attempt(s) settled by the fake provider`, timeout: 15_000, intervals: [250, 500, 1000] }).toBeGreaterThanOrEqual(attemptsSettled);
  expect((await operator.invoice(admin, invoiceId)).status).toBe(status);
}
