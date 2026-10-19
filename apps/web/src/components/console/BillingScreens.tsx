'use client';
import Link from 'next/link';
import { useCallback, useEffect, useState } from 'react';
import { useRouter } from 'next/navigation';
import { useTranslations } from 'next-intl';
import type { components } from '@salon/api-contracts';
import { Alert, AlertTitle } from '@/components/ui/alert';
import { Badge } from '@/components/ui/badge';
import { Button } from '@/components/ui/button';
import { Card, CardTitle } from '@/components/ui/card';
import { Empty, EmptyDescription, EmptyHeader, EmptyTitle } from '@/components/ui/empty';
import { FieldGroup } from '@/components/ui/field';
import { Item, ItemActions, ItemContent, ItemDescription, ItemGroup, ItemTitle } from '@/components/ui/item';
import { Skeleton } from '@/components/ui/skeleton';
import { Spinner } from '@/components/ui/spinner';
import { formatDate, formatMoney, minorUnitsText, parseMinorUnits } from '@/lib/format';
import { operatorRead, operatorRequest, OperatorFailure, principalInsideLock, withOperatorLock } from '@/lib/operator/browser';
import { ConsoleChoiceField, ConsoleFailure, ConsoleLoading, ConsoleTextField, uncertain, useFailureMessage } from './ConsoleControls';
import { ConsoleSheet } from './ConsoleSheet';
import { DirectoryPager } from './DirectoryControls';
import { useIdempotencyKey } from './PlanScreens';
import { PlanPicker } from './PlanPicker';
import { PAGE_SIZE, type DirectoryPage } from './useDirectory';

type Detail = components['schemas']['SalonDetail'];
type Subscription = components['schemas']['SubscriptionView'];
type InvoiceSummary = components['schemas']['InvoiceSummary'];
type Payment = components['schemas']['PaymentView'];

const LOCALE = 'en-LK';
const METHODS = ['card', 'bank_transfer'] as const;

function asFailure(error: unknown): OperatorFailure {
  return error instanceof OperatorFailure ? error : new OperatorFailure('service_unavailable');
}

/** A subscription state as a status label: a word, never a colour alone. */
export function SubscriptionBadge({ state, testId = 'console-subscription-state' }: { state: string; testId?: string }) {
  const t = useTranslations('console');
  const variant = state === 'active' ? 'positive' : state === 'read_only' ? 'destructive' : state === 'past_due' ? 'outline' : 'secondary';
  return <Badge variant={variant} data-testid={testId} data-state={state}>{t(`subscriptionState_${state}` as 'subscriptionState_active')}</Badge>;
}

export function InvoiceBadge({ status }: { status: string }) {
  const t = useTranslations('console');
  return <Badge variant={status === 'paid' ? 'positive' : 'outline'} data-testid="console-invoice-status" data-status={status}>{t(status === 'paid' ? 'invoicePaid' : 'invoiceOpen')}</Badge>;
}

/** Runs a console mutation under the cross-tab operator lock after confirming the session. */
async function mutate<T>(action: string, body: unknown): Promise<T> {
  return withOperatorLock(async () => { await principalInsideLock(); return operatorRequest<T>(action, body); });
}

/**
 * The salon's subscription on its detail page: state, plan, amount, method, trial end, current
 * period, next invoice and the read-only instant, with Change billing, Move plan and the invoices; an
 * unbilled salon shows "No subscription" and Start subscription.
 */
export function SubscriptionCard({ salon, onSalonChanged }: { salon: Detail; onSalonChanged: () => void }) {
  const t = useTranslations('console');
  const router = useRouter();
  const [subscription, setSubscription] = useState<Subscription | null>(null);
  const [unbilled, setUnbilled] = useState(false);
  const [loading, setLoading] = useState(true);
  const [failure, setFailure] = useState<OperatorFailure | null>(null);
  const [notice, setNotice] = useState<string | null>(null);
  const load = useCallback(async () => {
    setLoading(true); setFailure(null);
    try { setSubscription(await operatorRead<Subscription>(`subscription?id=${encodeURIComponent(salon.id)}`)); setUnbilled(false); }
    catch (error) {
      const f = asFailure(error);
      if (f.code === 'invalid_session') { router.replace('/console/sign-in'); router.refresh(); return; }
      if (f.code === 'not_found') { setSubscription(null); setUnbilled(true); }
      else setFailure(f);
    } finally { setLoading(false); }
  }, [router, salon.id]);
  useEffect(() => { let active = true; Promise.resolve().then(() => { if (active) void load(); }); return () => { active = false; }; }, [load]);
  const zone = salon.timeZone;
  const value = (text: string | null, testId: string) => <dd className="m-0 wrap-anywhere" data-testid={testId}>{text ?? <span className="text-muted-foreground">{t('none')}</span>}</dd>;
  const changed = (message: string) => { setNotice(message); void load(); };
  return (
    <Card asChild className="gap-5 p-4 rail:p-6">
      <section aria-labelledby="console-subscription-title" data-testid="console-subscription-card" aria-busy={loading || undefined}>
        <div className="flex min-w-0 flex-wrap items-center justify-between gap-3">
          <CardTitle asChild className="text-title leading-heading"><h2 id="console-subscription-title">{t('subscription')}</h2></CardTitle>
          {subscription && <SubscriptionBadge state={subscription.state} />}
        </div>
        {!salon.ownerEmail && <Alert variant="notice" role="status" data-testid="console-no-billing-contact"><p className="m-0">{t('noBillingContact')}</p></Alert>}
        {notice && <Alert variant="success" role="status" data-testid="console-subscription-notice">{notice}</Alert>}
        {failure && <ConsoleFailure failure={failure} busy={loading} onRetry={() => void load()} />}
        {loading && !subscription && !unbilled && !failure && (
          <div className="flex flex-col gap-3" role="status" data-testid="console-subscription-loading"><span className="sr-only">{t('loading')}</span>{[0, 1, 2].map((row) => <Skeleton key={row} className="h-field" aria-hidden="true" />)}</div>
        )}
        {unbilled && (
          <Empty surface="canvas" data-testid="console-subscription-none">
            <EmptyHeader><EmptyTitle>{t('noSubscription')}</EmptyTitle><EmptyDescription>{t('noSubscriptionHelp')}</EmptyDescription></EmptyHeader>
            <StartSubscription salon={salon} onStarted={() => changed(t('subscriptionStarted'))} />
          </Empty>
        )}
        {subscription && (
          <>
            <dl className="m-0 grid gap-x-6 gap-y-3 rail:grid-cols-2">
              <div><dt className="text-small">{t('plan')}</dt>{value(subscription.plan ? t('planLabel', { name: subscription.plan.name, key: subscription.plan.key }) : null, 'console-subscription-plan')}</div>
              <div><dt className="text-small">{t('monthlyAmountLabel')}</dt>{value(formatMoney(subscription.monthlyAmount, subscription.currency, LOCALE), 'console-subscription-amount')}</div>
              <div><dt className="text-small">{t('paymentMethod')}</dt>{value(t(`method_${subscription.paymentMethod}` as 'method_card'), 'console-subscription-method')}</div>
              <div><dt className="text-small">{t('trialEnds')}</dt>{value(formatDate(subscription.trialEndsAt, zone, LOCALE), 'console-subscription-trial-end')}</div>
              <div><dt className="text-small">{t('currentPeriod')}</dt>{value(subscription.currentPeriod ? t('period', { start: formatDate(subscription.currentPeriod.start, zone, LOCALE), end: formatDate(subscription.currentPeriod.end, zone, LOCALE) }) : null, 'console-subscription-period')}</div>
              <div><dt className="text-small">{t('nextInvoice')}</dt>{value(formatDate(subscription.nextInvoiceAt, zone, LOCALE), 'console-subscription-next-invoice')}</div>
              {subscription.readOnlyAt && <div><dt className="text-small">{t(subscription.state === 'read_only' ? 'readOnlySince' : 'readOnlyFrom')}</dt>{value(formatDate(subscription.readOnlyAt, zone, LOCALE), 'console-subscription-read-only-at')}</div>}
            </dl>
            <div className="flex flex-wrap gap-2">
              <ChangeSubscription salon={salon} subscription={subscription} onChanged={() => changed(t('subscriptionChanged'))} />
              <MovePlan salon={salon} onMoved={() => { onSalonChanged(); changed(t('planMoved')); }} />
              <Button asChild variant="outline"><Link href={`/console/salons/${salon.id}/invoices`} className="no-underline" data-testid="console-view-invoices">{t('viewInvoices')}</Link></Button>
            </div>
          </>
        )}
      </section>
    </Card>
  );
}

function MethodField({ value, error, onChange }: { value: string; error: boolean; onChange: (value: string) => void }) {
  const t = useTranslations('console');
  return <ConsoleChoiceField name="paymentMethod" label={t('paymentMethod')} value={value} error={error} options={METHODS.map((method) => ({ value: method, label: t(`method_${method}`) }))} onChange={onChange} />;
}

/** Start subscription, for an unbilled salon; a salon without a currency is given one here (LKR by default). */
function StartSubscription({ salon, onStarted }: { salon: Detail; onStarted: () => void }) {
  const t = useTranslations('console');
  const router = useRouter();
  const [open, setOpen] = useState(false);
  const [values, setValues] = useState({ amount: '', method: 'bank_transfer', currency: 'LKR' });
  const [busy, setBusy] = useState(false);
  const [failure, setFailure] = useState<OperatorFailure | null>(null);
  const idempotency = useIdempotencyKey();
  const needsCurrency = !salon.currency;
  function change(next: boolean) { setOpen(next); if (!next) { setFailure(null); idempotency.reset(); } }
  async function submit() {
    setBusy(true); setFailure(null);
    const payload = { id: salon.id, monthlyAmount: parseMinorUnits(values.amount), paymentMethod: values.method, ...(needsCurrency ? { currency: values.currency } : {}) };
    try {
      await mutate('subscription-start', { ...payload, idempotencyKey: idempotency.keyFor(payload) });
      idempotency.reset(); setOpen(false); onStarted();
    } catch (error) {
      const f = asFailure(error);
      if (f.code === 'invalid_session') { router.replace('/console/sign-in'); router.refresh(); return; }
      if (!uncertain(f)) idempotency.reset();
      setFailure(f);
    } finally { setBusy(false); }
  }
  const has = (name: string) => Boolean(failure?.fields.includes(name));
  return (
    <ConsoleSheet title={t('startSubscription')} description={t('startSubscriptionHelp')} open={open} onOpenChange={change} busy={busy} testId="console-start-subscription-sheet"
      trigger={<Button data-testid="console-start-subscription">{t('startSubscription')}</Button>}>
      {failure && <ConsoleFailure failure={failure} />}
      {failure && uncertain(failure) && <Alert variant="notice" role="status" data-testid="console-uncertain">{t('uncertain')}</Alert>}
      <form className="flex min-w-0 flex-col gap-6" noValidate onSubmit={(event) => { event.preventDefault(); void submit(); }}>
        <FieldGroup className="gap-4">
          <ConsoleTextField name="monthlyAmount" label={t('monthlyAmount', { currency: salon.currency ?? values.currency })} description={t('monthlyAmountHelp')} required inputMode="decimal" autoComplete="off" value={values.amount} error={has('monthlyAmount')} onChange={(event) => setValues({ ...values, amount: event.target.value })} />
          <MethodField value={values.method} error={has('paymentMethod')} onChange={(method) => setValues({ ...values, method })} />
          {needsCurrency && <ConsoleTextField name="subscriptionCurrency" label={t('currency')} description={t('subscriptionCurrencyHelp')} required minLength={3} maxLength={3} autoComplete="off" value={values.currency} error={has('currency')} onChange={(event) => setValues({ ...values, currency: event.target.value })} />}
        </FieldGroup>
        <div className="flex flex-wrap gap-2">
          <Button type="submit" data-testid="console-start-subscription-submit" disabled={busy} aria-busy={busy || undefined}>{busy ? <><Spinner />{t('saving')}</> : t('startSubscription')}</Button>
          <Button type="button" variant="outline" data-testid="console-start-subscription-cancel" disabled={busy} onClick={() => change(false)}>{t('cancel')}</Button>
        </div>
      </form>
    </ConsoleSheet>
  );
}

/** Change billing: the monthly amount (from the next invoice) and the payment method. */
function ChangeSubscription({ salon, subscription, onChanged }: { salon: Detail; subscription: Subscription; onChanged: () => void }) {
  const t = useTranslations('console');
  const router = useRouter();
  const [open, setOpen] = useState(false);
  const [values, setValues] = useState({ amount: minorUnitsText(subscription.monthlyAmount), method: subscription.paymentMethod as string });
  const [busy, setBusy] = useState(false);
  const [failure, setFailure] = useState<OperatorFailure | null>(null);
  function change(next: boolean) {
    setOpen(next); setFailure(null);
    if (next) setValues({ amount: minorUnitsText(subscription.monthlyAmount), method: subscription.paymentMethod });
  }
  async function submit() {
    setBusy(true); setFailure(null);
    try {
      await mutate('subscription-change', { id: salon.id, version: subscription.version, monthlyAmount: parseMinorUnits(values.amount), paymentMethod: values.method });
      setOpen(false); onChanged();
    } catch (error) {
      const f = asFailure(error);
      if (f.code === 'invalid_session') { router.replace('/console/sign-in'); router.refresh(); return; }
      setFailure(f);
    } finally { setBusy(false); }
  }
  const has = (name: string) => Boolean(failure?.fields.includes(name));
  return (
    <ConsoleSheet title={t('changeBilling')} description={t('changeBillingHelp')} open={open} onOpenChange={change} busy={busy} testId="console-change-subscription-sheet"
      trigger={<Button variant="outline" data-testid="console-change-subscription">{t('changeBilling')}</Button>}>
      {failure && <ConsoleFailure failure={failure} />}
      <form className="flex min-w-0 flex-col gap-6" noValidate onSubmit={(event) => { event.preventDefault(); void submit(); }}>
        <FieldGroup className="gap-4">
          <ConsoleTextField name="monthlyAmount" label={t('monthlyAmount', { currency: subscription.currency })} description={t('monthlyAmountHelp')} required inputMode="decimal" autoComplete="off" value={values.amount} error={has('monthlyAmount')} onChange={(event) => setValues({ ...values, amount: event.target.value })} />
          <MethodField value={values.method} error={has('paymentMethod')} onChange={(method) => setValues({ ...values, method })} />
        </FieldGroup>
        <div className="flex flex-wrap gap-2">
          <Button type="submit" data-testid="console-change-subscription-submit" disabled={busy} aria-busy={busy || undefined}>{busy ? <><Spinner />{t('saving')}</> : t('save')}</Button>
          <Button type="button" variant="outline" data-testid="console-change-subscription-cancel" disabled={busy} onClick={() => change(false)}>{t('cancel')}</Button>
        </div>
      </form>
    </ConsoleSheet>
  );
}

/** Move plan: refused while the salon's usage exceeds the new plan's limits. */
export function MovePlan({ salon, onMoved }: { salon: Detail; onMoved: () => void }) {
  const t = useTranslations('console');
  const router = useRouter();
  const [open, setOpen] = useState(false);
  const [planId, setPlanId] = useState('');
  const [busy, setBusy] = useState(false);
  const [failure, setFailure] = useState<OperatorFailure | null>(null);
  function change(next: boolean) { setOpen(next); setFailure(null); if (next) setPlanId(''); }
  async function submit() {
    setBusy(true); setFailure(null);
    try {
      await mutate('plan-move', { id: salon.id, version: salon.version, planId });
      setOpen(false); onMoved();
    } catch (error) {
      const f = asFailure(error);
      if (f.code === 'invalid_session') { router.replace('/console/sign-in'); router.refresh(); return; }
      setFailure(f);
    } finally { setBusy(false); }
  }
  return (
    <ConsoleSheet title={t('movePlan')} description={t('movePlanHelp', { plan: salon.plan ? salon.plan.name : t('noPlan') })} open={open} onOpenChange={change} busy={busy} testId="console-move-plan-sheet"
      trigger={<Button variant="outline" data-testid="console-move-plan">{t('movePlan')}</Button>}>
      {failure && <ConsoleFailure failure={failure} />}
      {failure?.code === 'entitlement_exceeded' && <Alert variant="notice" role="status" data-testid="console-move-plan-exceeded">{t('movePlanExceeded')}</Alert>}
      <form className="flex min-w-0 flex-col gap-6" noValidate onSubmit={(event) => { event.preventDefault(); void submit(); }}>
        <PlanPicker name="movePlanId" label={t('newPlan')} placeholder={t('choosePlan')} emptyText={t('noOtherPlans')} value={planId} error={Boolean(failure?.fields.includes('planId'))}
          exclude={salon.plan?.id} onFailure={setFailure}
          optionLabel={(plan) => t('planSeats', { name: plan.name, key: plan.key, seats: plan.staffSeats })} onChange={(plan) => setPlanId(plan.id)} />
        <div className="flex flex-wrap gap-2">
          <Button type="submit" data-testid="console-move-plan-submit" disabled={busy || !planId} aria-busy={busy || undefined}>{busy ? <><Spinner />{t('saving')}</> : t('movePlan')}</Button>
          <Button type="button" variant="outline" data-testid="console-move-plan-cancel" disabled={busy} onClick={() => change(false)}>{t('cancel')}</Button>
        </div>
      </form>
    </ConsoleSheet>
  );
}

/** A salon's invoices, newest first, paged in place; an open invoice offers Record transfer. */
export function InvoiceList({ id }: { id: string }) {
  const t = useTranslations('console');
  const router = useRouter();
  const [salon, setSalon] = useState<Detail | null>(null);
  const [data, setData] = useState<DirectoryPage<InvoiceSummary> | null>(null);
  const [page, setPage] = useState(0);
  const [loading, setLoading] = useState(true);
  const [failure, setFailure] = useState<OperatorFailure | null>(null);
  const [recorded, setRecorded] = useState<{ number: string; payment: Payment } | null>(null);
  const load = useCallback(async (next: number) => {
    setLoading(true); setFailure(null);
    try {
      const [detail, invoices] = await Promise.all([
        operatorRead<Detail>(`salon?id=${encodeURIComponent(id)}`),
        operatorRead<DirectoryPage<InvoiceSummary>>(`invoices?id=${encodeURIComponent(id)}&page=${next}&size=${PAGE_SIZE}`),
      ]);
      setSalon(detail); setData(invoices); setPage(next);
    } catch (error) {
      const f = asFailure(error);
      if (f.code === 'invalid_session') { router.replace('/console/sign-in'); router.refresh(); return; }
      setFailure(f);
    } finally { setLoading(false); }
  }, [id, router]);
  useEffect(() => { let active = true; Promise.resolve().then(() => { if (active) void load(0); }); return () => { active = false; }; }, [load]);
  const zone = salon?.timeZone ?? 'UTC';
  return (
    <div className="flex min-w-0 flex-col gap-6" data-testid="console-invoices">
      <Button asChild variant="link" size="link" className="self-start"><Link href={`/console/salons/${id}`} data-testid="console-back-to-salon">{t('backToSalon')}</Link></Button>
      <h1 className="m-0 text-workspace-title leading-heading font-semibold wrap-anywhere" data-testid="console-invoices-title">{salon?.name ? t('invoicesOf', { name: salon.name }) : t('invoices')}</h1>
      {recorded && (
        <Alert variant="success" role="status" data-testid="console-transfer-recorded">
          <AlertTitle>{t('transferRecorded')}</AlertTitle>
          <p className="m-0">{t('transferRecordedHelp', { number: recorded.number, amount: formatMoney(recorded.payment.amount, data?.items.find((invoice) => invoice.number === recorded.number)?.currency ?? 'LKR', LOCALE), reference: recorded.payment.reference ?? '' })}</p>
        </Alert>
      )}
      {failure && <ConsoleFailure failure={failure} busy={loading} onRetry={failure.code === 'not_found' || failure.code === 'bad_request' ? undefined : () => void load(page)} />}
      {loading && !data && <ConsoleLoading />}
      {data && data.items.length === 0 && (
        <Empty surface="card" data-testid="console-invoices-empty"><EmptyHeader><EmptyTitle>{t('invoicesEmpty')}</EmptyTitle><EmptyDescription>{t('invoicesEmptyHelp')}</EmptyDescription></EmptyHeader></Empty>
      )}
      {data && data.items.length > 0 && (
        <ItemGroup className="gap-2" data-testid="console-invoices-list" aria-busy={loading || undefined}>
          {data.items.map((invoice) => (
            <Item variant="outline" className="bg-card" role="listitem" key={invoice.id} data-testid="console-invoice-row" data-id={invoice.id}>
              <ItemContent>
                <ItemTitle className="wrap-anywhere"><span data-testid="console-invoice-number">{invoice.number}</span><span data-testid="console-invoice-amount">{formatMoney(invoice.amount, invoice.currency, LOCALE)}</span></ItemTitle>
                <ItemDescription className="wrap-anywhere" data-testid="console-invoice-period">{t('period', { start: formatDate(invoice.periodStart, zone, LOCALE), end: formatDate(invoice.periodEnd, zone, LOCALE) })}</ItemDescription>
                <div className="flex flex-wrap items-center gap-2">
                  <InvoiceBadge status={invoice.status} />
                  <span className="text-small" data-testid="console-invoice-due">{invoice.status === 'paid' && invoice.paidAt ? t('paidOn', { date: formatDate(invoice.paidAt, zone, LOCALE) }) : t('dueOn', { date: formatDate(invoice.dueAt, zone, LOCALE) })}</span>
                </div>
              </ItemContent>
              {invoice.status === 'open' && salon && (
                <ItemActions>
                  <RecordTransfer invoice={invoice} timeZone={zone} onRecorded={(payment) => { setRecorded({ number: invoice.number, payment }); void load(page); }} />
                </ItemActions>
              )}
            </Item>
          ))}
        </ItemGroup>
      )}
      {data && data.total > 0 && <DirectoryPager area="invoices" page={page} size={PAGE_SIZE} total={data.total} busy={loading} onPage={(next) => void load(next)} />}
    </div>
  );
}

/** Record transfer against one open invoice: reference, the exact amount and the received date. */
function RecordTransfer({ invoice, timeZone, onRecorded }: { invoice: InvoiceSummary; timeZone: string; onRecorded: (payment: Payment) => void }) {
  const t = useTranslations('console');
  const router = useRouter();
  const message = useFailureMessage();
  const [open, setOpen] = useState(false);
  const today = () => new Intl.DateTimeFormat('en-CA', { timeZone, year: 'numeric', month: '2-digit', day: '2-digit' }).format(new Date());
  const [values, setValues] = useState({ reference: '', amount: minorUnitsText(invoice.amount), receivedOn: '' });
  const [busy, setBusy] = useState(false);
  const [failure, setFailure] = useState<OperatorFailure | null>(null);
  const idempotency = useIdempotencyKey();
  function change(next: boolean) {
    setOpen(next); setFailure(null);
    if (next) setValues({ reference: '', amount: minorUnitsText(invoice.amount), receivedOn: today() });
    else idempotency.reset();
  }
  async function submit() {
    setBusy(true); setFailure(null);
    const payload = { id: invoice.id, reference: values.reference, amount: parseMinorUnits(values.amount), receivedOn: values.receivedOn };
    try {
      const payment = await mutate<Payment>('payment-record', { ...payload, idempotencyKey: idempotency.keyFor(payload) });
      idempotency.reset(); setOpen(false); onRecorded(payment);
    } catch (error) {
      const f = asFailure(error);
      if (f.code === 'invalid_session') { router.replace('/console/sign-in'); router.refresh(); return; }
      if (!uncertain(f)) idempotency.reset();
      setFailure(f);
    } finally { setBusy(false); }
  }
  const has = (name: string) => Boolean(failure?.fields.includes(name));
  return (
    <ConsoleSheet title={t('recordTransfer')} description={t('recordTransferHelp', { number: invoice.number, amount: formatMoney(invoice.amount, invoice.currency, LOCALE) })} open={open} onOpenChange={change} busy={busy} testId="console-record-transfer-sheet"
      trigger={<Button variant="outline" size="sm" data-testid="console-record-transfer" data-id={invoice.id}>{t('recordTransfer')}</Button>}>
      {failure && (failure.code === 'invalid_state'
        ? <Alert variant="notice" role="alert" data-testid="console-error" data-code={failure.code}>{message(failure)}</Alert>
        : <ConsoleFailure failure={failure} />)}
      {failure && uncertain(failure) && <Alert variant="notice" role="status" data-testid="console-uncertain">{t('uncertain')}</Alert>}
      <form className="flex min-w-0 flex-col gap-6" noValidate onSubmit={(event) => { event.preventDefault(); void submit(); }}>
        <FieldGroup className="gap-4">
          <ConsoleTextField name="transferReference" label={t('transferReference')} description={t('transferReferenceHelp')} required maxLength={120} autoComplete="off" value={values.reference} error={has('reference')} onChange={(event) => setValues({ ...values, reference: event.target.value })} />
          <ConsoleTextField name="transferAmount" label={t('transferAmount', { currency: invoice.currency })} description={t('transferAmountHelp')} required inputMode="decimal" autoComplete="off" value={values.amount} error={has('amount')} onChange={(event) => setValues({ ...values, amount: event.target.value })} />
          <ConsoleTextField name="transferReceivedOn" type="date" label={t('transferReceivedOn')} description={t('transferReceivedOnHelp')} required value={values.receivedOn} error={has('receivedOn')} onChange={(event) => setValues({ ...values, receivedOn: event.target.value })} />
        </FieldGroup>
        <div className="flex flex-wrap gap-2">
          <Button type="submit" data-testid="console-record-transfer-submit" disabled={busy} aria-busy={busy || undefined}>{busy ? <><Spinner />{t('saving')}</> : t('recordTransfer')}</Button>
          <Button type="button" variant="outline" data-testid="console-record-transfer-cancel" disabled={busy} onClick={() => change(false)}>{t('cancel')}</Button>
        </div>
      </form>
    </ConsoleSheet>
  );
}
