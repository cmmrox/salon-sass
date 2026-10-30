'use client';
import { useCallback, useEffect, useState } from 'react';
import { useRouter } from 'next/navigation';
import { useTranslations } from 'next-intl';
import type { components } from '@salon/api-contracts';
import { Download, RefreshCw } from 'lucide-react';
import { Alert, AlertTitle } from '@/components/ui/alert';
import { Badge } from '@/components/ui/badge';
import { Button } from '@/components/ui/button';
import { Card, CardTitle } from '@/components/ui/card';
import { Empty, EmptyDescription, EmptyHeader, EmptyTitle } from '@/components/ui/empty';
import { FieldGroup } from '@/components/ui/field';
import { Item, ItemContent, ItemDescription, ItemGroup, ItemTitle } from '@/components/ui/item';
import { Skeleton } from '@/components/ui/skeleton';
import { Spinner } from '@/components/ui/spinner';
import { formatBytes, formatDate, formatInstant } from '@salon/format';
import { operatorRead, operatorRequest, OperatorFailure, principalInsideLock, withOperatorLock } from '@/features/platform/client/operator-session';
import { ConsoleFailure, ConsoleTextArea, ConsoleTextField } from '@/features/platform/components/ConsoleControls';
import { ConsoleSheet } from '@/features/platform/components/ConsoleSheet';
import { DirectoryPager } from '@/features/platform/components/DirectoryControls';

type Detail = components['schemas']['SalonDetail'];
type LifecyclePage = components['schemas']['LifecyclePage'];
type ExportView = components['schemas']['ExportView'];

const LOCALE = 'en-LK';
const HISTORY_SIZE = 10;
const RETENTION_DAYS = 30;

function asFailure(error: unknown): OperatorFailure {
  return error instanceof OperatorFailure ? error : new OperatorFailure('service_unavailable');
}

/** A salon's lifecycle state as a word, never a colour alone. */
export function SalonStatusBadge({ status, testId = 'console-salon-status' }: { status: string; testId?: string }) {
  const t = useTranslations('console');
  const variant = status === 'active' ? 'positive' : status === 'suspended' ? 'outline' : status === 'closed' ? 'destructive' : 'secondary';
  const label = status === 'active' ? t('statusActive') : status === 'suspended' ? t('statusSuspended') : status === 'closed' ? t('statusClosed') : t('statusDeleted');
  return <Badge variant={variant} data-testid={testId} data-status={status}>{label}</Badge>;
}

/** Runs a console mutation under the cross-tab operator lock after confirming the session. */
async function mutate<T>(action: string, body: unknown): Promise<T> {
  return withOperatorLock(async () => { await principalInsideLock(); return operatorRequest<T>(action, body); });
}

/**
 * The salon's lifecycle on its detail page: its state, the action its state allows (Suspend, Reactivate,
 * Close), each in a confirmation sheet with a reason, and the history of every action. Closure is
 * visibly irreversible: its sheet states what happens and when the data is deleted, and asks for the
 * salon's web address typed exactly.
 */
export function LifecycleCard({ salon, onChanged }: { salon: Detail; onChanged: () => void }) {
  const t = useTranslations('console');
  const [notice, setNotice] = useState<string | null>(null);
  const [history, setHistory] = useState(0);
  const changed = (message: string) => { setNotice(message); setHistory((n) => n + 1); onChanged(); };
  const zone = salon.timeZone;
  return (
    <Card asChild className="gap-5 p-4 rail:p-6">
      <section aria-labelledby="console-lifecycle-title" data-testid="console-lifecycle-card">
        <div className="flex min-w-0 flex-wrap items-center justify-between gap-3">
          <CardTitle asChild className="text-title leading-heading"><h2 id="console-lifecycle-title">{t('lifecycle')}</h2></CardTitle>
          <SalonStatusBadge status={salon.status} testId="console-lifecycle-status" />
        </div>
        {notice && <Alert variant="success" role="status" data-testid="console-lifecycle-notice">{notice}</Alert>}
        {salon.status === 'suspended' && <Alert variant="notice" role="status" data-testid="console-lifecycle-suspended"><p className="m-0">{t('suspendedHelp')}</p></Alert>}
        {salon.status === 'closed' && salon.deleteAt && (
          <Alert variant="destructive" role="status" data-testid="console-lifecycle-closed">
            <AlertTitle>{t('closedTitle')}</AlertTitle>
            <p className="m-0">{t('closedHelp', { date: formatDate(salon.deleteAt, zone, LOCALE) })}</p>
          </Alert>
        )}
        {salon.status === 'deleted' && salon.deletedAt && (
          <Alert variant="default" role="status" className="border border-border" data-testid="console-lifecycle-deleted">
            <AlertTitle>{t('deletedTitle')}</AlertTitle>
            <p className="m-0">{t('deletedHelp', { date: formatDate(salon.deletedAt, zone, LOCALE) })}</p>
          </Alert>
        )}
        {(salon.status === 'active' || salon.status === 'suspended') && (
          <div className="flex flex-wrap gap-2">
            {salon.status === 'active'
              ? <ReasonAction kind="suspend" salon={salon} onDone={() => changed(t('suspended'))} />
              : <ReasonAction kind="reactivate" salon={salon} onDone={() => changed(t('reactivated'))} />}
            <CloseSalon salon={salon} onClosed={() => changed(t('closedNotice'))} />
          </div>
        )}
        <LifecycleHistory salon={salon} refresh={history} />
      </section>
    </Card>
  );
}

/** Suspend or Reactivate, in its sheet, with the reason the history and the audit keep. */
function ReasonAction({ kind, salon, onDone }: { kind: 'suspend' | 'reactivate'; salon: Detail; onDone: () => void }) {
  const t = useTranslations('console');
  const router = useRouter();
  const [open, setOpen] = useState(false);
  const [reason, setReason] = useState('');
  const [busy, setBusy] = useState(false);
  const [failure, setFailure] = useState<OperatorFailure | null>(null);
  function change(next: boolean) { setOpen(next); if (!next) { setFailure(null); setReason(''); } }
  async function submit() {
    setBusy(true); setFailure(null);
    try {
      await mutate(kind === 'suspend' ? 'salon-suspend' : 'salon-reactivate', { id: salon.id, version: salon.version, reason });
      setOpen(false); setReason(''); onDone();
    } catch (error) {
      const f = asFailure(error);
      if (f.code === 'invalid_session') { router.replace('/console/sign-in'); router.refresh(); return; }
      setFailure(f);
    } finally { setBusy(false); }
  }
  const title = kind === 'suspend' ? t('suspendSalon') : t('reactivateSalon');
  return (
    <ConsoleSheet title={title} description={kind === 'suspend' ? t('suspendHelp') : t('reactivateHelp')} open={open} onOpenChange={change} busy={busy} testId={`console-${kind}-sheet`}
      trigger={<Button variant={kind === 'suspend' ? 'outline' : 'default'} data-testid={`console-${kind}`}>{title}</Button>}>
      {failure && <ConsoleFailure failure={failure} />}
      <form className="flex min-w-0 flex-col gap-6" noValidate onSubmit={(event) => { event.preventDefault(); void submit(); }}>
        <FieldGroup className="gap-4">
          <ConsoleTextArea name={`${kind}Reason`} label={t('reason')} description={t('reasonHelp')} required minLength={10} maxLength={500} rows={4} value={reason} error={Boolean(failure?.fields.includes('reason'))} onChange={(event) => setReason(event.target.value)} />
        </FieldGroup>
        <div className="flex flex-wrap gap-2">
          <Button type="submit" data-testid={`console-${kind}-submit`} disabled={busy} aria-busy={busy || undefined}>{busy ? <><Spinner />{t('saving')}</> : title}</Button>
          <Button type="button" variant="outline" data-testid={`console-${kind}-cancel`} disabled={busy} onClick={() => change(false)}>{t('cancel')}</Button>
        </div>
      </form>
    </ConsoleSheet>
  );
}

/** Close salon: irreversible; states the consequences and the deletion date; the slug typed exactly and a reason. */
function CloseSalon({ salon, onClosed }: { salon: Detail; onClosed: () => void }) {
  const t = useTranslations('console');
  const router = useRouter();
  const [open, setOpen] = useState(false);
  const [values, setValues] = useState({ confirmSlug: '', reason: '' });
  const [busy, setBusy] = useState(false);
  const [failure, setFailure] = useState<OperatorFailure | null>(null);
  const [deletion, setDeletion] = useState('');
  function change(next: boolean) {
    setOpen(next); setFailure(null);
    if (next) setDeletion(formatDate(new Date(Date.now() + RETENTION_DAYS * 86_400_000).toISOString(), salon.timeZone, LOCALE));
    else setValues({ confirmSlug: '', reason: '' });
  }
  async function submit() {
    setBusy(true); setFailure(null);
    try {
      await mutate('salon-close', { id: salon.id, version: salon.version, confirmSlug: values.confirmSlug, reason: values.reason });
      setOpen(false); setValues({ confirmSlug: '', reason: '' }); onClosed();
    } catch (error) {
      const f = asFailure(error);
      if (f.code === 'invalid_session') { router.replace('/console/sign-in'); router.refresh(); return; }
      setFailure(f);
    } finally { setBusy(false); }
  }
  const has = (name: string) => Boolean(failure?.fields.includes(name));
  return (
    <ConsoleSheet title={t('closeSalon')} description={t('closeSalonHelp')} open={open} onOpenChange={change} busy={busy} testId="console-close-sheet"
      trigger={<Button variant="destructive" data-testid="console-close">{t('closeSalon')}</Button>}>
      <Alert variant="destructive" role="note" data-testid="console-close-consequences">
        <AlertTitle>{t('closeIrreversible')}</AlertTitle>
        <ul className="m-0 flex list-disc flex-col gap-1 pl-5">
          <li>{t('closeConsequenceHost', { hostname: salon.hostname })}</li>
          <li>{t('closeConsequenceSessions')}</li>
          <li>{t('closeConsequenceBilling')}</li>
          <li>{t('closeConsequenceExport')}</li>
          <li data-testid="console-close-deletion-date">{t('closeConsequenceDeletion', { date: deletion })}</li>
          <li>{t('closeConsequenceSlug', { slug: salon.slug })}</li>
        </ul>
      </Alert>
      {failure && <ConsoleFailure failure={failure} />}
      <form className="flex min-w-0 flex-col gap-6" noValidate onSubmit={(event) => { event.preventDefault(); void submit(); }}>
        <FieldGroup className="gap-4">
          <ConsoleTextField name="confirmSlug" label={t('confirmSlug', { slug: salon.slug })} description={t('confirmSlugHelp')} required autoComplete="off" spellCheck={false} value={values.confirmSlug} error={has('confirmSlug')} onChange={(event) => setValues({ ...values, confirmSlug: event.target.value })} />
          <ConsoleTextArea name="closeReason" label={t('reason')} description={t('reasonHelp')} required minLength={10} maxLength={500} rows={4} value={values.reason} error={has('reason')} onChange={(event) => setValues({ ...values, reason: event.target.value })} />
        </FieldGroup>
        <div className="flex flex-wrap gap-2">
          <Button type="submit" variant="destructive" data-testid="console-close-submit" disabled={busy} aria-busy={busy || undefined}>{busy ? <><Spinner />{t('saving')}</> : t('closeForGood')}</Button>
          <Button type="button" variant="outline" data-testid="console-close-cancel" disabled={busy} onClick={() => change(false)}>{t('cancel')}</Button>
        </div>
      </form>
    </ConsoleSheet>
  );
}

/** Every lifecycle action of the salon, newest first: what, why, who and when. */
function LifecycleHistory({ salon, refresh }: { salon: Detail; refresh: number }) {
  const t = useTranslations('console');
  const router = useRouter();
  const [page, setPage] = useState(0);
  const [data, setData] = useState<LifecyclePage | null>(null);
  const [loading, setLoading] = useState(true);
  const [failure, setFailure] = useState<OperatorFailure | null>(null);
  const load = useCallback(async (next: number) => {
    setLoading(true); setFailure(null);
    try { setData(await operatorRead<LifecyclePage>(`lifecycle?id=${encodeURIComponent(salon.id)}&page=${next}&size=${HISTORY_SIZE}`)); setPage(next); }
    catch (error) {
      const f = asFailure(error);
      if (f.code === 'invalid_session') { router.replace('/console/sign-in'); router.refresh(); return; }
      setFailure(f);
    } finally { setLoading(false); }
  }, [router, salon.id]);
  useEffect(() => { let active = true; Promise.resolve().then(() => { if (active) void load(0); }); return () => { active = false; }; }, [load, refresh]);
  return (
    <div className="flex min-w-0 flex-col gap-3" data-testid="console-lifecycle-history" aria-busy={loading || undefined}>
      <h3 className="m-0 text-body font-semibold">{t('history')}</h3>
      {failure && <ConsoleFailure failure={failure} busy={loading} onRetry={() => void load(page)} />}
      {loading && !data && !failure && <div className="flex flex-col gap-2" role="status"><span className="sr-only">{t('loading')}</span>{[0, 1].map((row) => <Skeleton key={row} className="h-field" aria-hidden="true" />)}</div>}
      {data && data.items.length === 0 && (
        <Empty surface="canvas" data-testid="console-lifecycle-empty"><EmptyHeader><EmptyTitle>{t('historyEmpty')}</EmptyTitle><EmptyDescription>{t('historyEmptyHelp')}</EmptyDescription></EmptyHeader></Empty>
      )}
      {data && data.items.length > 0 && (
        <ItemGroup className="gap-2" data-testid="console-lifecycle-list">
          {data.items.map((event) => (
            <Item variant="outline" key={event.id} data-testid="console-lifecycle-row" data-id={event.id} data-kind={event.kind}>
              <ItemContent>
                <div className="flex flex-wrap items-center gap-2">
                  <Badge variant={event.kind === 'closed' ? 'destructive' : event.kind === 'reactivated' ? 'positive' : event.kind === 'deleted' ? 'secondary' : 'outline'} data-testid="console-lifecycle-kind">{t(`event_${event.kind}` as 'event_suspended')}</Badge>
                  <ItemTitle className="wrap-anywhere">{event.actor.kind === 'system' ? t('actorSystem') : event.actor.displayName ?? t('actorOperator')}</ItemTitle>
                </div>
                {event.reason && <ItemDescription className="wrap-anywhere whitespace-pre-line line-clamp-none" data-testid="console-lifecycle-reason">{event.reason}</ItemDescription>}
                <p className="m-0 text-small" data-testid="console-lifecycle-at">{formatInstant(event.at, salon.timeZone, LOCALE)}</p>
              </ItemContent>
            </Item>
          ))}
        </ItemGroup>
      )}
      {data && data.total > HISTORY_SIZE && <DirectoryPager area="lifecycle" page={page} size={HISTORY_SIZE} total={data.total} busy={loading} onPage={(next) => void load(next)} />}
    </div>
  );
}

/**
 * A closed salon's export: its state, when it was made, its size and checksum, when it is deleted,
 * and Download, which saves the same archive the Owner's link opens (each download is audited).
 */
export function ExportCard({ salon }: { salon: Detail }) {
  const t = useTranslations('console');
  const router = useRouter();
  const [view, setView] = useState<ExportView | null>(null);
  const [loading, setLoading] = useState(true);
  const [failure, setFailure] = useState<OperatorFailure | null>(null);
  const [downloading, setDownloading] = useState(false);
  const [downloaded, setDownloaded] = useState(false);
  const load = useCallback(async () => {
    setLoading(true); setFailure(null);
    try { setView(await operatorRead<ExportView>(`export?id=${encodeURIComponent(salon.id)}`)); }
    catch (error) {
      const f = asFailure(error);
      if (f.code === 'invalid_session') { router.replace('/console/sign-in'); router.refresh(); return; }
      setFailure(f);
    } finally { setLoading(false); }
  }, [router, salon.id]);
  useEffect(() => { let active = true; Promise.resolve().then(() => { if (active) void load(); }); return () => { active = false; }; }, [load]);
  async function download() {
    setDownloading(true); setFailure(null); setDownloaded(false);
    try {
      await operatorRead('principal');
      const response = await fetch(`/operator-web/export-archive?id=${encodeURIComponent(salon.id)}`, { cache: 'no-store' });
      if (!response.ok) {
        const data = await response.json().catch(() => ({ code: 'service_unavailable' }));
        throw new OperatorFailure(data.code ?? 'service_unavailable', [], 0, false, response.status);
      }
      const blob = await response.blob();
      const name = /filename="?([A-Za-z0-9._-]+)"?/.exec(response.headers.get('content-disposition') ?? '')?.[1] ?? 'salon-export.zip';
      const url = URL.createObjectURL(blob);
      const link = document.createElement('a');
      link.href = url; link.download = name; document.body.append(link); link.click(); link.remove();
      setTimeout(() => URL.revokeObjectURL(url), 1000);
      setDownloaded(true);
    } catch (error) {
      const f = asFailure(error);
      if (f.code === 'invalid_session') { router.replace('/console/sign-in'); router.refresh(); return; }
      setFailure(f);
    } finally { setDownloading(false); }
  }
  const zone = salon.timeZone;
  const value = (text: string | null | undefined, testId: string, mono = false) => <dd className={mono ? 'm-0 font-mono text-small wrap-anywhere' : 'm-0 wrap-anywhere'} data-testid={testId}>{text ?? <span className="text-muted-foreground">{t('none')}</span>}</dd>;
  return (
    <Card asChild className="gap-5 p-4 rail:p-6">
      <section aria-labelledby="console-export-title" data-testid="console-export-card" aria-busy={loading || undefined}>
        <div className="flex min-w-0 flex-wrap items-center justify-between gap-3">
          <CardTitle asChild className="text-title leading-heading"><h2 id="console-export-title">{t('export')}</h2></CardTitle>
          {view && <Badge variant={view.status === 'ready' ? 'positive' : view.status === 'pending' ? 'outline' : 'secondary'} data-testid="console-export-status" data-status={view.status}>{t(`export_${view.status}` as 'export_ready')}</Badge>}
        </div>
        {failure && <ConsoleFailure failure={failure} busy={loading || downloading} onRetry={failure.code === 'not_found' ? undefined : () => void load()} />}
        {loading && !view && !failure && <div className="flex flex-col gap-3" role="status" data-testid="console-export-loading"><span className="sr-only">{t('loading')}</span>{[0, 1, 2].map((row) => <Skeleton key={row} className="h-field" aria-hidden="true" />)}</div>}
        {downloaded && <Alert variant="success" role="status" data-testid="console-export-downloaded">{t('exportDownloaded')}</Alert>}
        {view && (
          <>
            {view.status === 'pending' && <Alert variant="notice" role="status" data-testid="console-export-pending"><p className="m-0">{t('exportPendingHelp')}</p></Alert>}
            {view.status === 'deleted' && <Alert variant="default" role="status" className="border border-border" data-testid="console-export-deleted"><p className="m-0">{t('exportDeletedHelp')}</p></Alert>}
            <dl className="m-0 grid gap-x-6 gap-y-3 rail:grid-cols-2">
              <div><dt className="text-small">{t('exportRequested')}</dt>{value(formatInstant(view.createdAt, zone, LOCALE), 'console-export-created')}</div>
              <div><dt className="text-small">{t('exportReady')}</dt>{value(view.readyAt ? formatInstant(view.readyAt, zone, LOCALE) : null, 'console-export-ready')}</div>
              <div><dt className="text-small">{t('exportSize')}</dt>{value(view.sizeBytes == null ? null : formatBytes(view.sizeBytes, LOCALE), 'console-export-size')}</div>
              <div><dt className="text-small">{t('exportDeleteAt')}</dt>{value(formatDate(view.deleteAt, zone, LOCALE), 'console-export-delete-at')}</div>
              <div className="rail:col-span-2"><dt className="text-small">{t('exportChecksum')}</dt>{value(view.sha256, 'console-export-sha256', true)}</div>
            </dl>
            <div className="flex flex-wrap gap-2">
              {view.status === 'ready' && <Button data-testid="console-export-download" disabled={downloading} aria-busy={downloading || undefined} onClick={() => void download()}>{downloading ? <><Spinner />{t('downloading')}</> : <><Download aria-hidden="true" />{t('downloadExport')}</>}</Button>}
              {view.status === 'pending' && <Button variant="outline" data-testid="console-export-refresh" disabled={loading} onClick={() => void load()}><RefreshCw aria-hidden="true" />{t('refresh')}</Button>}
            </div>
          </>
        )}
      </section>
    </Card>
  );
}
