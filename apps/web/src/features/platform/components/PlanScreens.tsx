'use client';
import Link from 'next/link';
import { useEffect, useRef, useState } from 'react';
import { useRouter } from 'next/navigation';
import { useTranslations } from 'next-intl';
import type { components } from '@salon/api-contracts';
import { Alert, AlertTitle } from '@/components/ui/alert';
import { Badge } from '@/components/ui/badge';
import { Button } from '@/components/ui/button';
import { Card, CardTitle } from '@/components/ui/card';
import { Checkbox } from '@/components/ui/checkbox';
import { Empty, EmptyDescription, EmptyHeader, EmptyTitle } from '@/components/ui/empty';
import { Field, FieldGroup, FieldLabel } from '@/components/ui/field';
import { Item, ItemContent, ItemDescription, ItemGroup, ItemTitle } from '@/components/ui/item';
import { Spinner } from '@/components/ui/spinner';
import { formatInstant, viewerTimeZone } from '@salon/format';
import { operatorRead, operatorRequest, OperatorFailure, withOperatorLock, principalInsideLock } from '@/features/platform/client/operator-session';
import { ConsoleFailure, ConsoleLoading, ConsoleTextField, consoleTestId, uncertain } from './ConsoleControls';
import { ConsoleSheet } from './ConsoleSheet';
import { DirectoryPager, DirectorySearch } from './DirectoryControls';
import { PAGE_SIZE, useDirectory } from '@/features/platform/hooks/use-directory';

export type Plan = components['schemas']['PlanDefinition'];

const LIMITS = ['staffSeats', 'branches', 'messageAllowance'] as const;
const FEATURES = ['customDomain', 'dedicatedApp'] as const;

/** One key per unchanged pending submission; a changed one gets a new key. */
export function useIdempotencyKey() {
  const attempt = useRef<{ key: string; payload: string } | null>(null);
  return {
    keyFor(payload: unknown): string {
      const serialized = JSON.stringify(payload);
      if (attempt.current?.payload === serialized) return attempt.current.key;
      const key = crypto.randomUUID();
      attempt.current = { key, payload: serialized };
      return key;
    },
    reset() { attempt.current = null; },
  };
}

/** The plan directory: search, rows with their limits, paging, and Create plan. */
export function PlanDirectory() {
  const t = useTranslations('console');
  const { query, page, data, loading, failure, load } = useDirectory<Plan>('plans');
  return (
    <div className="flex min-w-0 flex-col gap-6" data-testid="console-plans">
      <div className="flex min-w-0 flex-wrap items-center justify-between gap-3">
        <h1 className="m-0 text-workspace-title leading-heading font-semibold" data-testid="console-plans-title">{t('plans')}</h1>
        <CreatePlan onCreated={() => void load(0, '')} />
      </div>
      <DirectorySearch area="plans" help={t('searchPlansHelp')} busy={loading} onSearch={(q) => void load(0, q)} />
      {failure && <ConsoleFailure failure={failure} busy={loading} onRetry={() => void load(page, query)} />}
      {loading && !data && <ConsoleLoading />}
      {data && data.items.length === 0 && (
        <Empty surface="card" data-testid="console-plans-empty"><EmptyHeader><EmptyTitle>{query ? t('plansNoMatch') : t('plansEmpty')}</EmptyTitle>{!query && <EmptyDescription>{t('plansEmptyHelp')}</EmptyDescription>}</EmptyHeader></Empty>
      )}
      {data && data.items.length > 0 && (
        <ItemGroup className="gap-2" data-testid="console-plans-list" aria-busy={loading || undefined}>
          {data.items.map((plan) => (
            <Item asChild variant="outline" className="bg-card" key={plan.id} data-testid="console-plan-row" data-id={plan.id}>
              <Link href={`/console/plans/${plan.id}`} className="no-underline">
                <ItemContent>
                  <ItemTitle className="wrap-anywhere">{plan.name}</ItemTitle>
                  <ItemDescription className="wrap-anywhere">{plan.key}</ItemDescription>
                  <div className="flex flex-wrap gap-2">
                    {LIMITS.map((limit) => <Badge key={limit} variant="secondary" className="max-w-full whitespace-normal wrap-anywhere">{t('labelValue', { label: t(limit), value: plan[limit].toLocaleString('en-LK') })}</Badge>)}
                  </div>
                </ItemContent>
              </Link>
            </Item>
          ))}
        </ItemGroup>
      )}
      {data && data.total > 0 && <DirectoryPager area="plans" page={page} size={PAGE_SIZE} total={data.total} busy={loading} onPage={(next) => void load(next, query)} />}
    </div>
  );
}

const EMPTY_PLAN = { key: '', name: '', staffSeats: '', branches: '', messageAllowance: '', customDomain: false, dedicatedApp: false };

/** Create plan, in its sheet. Every field is required; the API validates for real. */
function CreatePlan({ onCreated }: { onCreated: () => void }) {
  const t = useTranslations('console');
  const router = useRouter();
  const [open, setOpen] = useState(false);
  const [values, setValues] = useState(EMPTY_PLAN);
  const [busy, setBusy] = useState(false);
  const [failure, setFailure] = useState<OperatorFailure | null>(null);
  const [created, setCreated] = useState<Plan | null>(null);
  const idempotency = useIdempotencyKey();
  const number = (value: string) => (value.trim() === '' ? null : Number(value));
  function change(next: boolean) {
    setOpen(next);
    if (!next) { setValues(EMPTY_PLAN); setFailure(null); idempotency.reset(); }
    else setCreated(null);
  }
  async function submit() {
    setBusy(true); setFailure(null);
    const payload = { key: values.key, name: values.name, staffSeats: number(values.staffSeats), branches: number(values.branches), messageAllowance: number(values.messageAllowance), customDomain: values.customDomain, dedicatedApp: values.dedicatedApp };
    const idempotencyKey = idempotency.keyFor(payload);
    try {
      const plan = await withOperatorLock(async () => { await principalInsideLock(); return operatorRequest<Plan>('plan-create', { ...payload, idempotencyKey }); });
      idempotency.reset(); setCreated(plan); setOpen(false); setValues(EMPTY_PLAN); onCreated();
    } catch (error) {
      const f = error instanceof OperatorFailure ? error : new OperatorFailure('service_unavailable');
      if (f.code === 'invalid_session') { router.replace('/console/sign-in'); router.refresh(); return; }
      if (!uncertain(f)) idempotency.reset();
      setFailure(f);
    } finally { setBusy(false); }
  }
  const field = (name: keyof typeof EMPTY_PLAN) => failure?.fields.includes(name);
  return (
    <>
      <ConsoleSheet title={t('createPlan')} description={t('immutable')} open={open} onOpenChange={change} busy={busy} testId="console-create-plan-sheet"
        trigger={<Button data-testid="console-create-plan">{t('createPlan')}</Button>}>
        {failure && <ConsoleFailure failure={failure} />}
        {failure && uncertain(failure) && <Alert variant="notice" role="status" data-testid="console-uncertain">{t('uncertain')}</Alert>}
        <form className="flex min-w-0 flex-col gap-5" noValidate onSubmit={(event) => { event.preventDefault(); void submit(); }}>
          <FieldGroup className="gap-4">
            <ConsoleTextField name="planKey" label={t('planKey')} description={t('planKeyHelp')} required maxLength={30} autoComplete="off" value={values.key} error={field('key')} onChange={(e) => setValues({ ...values, key: e.target.value })} />
            <ConsoleTextField name="planName" label={t('planName')} required maxLength={120} autoComplete="off" value={values.name} error={field('name')} onChange={(e) => setValues({ ...values, name: e.target.value })} />
            {LIMITS.map((limit) => (
              <ConsoleTextField key={limit} name={limit} label={t(limit)} description={t('limitHelp', { min: limit === 'messageAllowance' ? 0 : 1 })} required type="number" inputMode="numeric" min={limit === 'messageAllowance' ? 0 : 1} max={1000000} step={1} value={values[limit]} error={field(limit)} onChange={(e) => setValues({ ...values, [limit]: e.target.value })} />
            ))}
            {FEATURES.map((feature) => (
              <Field key={feature} orientation="horizontal" data-invalid={field(feature) || undefined}>
                <Checkbox id={`console-${feature}`} data-testid={`console-${feature === 'customDomain' ? 'custom-domain' : 'dedicated-app'}`} checked={values[feature]} onCheckedChange={(state) => setValues({ ...values, [feature]: state === true })} />
                <FieldLabel htmlFor={`console-${feature}`} className="min-h-touch justify-center text-body font-normal">{t(feature)}</FieldLabel>
              </Field>
            ))}
          </FieldGroup>
          <div className="flex flex-wrap gap-2">
            <Button type="submit" data-testid="console-create-plan-submit" disabled={busy} aria-busy={busy || undefined}>{busy ? <><Spinner />{t('creating')}</> : t('create')}</Button>
            <Button type="button" variant="outline" data-testid="console-create-plan-cancel" disabled={busy} onClick={() => change(false)}>{t('cancel')}</Button>
          </div>
        </form>
      </ConsoleSheet>
      {created && (
        <Alert variant="success" role="status" className="basis-full" data-testid="console-plan-created">
          <AlertTitle>{t('planCreated')}</AlertTitle>
          <p className="m-0 wrap-anywhere">{t('planLabel', { name: created.name, key: created.key })}</p>
          <Button asChild variant="link" size="link"><Link href={`/console/plans/${created.id}`} data-testid="console-plan-created-view">{t('viewPlan')}</Link></Button>
        </Alert>
      )}
    </>
  );
}

/** One plan: its exact immutable limits and features. */
export function PlanDetail({ id }: { id: string }) {
  const t = useTranslations('console');
  const router = useRouter();
  const [plan, setPlan] = useState<Plan | null>(null);
  const [failure, setFailure] = useState<OperatorFailure | null>(null);
  const [loading, setLoading] = useState(true);
  async function load() {
    setLoading(true); setFailure(null);
    try { setPlan(await operatorRead<Plan>(`plan?id=${encodeURIComponent(id)}`)); }
    catch (error) {
      const f = error instanceof OperatorFailure ? error : new OperatorFailure('service_unavailable');
      if (f.code === 'invalid_session') { router.replace('/console/sign-in'); router.refresh(); return; }
      setFailure(f);
    } finally { setLoading(false); }
  }
  // eslint-disable-next-line react-hooks/exhaustive-deps
  useEffect(() => { let active = true; Promise.resolve().then(() => { if (active) void load(); }); return () => { active = false; }; }, [id]);
  return (
    <div className="flex min-w-0 flex-col gap-6" data-testid="console-plan-detail">
      <Button asChild variant="link" size="link" className="self-start"><Link href="/console/plans" data-testid="console-back-to-plans">{t('backToPlans')}</Link></Button>
      {failure && <ConsoleFailure failure={failure} busy={loading} onRetry={failure.code === 'not_found' || failure.code === 'bad_request' ? undefined : () => void load()} />}
      {loading && !plan && <ConsoleLoading rows={4} />}
      {plan && (
        <Card asChild className="gap-4 p-4 rail:p-6">
          <section aria-labelledby="console-plan-name">
            <CardTitle asChild className="text-workspace-title leading-heading wrap-anywhere"><h1 id="console-plan-name" data-testid="console-plan-name">{plan.name}</h1></CardTitle>
            <p className="m-0 font-mono wrap-anywhere" data-testid="console-plan-key">{plan.key}</p>
            <h2 className="m-0 text-large">{t('limits')}</h2>
            <dl className="m-0 grid gap-3 rail:grid-cols-3">
              {LIMITS.map((limit) => <div key={limit} className="rounded-field bg-background p-3"><dt className="text-small">{t(limit)}</dt><dd className="m-0 text-large font-semibold" data-testid={consoleTestId(`plan-${limit}`)}>{plan[limit].toLocaleString('en-LK')}</dd></div>)}
            </dl>
            <h2 className="m-0 text-large">{t('features')}</h2>
            <div className="flex flex-wrap gap-2">
              {FEATURES.map((feature) => <Badge key={feature} className="max-w-full whitespace-normal wrap-anywhere" variant={plan[feature] ? 'positive' : 'outline'} data-testid={consoleTestId(`plan-${feature}`)}>{t('labelValue', { label: t(feature), value: plan[feature] ? t('included') : t('notIncluded') })}</Badge>)}
            </div>
            <p className="m-0 text-small">{t('created', { time: formatInstant(plan.createdAt, viewerTimeZone()) })}</p>
            <Alert role={undefined} className="bg-background text-small" data-testid="console-plan-immutable">{t('immutable')}</Alert>
          </section>
        </Card>
      )}
    </div>
  );
}
