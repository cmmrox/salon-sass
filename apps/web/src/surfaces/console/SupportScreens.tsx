'use client';
import { useCallback, useEffect, useState } from 'react';
import { useRouter } from 'next/navigation';
import { useTranslations } from 'next-intl';
import type { components } from '@salon/api-contracts/platform';
import { ExternalLink } from 'lucide-react';
import { Alert, AlertDescription, AlertTitle } from '@/components/ui/alert';
import {
  AlertDialog,
  AlertDialogAction,
  AlertDialogCancel,
  AlertDialogContent,
  AlertDialogDescription,
  AlertDialogFooter,
  AlertDialogHeader,
  AlertDialogTitle,
  AlertDialogTrigger,
} from '@/components/ui/alert-dialog';
import { Badge } from '@/components/ui/badge';
import { Button } from '@/components/ui/button';
import { Card, CardTitle } from '@/components/ui/card';
import { Empty, EmptyDescription, EmptyHeader, EmptyTitle } from '@/components/ui/empty';
import { FieldGroup } from '@/components/ui/field';
import { Item, ItemActions, ItemContent, ItemDescription, ItemGroup, ItemTitle } from '@/components/ui/item';
import { Spinner } from '@/components/ui/spinner';
import { formatInstant, viewerTimeZone } from '@salon/format';
import {
  operatorRead,
  operatorRequest,
  OperatorFailure,
  principalInsideLock,
  withOperatorLock,
  ConsoleChoiceField,
  ConsoleFailure,
  ConsoleLoading,
  ConsoleTextArea,
  ConsoleSheet,
  DirectoryPager,
} from '@/features/platform';

type Detail = components['schemas']['SalonDetail'];
type Entry = components['schemas']['SupportEntry'];
type Session = components['schemas']['SupportSessionView'];
type SessionPage = components['schemas']['SupportSessionPage'];

const SIZE = 20;
const DURATIONS = ['5', '15', '30', '60', '90', '120'] as const;

function asFailure(error: unknown): OperatorFailure {
  return error instanceof OperatorFailure ? error : new OperatorFailure('service_unavailable');
}

/** A console mutation under the cross-tab operator lock, after confirming the session. */
async function mutate<T>(action: string, body: unknown): Promise<T> {
  return withOperatorLock(async () => {
    await principalInsideLock();
    return operatorRequest<T>(action, body);
  });
}

/**
 * A fresh entry link to open the salon's workspace in the support view (F03-S04): single-use and valid
 * for 60 seconds, so it is shown as an action to take now, in a new tab, with its deadline.
 */
function EntryLink({ entry, testId }: { entry: Entry; testId: string }) {
  const t = useTranslations('console');
  const zone = viewerTimeZone();
  return (
    <Alert variant="success" role="status" data-testid={testId}>
      <AlertTitle>{t('supportReady')}</AlertTitle>
      <AlertDescription>
        <p className="m-0" data-testid={`${testId}-help`}>
          {t('supportReadyHelp', {
            code: formatInstant(entry.codeExpiresAt, zone),
            ends: formatInstant(entry.expiresAt, zone),
          })}
        </p>
        <Button asChild className="mt-2 self-start">
          <a href={entry.entryUrl} target="_blank" rel="noopener noreferrer" data-testid={`${testId}-open`}>
            <ExternalLink aria-hidden="true" />
            {t('openWorkspace')}
          </a>
        </Button>
      </AlertDescription>
    </Alert>
  );
}

/**
 * Start support session, on a salon's detail page for a Support operator: a sheet with the reason and
 * the duration (5 to 120 minutes, default 30), then the link that opens the salon's workspace.
 */
export function SupportCard({ salon }: { salon: Detail }) {
  const t = useTranslations('console');
  const router = useRouter();
  const [open, setOpen] = useState(false);
  const [values, setValues] = useState({ reason: '', durationMinutes: '30' });
  const [busy, setBusy] = useState(false);
  const [failure, setFailure] = useState<OperatorFailure | null>(null);
  const [entry, setEntry] = useState<Entry | null>(null);
  const available = salon.status === 'active';
  function change(next: boolean) {
    setOpen(next);
    if (!next) {
      setFailure(null);
      setValues({ reason: '', durationMinutes: '30' });
    }
  }
  async function submit() {
    setBusy(true);
    setFailure(null);
    try {
      const started = await mutate<Entry>('support-start', {
        id: salon.id,
        reason: values.reason,
        durationMinutes: Number(values.durationMinutes),
      });
      setEntry(started);
      setOpen(false);
      setValues({ reason: '', durationMinutes: '30' });
    } catch (error) {
      const f = asFailure(error);
      if (f.code === 'invalid_session') {
        router.replace('/console/sign-in');
        router.refresh();
        return;
      }
      setFailure(f);
    } finally {
      setBusy(false);
    }
  }
  const has = (field: string) => Boolean(failure?.fields.includes(field));
  return (
    <Card asChild className="gap-4 p-4 rail:p-6">
      <section aria-labelledby="console-support-title" data-testid="console-support-card">
        <CardTitle asChild className="text-title leading-heading">
          <h2 id="console-support-title">{t('supportTitle')}</h2>
        </CardTitle>
        <p className="m-0">{available ? t('supportHelp') : t('supportUnavailable')}</p>
        {entry && <EntryLink entry={entry} testId="console-support-started" />}
        {available && (
          <ConsoleSheet
            title={t('supportStart')}
            description={t('supportStartHelp')}
            open={open}
            onOpenChange={change}
            busy={busy}
            testId="console-support-sheet"
            trigger={
              <Button className="self-start" data-testid="console-support-start">
                {t('supportStart')}
              </Button>
            }
          >
            {failure && <ConsoleFailure failure={failure} />}
            <form
              className="flex min-w-0 flex-col gap-6"
              noValidate
              onSubmit={(event) => {
                event.preventDefault();
                void submit();
              }}
            >
              <FieldGroup className="gap-4">
                <ConsoleTextArea
                  name="supportReason"
                  label={t('reason')}
                  description={t('supportReasonHelp')}
                  required
                  minLength={10}
                  maxLength={500}
                  rows={4}
                  value={values.reason}
                  error={has('reason')}
                  onChange={(event) => setValues({ ...values, reason: event.target.value })}
                />
                <ConsoleChoiceField
                  name="supportDuration"
                  label={t('supportDuration')}
                  description={t('supportDurationHelp')}
                  value={values.durationMinutes}
                  error={has('durationMinutes')}
                  options={DURATIONS.map((minutes) => ({
                    value: minutes,
                    label: t('minutes', { minutes: Number(minutes) }),
                  }))}
                  onChange={(durationMinutes) => setValues({ ...values, durationMinutes })}
                />
              </FieldGroup>
              <div className="flex flex-wrap gap-2">
                <Button
                  type="submit"
                  data-testid="console-support-submit"
                  disabled={busy}
                  aria-busy={busy || undefined}
                >
                  {busy ? (
                    <>
                      <Spinner />
                      {t('supportStarting')}
                    </>
                  ) : (
                    t('supportStart')
                  )}
                </Button>
                <Button
                  type="button"
                  variant="outline"
                  data-testid="console-support-cancel"
                  disabled={busy}
                  onClick={() => change(false)}
                >
                  {t('cancel')}
                </Button>
              </div>
            </form>
          </ConsoleSheet>
        )}
      </section>
    </Card>
  );
}

/**
 * Support sessions in the console (F03-S04): an Admin sees every session and may revoke an active one;
 * a Support operator sees its own, opens the workspace again with a fresh link and ends them. Longer
 * than 200 lines: the list, the fresh link and the end and revoke confirmations share one load, lock
 * and refusal state.
 */
export function SupportSessionList({ admin }: { admin: boolean }) {
  const t = useTranslations('console');
  const router = useRouter();
  const [state, setState] = useState<'all' | 'active' | 'ended'>('active');
  const [page, setPage] = useState(0);
  const [data, setData] = useState<SessionPage | null>(null);
  const [loading, setLoading] = useState(true);
  const [failure, setFailure] = useState<OperatorFailure | null>(null);
  const [busy, setBusy] = useState<string | null>(null);
  const [notice, setNotice] = useState<string | null>(null);
  const [links, setLinks] = useState<Record<string, Entry>>({});
  const zone = viewerTimeZone();
  const load = useCallback(
    async (next: number, filter: 'all' | 'active' | 'ended') => {
      setLoading(true);
      setFailure(null);
      try {
        const query = `support-sessions?page=${next}&size=${SIZE}${filter === 'all' ? '' : `&state=${filter}`}`;
        setData(await operatorRead<SessionPage>(query));
        setPage(next);
      } catch (error) {
        const f = asFailure(error);
        if (f.code === 'invalid_session') {
          router.replace('/console/sign-in');
          router.refresh();
          return;
        }
        setFailure(f);
      } finally {
        setLoading(false);
      }
    },
    [router],
  );
  useEffect(() => {
    let active = true;
    Promise.resolve().then(() => {
      if (active) void load(0, state);
    });
    return () => {
      active = false;
    };
  }, [load, state]);
  async function act(session: Session, action: 'support-end' | 'support-entry-code') {
    setBusy(session.id);
    setFailure(null);
    setNotice(null);
    try {
      if (action === 'support-entry-code') {
        const entry = await mutate<Entry>(action, { id: session.id });
        setLinks((current) => ({ ...current, [session.id]: entry }));
      } else {
        await mutate(action, { id: session.id });
        setNotice(admin ? t('supportRevoked') : t('supportEnded'));
        await load(page, state);
      }
    } catch (error) {
      const f = asFailure(error);
      if (f.code === 'invalid_session') {
        router.replace('/console/sign-in');
        router.refresh();
        return;
      }
      setFailure(f);
      await load(page, state);
    } finally {
      setBusy(null);
    }
  }
  const items = data?.items ?? [];
  return (
    <div className="flex min-w-0 flex-col gap-6" data-testid="console-support">
      <div className="flex min-w-0 flex-col gap-2">
        <h1 className="text-workspace-title leading-heading font-semibold">{t('supportSessions')}</h1>
        <p className="m-0">{admin ? t('supportListAdmin') : t('supportListOwn')}</p>
      </div>
      <div className="max-w-sm">
        <ConsoleChoiceField
          name="supportFilter"
          label={t('supportFilter')}
          value={state}
          options={[
            { value: 'active', label: t('supportActive') },
            { value: 'ended', label: t('supportEndedState') },
            { value: 'all', label: t('supportAll') },
          ]}
          onChange={(value) => setState(value as 'all' | 'active' | 'ended')}
        />
      </div>
      {notice && (
        <Alert variant="success" role="status" data-testid="console-support-notice">
          {notice}
        </Alert>
      )}
      {failure && <ConsoleFailure failure={failure} busy={loading} onRetry={() => void load(page, state)} />}
      {loading && !data && <ConsoleLoading rows={4} />}
      {data && items.length === 0 && (
        <Empty surface="canvas" data-testid="console-support-empty">
          <EmptyHeader>
            <EmptyTitle>{t('supportEmpty')}</EmptyTitle>
            <EmptyDescription>{admin ? t('supportEmptyAdmin') : t('supportEmptyOwn')}</EmptyDescription>
          </EmptyHeader>
        </Empty>
      )}
      {items.length > 0 && (
        <ItemGroup className="gap-2" data-testid="console-support-list" aria-busy={loading || undefined}>
          {items.map((session) => (
            <Item
              variant="outline"
              role="listitem"
              key={session.id}
              className="flex-wrap bg-card"
              data-testid="console-support-row"
              data-id={session.id}
              data-state={session.state}
            >
              <ItemContent className="min-w-0">
                <ItemTitle className="wrap-anywhere" data-testid="console-support-salon">
                  {session.salon.name ?? session.salon.slug}
                  <Badge
                    variant={session.state === 'active' ? 'positive' : 'outline'}
                    data-testid="console-support-state"
                  >
                    {session.state === 'active'
                      ? t('supportActive')
                      : t(`supportEndedBy_${session.endedBy ?? 'expiry'}` as 'supportEndedBy_expiry')}
                  </Badge>
                </ItemTitle>
                <ItemDescription className="line-clamp-none wrap-anywhere" data-testid="console-support-hostname">
                  {session.salon.hostname}
                </ItemDescription>
                <p className="m-0 wrap-anywhere" data-testid="console-support-operator">
                  {t('supportOperator', { name: session.operator.displayName })}
                </p>
                <p className="m-0 wrap-anywhere whitespace-pre-line" data-testid="console-support-reason">
                  {session.reason}
                </p>
                <p className="m-0 flex flex-wrap gap-x-3 text-small" data-testid="console-support-times">
                  <span>{t('supportStarted', { time: formatInstant(session.startedAt, zone) })}</span>
                  <span>
                    {session.state === 'active'
                      ? t('supportEnds', { time: formatInstant(session.expiresAt, zone) })
                      : t('supportEndedAt', { time: formatInstant(session.endedAt ?? session.expiresAt, zone) })}
                  </span>
                </p>
                {links[session.id] && <EntryLink entry={links[session.id]!} testId="console-support-link" />}
              </ItemContent>
              {session.state === 'active' && (
                <ItemActions className="w-full flex-wrap rail:w-auto">
                  {!admin && (
                    <Button
                      variant="outline"
                      data-testid="console-support-open"
                      data-id={session.id}
                      disabled={busy !== null}
                      onClick={() => void act(session, 'support-entry-code')}
                    >
                      {busy === session.id ? <Spinner /> : null}
                      {t('supportNewLink')}
                    </Button>
                  )}
                  <AlertDialog>
                    <AlertDialogTrigger asChild>
                      <Button
                        variant="destructive"
                        data-testid="console-support-end"
                        data-id={session.id}
                        disabled={busy !== null}
                      >
                        {admin ? t('supportRevoke') : t('supportEnd')}
                      </Button>
                    </AlertDialogTrigger>
                    <AlertDialogContent data-testid="console-support-confirm">
                      <AlertDialogHeader>
                        <AlertDialogTitle>{admin ? t('supportRevokeTitle') : t('supportEndTitle')}</AlertDialogTitle>
                        <AlertDialogDescription>
                          {t('supportEndHelp', { salon: session.salon.name ?? session.salon.slug })}
                        </AlertDialogDescription>
                      </AlertDialogHeader>
                      <AlertDialogFooter>
                        <AlertDialogCancel data-testid="console-support-confirm-cancel">
                          {t('cancel')}
                        </AlertDialogCancel>
                        <AlertDialogAction
                          variant="destructive"
                          data-testid="console-support-confirm-end"
                          onClick={() => void act(session, 'support-end')}
                        >
                          {admin ? t('supportRevoke') : t('supportEnd')}
                        </AlertDialogAction>
                      </AlertDialogFooter>
                    </AlertDialogContent>
                  </AlertDialog>
                </ItemActions>
              )}
            </Item>
          ))}
        </ItemGroup>
      )}
      {data && data.total > 0 && (
        <DirectoryPager
          area="support"
          page={page}
          size={SIZE}
          total={data.total}
          busy={loading}
          onPage={(next) => void load(next, state)}
        />
      )}
    </div>
  );
}
