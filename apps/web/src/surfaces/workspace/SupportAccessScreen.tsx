'use client';
import { useEffect, useState } from 'react';
import { useTranslations } from 'next-intl';
import type { components } from '@salon/api-contracts/platform';
import { Alert } from '@/components/ui/alert';
import { AlertDialog, AlertDialogAction, AlertDialogCancel, AlertDialogContent, AlertDialogDescription, AlertDialogFooter, AlertDialogHeader, AlertDialogTitle, AlertDialogTrigger } from '@/components/ui/alert-dialog';
import { Badge } from '@/components/ui/badge';
import { Button } from '@/components/ui/button';
import { Card } from '@/components/ui/card';
import { Empty, EmptyDescription, EmptyHeader, EmptyTitle } from '@/components/ui/empty';
import { Item, ItemActions, ItemContent, ItemDescription, ItemGroup, ItemTitle } from '@/components/ui/item';
import { Pagination, PaginationContent, PaginationCurrent, PaginationItem, PaginationNext, PaginationPrevious } from '@/components/ui/pagination';
import { Skeleton } from '@/components/ui/skeleton';
import { Spinner } from '@/components/ui/spinner';
import { ensurePrincipal, IdentityFailure, identityRequest, principalInsideLock, supportsLocks, withIdentityLock } from '@/features/identity';
import { formatInstant } from '@salon/format';

type Session = components['schemas']['SalonSupportSession'];
type Page = components['schemas']['SalonSupportSessionPage'];
const SIZE = 10;

/**
 * The Owner's Support access page (F03-S04): every support session of the salon, active and past,
 * with its operator's name, reason, start, end and state, and Revoke for an active one (confirmed in
 * an Alert Dialog). A revoked session stops on its next request.
 */
export function SupportAccessScreen({ timeZone }: { timeZone: string }) {
  const t = useTranslations('support');
  const [page, setPage] = useState(0);
  const [data, setData] = useState<Page | null>(null);
  const [loading, setLoading] = useState(true);
  const [failure, setFailure] = useState<IdentityFailure | null>(null);
  const [busy, setBusy] = useState<string | null>(null);
  const [revoked, setRevoked] = useState(false);
  async function load(next = page) {
    setLoading(true); setFailure(null);
    try {
      await ensurePrincipal('staff');
      setData(await identityRequest<Page>('staff', `support-sessions?page=${next}`));
      setPage(next);
    } catch (error) {
      const f = error instanceof IdentityFailure ? error : new IdentityFailure('service_unavailable');
      if (f.code === 'invalid_session') { window.location.replace('/workspace/sign-in'); return; }
      setFailure(f);
    } finally { setLoading(false); }
  }
  useEffect(() => {
    let active = true;
    Promise.resolve().then(() => {
      if (active) void load(0);
    });
    return () => {
      active = false;
    };
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, []);
  async function revoke(session: Session) {
    setBusy(session.id); setFailure(null); setRevoked(false);
    try {
      if (!supportsLocks()) throw new IdentityFailure('browser_unsupported');
      await withIdentityLock('staff', async () => { await principalInsideLock('staff'); await identityRequest('staff', 'support-revoke', { id: session.id }); });
      setRevoked(true);
      await load(page);
    } catch (error) {
      setFailure(error instanceof IdentityFailure ? error : new IdentityFailure('service_unavailable'));
      await load(page).catch(() => undefined);
    } finally { setBusy(null); }
  }
  const message = (code: string) => (t.has(`errors.${code}` as 'errors.service_unavailable') ? t(`errors.${code}` as 'errors.service_unavailable') : t('errors.service_unavailable'));
  const items = data?.items ?? [];
  return (
    <Card asChild className="gap-4 p-4 rail:p-6">
      <section aria-labelledby="support-access-heading" data-testid="support-access">
        <h2 id="support-access-heading" className="text-title leading-heading font-semibold">{t('accessHeading')}</h2>
        <p className="m-0">{t('accessHelp')}</p>
        {revoked && <Alert variant="success" role="status" data-testid="support-access-revoked">{t('revokedNotice')}</Alert>}
        {failure && <Alert variant="notice" data-testid="support-access-error"><p className="m-0">{message(failure.code)}</p><Button variant="outline" className="mt-2" data-testid="support-access-retry" disabled={loading} onClick={() => void load(page)}>{t('retry')}</Button></Alert>}
        {loading && !data && <div className="flex flex-col gap-2" data-testid="support-access-loading" aria-busy="true">{[0, 1, 2].map((n) => <Skeleton key={n} className="h-20 w-full rounded-field" />)}</div>}
        {data && items.length === 0 && <Empty data-testid="support-access-empty"><EmptyHeader><EmptyTitle>{t('accessEmpty')}</EmptyTitle><EmptyDescription>{t('accessEmptyHelp')}</EmptyDescription></EmptyHeader></Empty>}
        {items.length > 0 && (
          <ItemGroup className="gap-2" data-testid="support-access-list">
            {items.map((session) => (
              <Item variant="outline" role="listitem" key={session.id} className="flex-wrap" data-testid="support-access-row" data-id={session.id} data-state={session.state}>
                <ItemContent className="min-w-0">
                  <ItemTitle className="wrap-anywhere" data-testid="support-access-operator">
                    {session.operatorName}
                    <Badge variant={session.state === 'active' ? 'positive' : 'outline'} data-testid="support-access-state">{session.state === 'active' ? t('stateActive') : t(`endedBy.${session.endedBy ?? 'expiry'}` as 'endedBy.expiry')}</Badge>
                  </ItemTitle>
                  <ItemDescription className="line-clamp-none wrap-anywhere whitespace-pre-line" data-testid="support-access-reason">{session.reason}</ItemDescription>
                  <p className="m-0 flex flex-wrap gap-x-3 text-small" data-testid="support-access-times">
                    <span>{t('started', { time: formatInstant(session.startedAt, timeZone) })}</span>
                    <span>{session.state === 'active' ? t('endsAt', { time: formatInstant(session.expiresAt, timeZone) }) : t('endedAt', { time: formatInstant(session.endedAt ?? session.expiresAt, timeZone) })}</span>
                  </p>
                </ItemContent>
                {session.state === 'active' && (
                  <ItemActions className="w-full rail:w-auto">
                    <AlertDialog>
                      <AlertDialogTrigger asChild>
                        <Button variant="destructive" data-testid="support-access-revoke" data-id={session.id} disabled={busy !== null}>{busy === session.id ? <><Spinner />{t('revoking')}</> : t('revoke')}</Button>
                      </AlertDialogTrigger>
                      <AlertDialogContent data-testid="support-access-confirm">
                        <AlertDialogHeader>
                          <AlertDialogTitle>{t('revokeTitle')}</AlertDialogTitle>
                          <AlertDialogDescription>{t('revokeHelp', { name: session.operatorName })}</AlertDialogDescription>
                        </AlertDialogHeader>
                        <AlertDialogFooter>
                          <AlertDialogCancel data-testid="support-access-confirm-cancel">{t('cancel')}</AlertDialogCancel>
                          <AlertDialogAction variant="destructive" data-testid="support-access-confirm-revoke" onClick={() => void revoke(session)}>{t('revoke')}</AlertDialogAction>
                        </AlertDialogFooter>
                      </AlertDialogContent>
                    </AlertDialog>
                  </ItemActions>
                )}
              </Item>
            ))}
          </ItemGroup>
        )}
        {data && data.total > SIZE && (
          <Pagination aria-label={t('pages')} data-testid="support-access-pagination">
            <PaginationContent>
              <PaginationItem><PaginationPrevious data-testid="support-access-previous" disabled={loading || page === 0} onClick={() => void load(page - 1)}>{t('previous')}</PaginationPrevious></PaginationItem>
              <PaginationItem><PaginationCurrent data-testid="support-access-page">{t('page', { page: page + 1 })}</PaginationCurrent></PaginationItem>
              <PaginationItem><PaginationNext data-testid="support-access-next" disabled={loading || (page + 1) * SIZE >= data.total} onClick={() => void load(page + 1)}>{t('next')}</PaginationNext></PaginationItem>
            </PaginationContent>
          </Pagination>
        )}
      </section>
    </Card>
  );
}
