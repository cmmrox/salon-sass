'use client';
import Link from 'next/link';
import { useEffect, useState } from 'react';
import { useRouter } from 'next/navigation';
import { useTranslations } from 'next-intl';
import type { components } from '@salon/api-contracts/platform';
import { Copy } from 'lucide-react';
import { Alert, AlertTitle } from '@/components/ui/alert';
import { Badge } from '@/components/ui/badge';
import { Button } from '@/components/ui/button';
import { Card, CardTitle } from '@/components/ui/card';
import { Empty, EmptyDescription, EmptyHeader, EmptyTitle } from '@/components/ui/empty';
import { FieldGroup, FieldLegend, FieldSet } from '@/components/ui/field';
import { Item, ItemContent, ItemDescription, ItemGroup, ItemTitle } from '@/components/ui/item';
import { Spinner } from '@/components/ui/spinner';
import { formatInstant, formatPhone, parseMinorUnits, viewerTimeZone } from '@salon/format';
import { operatorRead, operatorRequest, OperatorFailure, principalInsideLock, withOperatorLock, ConsoleChoiceField, ConsoleFailure, ConsoleLoading, ConsolePasswordField, ConsoleTextArea, ConsoleTextField, uncertain, ConsoleSheet, DirectoryPager, DirectorySearch, SubscriptionCard, type Plan, useIdempotencyKey, PlanPicker, PAGE_SIZE, useDirectory } from '@/features/platform';
import { ExportCard, LifecycleCard, SalonStatusBadge } from './LifecycleScreens';
import { SupportCard } from './SupportScreens';

type Summary = components['schemas']['SalonSummary'];
type Detail = components['schemas']['SalonDetail'];
type Created = components['schemas']['SalonCreated'];

/** The salon directory: search by name or web address, rows with status and plan, paging, Create salon. */
export function SalonDirectory({ platformDomain, permissions = ['operator.salon.create'] }: { platformDomain: string; permissions?: string[] }) {
  const t = useTranslations('console');
  const { query, page, data, loading, failure, load } = useDirectory<Summary>('salons');
  return (
    <div className="flex min-w-0 flex-col gap-6" data-testid="console-salons">
      <div className="flex min-w-0 flex-wrap items-center justify-between gap-3">
        <h1 className="m-0 text-workspace-title leading-heading font-semibold" data-testid="console-salons-title">{t('salons')}</h1>
        {permissions.includes('operator.salon.create') && <CreateSalon platformDomain={platformDomain} onCreated={() => void load(0, '')} />}
      </div>
      <DirectorySearch area="salons" help={t('searchSalonsHelp')} busy={loading} onSearch={(q) => void load(0, q)} />
      {failure && <ConsoleFailure failure={failure} busy={loading} onRetry={() => void load(page, query)} />}
      {loading && !data && <ConsoleLoading />}
      {data && data.items.length === 0 && (
        <Empty surface="card" data-testid="console-salons-empty"><EmptyHeader><EmptyTitle>{query ? t('salonsNoMatch') : t('salonsEmpty')}</EmptyTitle>{!query && <EmptyDescription>{t('salonsEmptyHelp')}</EmptyDescription>}</EmptyHeader></Empty>
      )}
      {data && data.items.length > 0 && (
        <ItemGroup className="gap-2" data-testid="console-salons-list" aria-busy={loading || undefined}>
          {data.items.map((salon) => (
            <Item asChild variant="outline" className="bg-card" key={salon.id} data-testid="console-salon-row" data-id={salon.id}>
              <Link href={`/console/salons/${salon.id}`} className="no-underline">
                <ItemContent>
                  <ItemTitle className="wrap-anywhere">{salon.name ?? t('notConfigured')}</ItemTitle>
                  <ItemDescription className="wrap-anywhere">{salon.hostname}</ItemDescription>
                  <div className="flex flex-wrap gap-2">
                    <SalonStatusBadge status={salon.status} />
                    <Badge variant="secondary" className="max-w-full whitespace-normal wrap-anywhere" data-testid="console-salon-plan">{salon.plan ? salon.plan.name : t('noPlan')}</Badge>
                  </div>
                </ItemContent>
              </Link>
            </Item>
          ))}
        </ItemGroup>
      )}
      {data && data.total > 0 && <DirectoryPager area="salons" page={page} size={PAGE_SIZE} total={data.total} busy={loading} onPage={(next) => void load(next, query)} />}
    </div>
  );
}

const EMPTY = { name: '', slug: '', country: 'LK', currency: 'LKR', timeZone: 'Asia/Colombo', defaultLanguage: 'en', contactEmail: '', contactPhone: '', address: '', planId: '', ownerName: '', ownerEmail: '', ownerPassword: '', monthlyAmount: '', paymentMethod: 'bank_transfer' };
type Values = typeof EMPTY;

/**
 * Create salon, in its sheet: the provisioning profile, an existing plan and the first Owner. One
 * idempotency key per unchanged pending submission; after an uncertain failure the same request can
 * be retried. The initial password lives only in this form's state and is cleared on success, on
 * cancel and after any definite refusal; it is never shown afterwards.
 */
function CreateSalon({ platformDomain, onCreated }: { platformDomain: string; onCreated: () => void }) {
  const t = useTranslations('console');
  const router = useRouter();
  const [open, setOpen] = useState(false);
  const [values, setValues] = useState<Values>(EMPTY);
  const [plan, setPlan] = useState<Plan | null>(null);
  const [plansAvailable, setPlansAvailable] = useState(false);
  const [busy, setBusy] = useState(false);
  const [failure, setFailure] = useState<OperatorFailure | null>(null);
  const [created, setCreated] = useState<{ result: Created; plan: string } | null>(null);
  const idempotency = useIdempotencyKey();
  function change(next: boolean) {
    setOpen(next);
    if (!next) { setValues((v) => ({ ...v, ownerPassword: '' })); setFailure(null); idempotency.reset(); }
    else { setCreated(null); setPlan(null); setPlansAvailable(false); }
  }
  const set = (name: keyof Values) => (event: { target: { value: string } }) => setValues({ ...values, [name]: event.target.value });
  async function submit() {
    setBusy(true); setFailure(null);
    const payload = {
      name: values.name, slug: values.slug, country: values.country, currency: values.currency, timeZone: values.timeZone,
      defaultLanguage: values.defaultLanguage, contactEmail: values.contactEmail, contactPhone: values.contactPhone, address: values.address,
      planId: values.planId, owner: { name: values.ownerName, email: values.ownerEmail, password: values.ownerPassword },
      subscription: { monthlyAmount: parseMinorUnits(values.monthlyAmount), paymentMethod: values.paymentMethod },
    };
    const idempotencyKey = idempotency.keyFor(payload);
    try {
      const result = await withOperatorLock(async () => { await principalInsideLock(); return operatorRequest<Created>('salon-create', { ...payload, idempotencyKey }); });
      idempotency.reset();
      setCreated({ result, plan: plan?.id === result.planId ? plan.name : '' });
      setValues(EMPTY); setOpen(false); onCreated();
    } catch (error) {
      const f = error instanceof OperatorFailure ? error : new OperatorFailure('service_unavailable');
      if (f.code === 'invalid_session') { router.replace('/console/sign-in'); router.refresh(); return; }
      if (!uncertain(f)) { idempotency.reset(); setValues((v) => ({ ...v, ownerPassword: '' })); }
      setFailure(f);
    } finally { setBusy(false); }
  }
  const has = (...names: string[]) => names.some((name) => failure?.fields.includes(name));
  const scheme = typeof window !== 'undefined' ? window.location.protocol : 'https:';
  const port = typeof window !== 'undefined' && window.location.port ? `:${window.location.port}` : '';
  return (
    <>
      <ConsoleSheet title={t('createSalon')} open={open} onOpenChange={change} busy={busy} testId="console-create-salon-sheet"
        trigger={<Button data-testid="console-create-salon">{t('createSalon')}</Button>}>
        {failure && <ConsoleFailure failure={failure} />}
        {failure && uncertain(failure) && <Alert variant="notice" role="status" data-testid="console-uncertain">{t('uncertain')}</Alert>}
        <form className="flex min-w-0 flex-col gap-6" noValidate onSubmit={(event) => { event.preventDefault(); void submit(); }}>
          <FieldSet>
            <FieldLegend>{t('profile')}</FieldLegend>
            <FieldGroup className="gap-4">
              <ConsoleTextField name="salonName" label={t('salonName')} required maxLength={120} autoComplete="organization" value={values.name} error={has('name')} onChange={set('name')} />
              <ConsoleTextField name="slug" label={t('slug')} description={t('slugHelp', { example: `${values.slug.trim().toLowerCase() || 'example'}.${platformDomain}` })} required minLength={3} maxLength={30} autoComplete="off" value={values.slug} error={has('slug')} onChange={set('slug')} />
              <PlanPicker name="planId" label={t('plan')} placeholder={t('choosePlan')} emptyText={t('noPlansToChoose')} value={values.planId} error={has('planId')} onFailure={setFailure} onAvailable={setPlansAvailable}
                optionLabel={(choice) => t('planLabel', { name: choice.name, key: choice.key })} onChange={(choice) => { setPlan(choice); setValues((v) => ({ ...v, planId: choice.id })); }} />
              <ConsoleTextField name="country" label={t('country')} description={t('countryHelp')} required minLength={2} maxLength={2} autoComplete="country" value={values.country} error={has('country')} onChange={set('country')} />
              <ConsoleTextField name="currency" label={t('currency')} description={t('currencyHelp')} required minLength={3} maxLength={3} autoComplete="off" value={values.currency} error={has('currency')} onChange={set('currency')} />
              <ConsoleTextField name="timeZone" label={t('timeZone')} description={t('timeZoneHelp')} required maxLength={64} autoComplete="off" value={values.timeZone} error={has('timeZone')} onChange={set('timeZone')} />
              <ConsoleChoiceField name="defaultLanguage" label={t('defaultLanguage')} value={values.defaultLanguage} error={has('defaultLanguage')}
                options={['en', 'si', 'ta'].map((language) => ({ value: language, label: t(`language_${language}` as 'language_en') }))} onChange={(defaultLanguage) => setValues({ ...values, defaultLanguage })} />
            </FieldGroup>
          </FieldSet>
          <FieldSet>
            <FieldLegend>{t('contact')}</FieldLegend>
            <FieldGroup className="gap-4">
              <ConsoleTextField name="contactEmail" type="email" label={t('contactEmail')} maxLength={254} autoComplete="email" value={values.contactEmail} error={has('contactEmail')} onChange={set('contactEmail')} />
              <ConsoleTextField name="contactPhone" type="tel" label={t('contactPhone')} description={t('contactPhoneHelp')} maxLength={32} autoComplete="tel" value={values.contactPhone} error={has('contactPhone')} onChange={set('contactPhone')} />
              <ConsoleTextArea name="address" label={t('address')} maxLength={500} rows={3} autoComplete="street-address" value={values.address} error={has('address')} onChange={set('address')} />
            </FieldGroup>
          </FieldSet>
          <FieldSet>
            <FieldLegend>{t('subscription')}</FieldLegend>
            <FieldGroup className="gap-4">
              <ConsoleTextField name="monthlyAmount" label={t('monthlyAmount', { currency: values.currency.trim().toUpperCase() || 'LKR' })} description={t('monthlyAmountCreateHelp')} required inputMode="decimal" autoComplete="off" value={values.monthlyAmount} error={has('subscription.monthlyAmount', 'subscription')} onChange={set('monthlyAmount')} />
              <ConsoleChoiceField name="paymentMethod" label={t('paymentMethod')} value={values.paymentMethod} error={has('subscription.paymentMethod')}
                options={['card', 'bank_transfer'].map((method) => ({ value: method, label: t(`method_${method}` as 'method_card') }))} onChange={(paymentMethod) => setValues({ ...values, paymentMethod })} />
            </FieldGroup>
          </FieldSet>
          <FieldSet>
            <FieldLegend>{t('ownerSection')}</FieldLegend>
            <FieldGroup className="gap-4">
              <ConsoleTextField name="ownerName" label={t('ownerName')} required maxLength={120} autoComplete="off" value={values.ownerName} error={has('owner.name', 'owner')} onChange={set('ownerName')} />
              <ConsoleTextField name="ownerEmail" type="email" label={t('ownerEmail')} required maxLength={254} autoComplete="off" value={values.ownerEmail} error={has('owner.email', 'owner')} onChange={set('ownerEmail')} />
              <ConsolePasswordField name="ownerPassword" label={t('ownerPassword')} description={t('ownerPasswordHelp')} required minLength={15} maxLength={128} autoComplete="new-password" value={values.ownerPassword} error={has('owner.password', 'owner')} onChange={set('ownerPassword')} />
            </FieldGroup>
          </FieldSet>
          <div className="flex flex-wrap gap-2">
            <Button type="submit" data-testid="console-create-salon-submit" disabled={busy || !plansAvailable} aria-busy={busy || undefined}>{busy ? <><Spinner />{t('creating')}</> : t('create')}</Button>
            <Button type="button" variant="outline" data-testid="console-create-salon-cancel" disabled={busy} onClick={() => change(false)}>{t('cancel')}</Button>
          </div>
        </form>
      </ConsoleSheet>
      {created && (
        <Alert variant="success" role="status" className="basis-full" data-testid="console-salon-created">
          <AlertTitle>{t('salonCreated')}</AlertTitle>
          <p className="m-0 wrap-anywhere" data-testid="console-salon-created-hostname">{t('salonCreatedHelp', { hostname: created.result.hostname, plan: created.plan })}</p>
          <p className="m-0 wrap-anywhere" data-testid="console-salon-created-next">{t('ownerNext', { url: `${scheme}//${created.result.hostname}${port}/workspace/sign-in` })}</p>
          <Button asChild variant="link" size="link"><Link href={`/console/salons/${created.result.id}`} data-testid="console-salon-created-view">{t('viewSalon')}</Link></Button>
        </Alert>
      )}
    </>
  );
}

/** One salon's provisioning profile and plan. Read-only; no automatic workspace entry. */
export function SalonDetail({ id, permissions = [] }: { id: string; permissions?: string[] }) {
  const t = useTranslations('console');
  const router = useRouter();
  const [salon, setSalon] = useState<Detail | null>(null);
  const [failure, setFailure] = useState<OperatorFailure | null>(null);
  const [loading, setLoading] = useState(true);
  const [copied, setCopied] = useState(false);
  async function load() {
    setLoading(true); setFailure(null);
    try { setSalon(await operatorRead<Detail>(`salon?id=${encodeURIComponent(id)}`)); }
    catch (error) {
      const f = error instanceof OperatorFailure ? error : new OperatorFailure('service_unavailable');
      if (f.code === 'invalid_session') { router.replace('/console/sign-in'); router.refresh(); return; }
      setFailure(f);
    } finally { setLoading(false); }
  }
  useEffect(() => {
    let active = true;
    Promise.resolve().then(() => {
      if (active) void load();
    });
    return () => {
      active = false;
    };
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [id]);
  const value = (text: string | null | undefined, testId: string) => <dd className="m-0 wrap-anywhere whitespace-pre-line" data-testid={testId}>{text ?? <span className="text-muted-foreground">{t('notConfigured')}</span>}</dd>;
  return (
    <div className="flex min-w-0 flex-col gap-6" data-testid="console-salon-detail">
      <Button asChild variant="link" size="link" className="self-start"><Link href="/console/salons" data-testid="console-back-to-salons">{t('backToSalons')}</Link></Button>
      {failure && <ConsoleFailure failure={failure} busy={loading} onRetry={failure.code === 'not_found' || failure.code === 'bad_request' ? undefined : () => void load()} />}
      {loading && !salon && <ConsoleLoading rows={5} />}
      {salon && (
        <Card asChild className="gap-5 p-4 rail:p-6">
          <section aria-labelledby="console-salon-name">
            <div className="flex min-w-0 flex-col gap-2">
              <CardTitle asChild className="text-workspace-title leading-heading wrap-anywhere"><h1 id="console-salon-name" data-testid="console-salon-name">{salon.name ?? t('notConfigured')}</h1></CardTitle>
              <div className="flex flex-wrap gap-2">
                <SalonStatusBadge status={salon.status} />
                <Badge variant="secondary" className="max-w-full whitespace-normal wrap-anywhere" data-testid="console-salon-plan">{salon.plan ? t('planLabel', { name: salon.plan.name, key: salon.plan.key }) : t('noPlan')}</Badge>
              </div>
            </div>
            <div className="flex min-w-0 flex-col items-start gap-2 rounded-field bg-background p-3 rail:flex-row rail:items-center">
              <span className="w-full min-w-0 flex-1 font-mono wrap-anywhere" data-testid="console-salon-hostname">{salon.hostname}</span>
              <Button variant="outline" size="sm" data-testid="console-copy-hostname" onClick={() => { void navigator.clipboard?.writeText(salon.hostname).then(() => setCopied(true)); }}><Copy aria-hidden="true" />{t('copyHostname')}</Button>
            </div>
            {copied && <Alert variant="success" role="status" data-testid="console-hostname-copied">{t('hostnameCopied')}</Alert>}
            <dl className="m-0 grid gap-x-6 gap-y-3 rail:grid-cols-2">
              <div><dt className="text-small">{t('country')}</dt>{value(salon.country, 'console-salon-country')}</div>
              <div><dt className="text-small">{t('currency')}</dt>{value(salon.currency, 'console-salon-currency')}</div>
              <div><dt className="text-small">{t('timeZone')}</dt>{value(salon.timeZone, 'console-salon-time-zone')}</div>
              <div><dt className="text-small">{t('defaultLanguage')}</dt>{value(salon.defaultLanguage ? t(`language_${salon.defaultLanguage}` as 'language_en') : null, 'console-salon-language')}</div>
              <div><dt className="text-small">{t('contactEmailLabel')}</dt>{value(salon.contactEmail, 'console-salon-contact-email')}</div>
              <div><dt className="text-small">{t('contactPhoneLabel')}</dt>{value(salon.contactPhone ? formatPhone(salon.contactPhone) : null, 'console-salon-contact-phone')}</div>
              <div className="rail:col-span-2"><dt className="text-small">{t('addressLabel')}</dt>{value(salon.address, 'console-salon-address')}</div>
              <div><dt className="text-small">{t('ownerContact')}</dt>{value(salon.ownerEmail, 'console-salon-owner-email')}</div>
              <div><dt className="text-small">{t('createdLabel')}</dt><dd className="m-0" data-testid="console-salon-created-at">{formatInstant(salon.createdAt, viewerTimeZone())}</dd></div>
            </dl>
          </section>
        </Card>
      )}
      {/* Each card for the operator whose permissions it needs: Support finds salons and starts support sessions (F03-S04). */}
      {salon && permissions.includes('operator.support.start') && <SupportCard salon={salon} />}
      {salon && permissions.includes('operator.salon.suspend') && <LifecycleCard salon={salon} onChanged={() => void load()} />}
      {salon && permissions.includes('operator.export.read') && (salon.status === 'closed' || salon.status === 'deleted') && <ExportCard key={`${salon.id}-export-${salon.version}`} salon={salon} />}
      {salon && permissions.includes('operator.billing.read') && <SubscriptionCard salon={salon} onSalonChanged={() => void load()} />}
    </div>
  );
}
