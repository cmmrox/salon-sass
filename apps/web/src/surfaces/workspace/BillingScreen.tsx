'use client';
import { useState } from 'react';
import { useRouter } from 'next/navigation';
import { useTranslations } from 'next-intl';
import type { components } from '@salon/api-contracts/platform';
import { Alert } from '@/components/ui/alert';
import { Badge } from '@/components/ui/badge';
import { Empty, EmptyDescription, EmptyHeader, EmptyTitle } from '@/components/ui/empty';
import { Item, ItemContent, ItemDescription, ItemGroup, ItemTitle } from '@/components/ui/item';
import {
  Pagination,
  PaginationContent,
  PaginationCurrent,
  PaginationItem,
  PaginationNext,
  PaginationPrevious,
} from '@/components/ui/pagination';
import { formatDate, formatMoney } from '@salon/format';
import { leaveEndedSession, AccountLoading, Button, Section, type Kind } from '@/features/identity';
import { useEntitlements, useInvoices, type Entitlements } from '@/features/platform';
import { useReadFailure } from '@/lib/api-core/data-layer';

type InvoicePage = components['schemas']['InvoicePage'];

const LOCALE = 'en-LK';
const PAGE = 10;

/**
 * The Owner's Plan and billing page (F03-S02): the plan, its limits with current usage, its features,
 * the subscription state and the salon's invoices, paged in place. There is no payment entry here;
 * payment is by card through the platform or by bank transfer recorded by the platform. Longer than
 * 200 lines: mostly the markup of the one page these sections load and refresh together.
 */
export function BillingScreen({ kind, timeZone }: { kind: Kind; timeZone: string }) {
  const t = useTranslations('billing');
  const router = useRouter();
  // The page asked for, and the page whose invoices are on screen: the pager moves once the new page has arrived.
  const [page, setPage] = useState(0);
  const plan = useEntitlements(kind);
  const list = useInvoices(kind, page);
  const ready = Boolean(plan.data && list.data && !list.isPlaceholderData);
  const [shown, setShown] = useState<{ entitlements: Entitlements; invoices: InvoicePage; page: number } | null>(null);
  if (ready && plan.data && list.data && (shown?.invoices !== list.data || shown.entitlements !== plan.data))
    setShown({ entitlements: plan.data, invoices: list.data, page });
  const entitlements = shown?.entitlements ?? null;
  const invoices = shown?.invoices ?? null;
  const shownPage = shown?.page ?? 0;
  const busy = plan.isFetching || list.isFetching;
  const failed = plan.error ?? list.error;
  const error = failed && failed.code !== 'invalid_session' ? failed : null;
  useReadFailure([plan, list], (failure) => {
    if (failure.code === 'invalid_session') leaveEndedSession(kind, router);
  });
  /** Reads the page asked for again (after a failure), or another page. */
  const load = (next: number) => {
    if (next === page) {
      void plan.refetch();
      void list.refetch();
    } else setPage(next);
  };
  const date = (value: string) => formatDate(value, timeZone, LOCALE);
  const subscription = entitlements?.subscription ?? null;
  return (
    <div
      className="flex min-w-0 flex-col gap-6 rail:grid rail:grid-cols-2 rail:items-start"
      data-testid="billing-page"
      aria-busy={busy || undefined}
    >
      {error && (
        <Alert variant="notice" className="rail:col-span-full" data-testid="billing-error" data-code={error.code}>
          <p>
            {t.has(error.code as 'service_unavailable')
              ? t(error.code as 'service_unavailable')
              : t('service_unavailable')}
          </p>
          <Button testId="billing-retry" secondary disabled={busy} onClick={() => load(page)}>
            {t('retry')}
          </Button>
        </Alert>
      )}
      {!entitlements && !error && (
        <div className="rail:col-span-full">
          <AccountLoading />
        </div>
      )}
      {entitlements && (
        <>
          <Section title={t('planTitle')}>
            {entitlements.plan ? (
              <>
                <p className="m-0 text-large font-semibold wrap-anywhere" data-testid="billing-plan-name">
                  {entitlements.plan.name}
                </p>
                {entitlements.limits && (
                  <dl className="m-0 grid gap-3">
                    <div className="flex flex-wrap justify-between gap-2">
                      <dt>{t('staffSeats')}</dt>
                      <dd className="m-0 font-medium" data-testid="billing-staff-seats">
                        {t('used', { used: entitlements.usage.staffSeats, limit: entitlements.limits.staffSeats })}
                      </dd>
                    </div>
                    <div className="flex flex-wrap justify-between gap-2">
                      <dt>{t('branches')}</dt>
                      <dd className="m-0 font-medium" data-testid="billing-branches">
                        {entitlements.limits.branches}
                      </dd>
                    </div>
                    <div className="flex flex-wrap justify-between gap-2">
                      <dt>{t('messageAllowance')}</dt>
                      <dd className="m-0 font-medium" data-testid="billing-message-allowance">
                        {entitlements.limits.messageAllowance}
                      </dd>
                    </div>
                  </dl>
                )}
                {entitlements.features && (
                  <div className="flex flex-wrap gap-2" data-testid="billing-features">
                    <Badge
                      variant={entitlements.features.customDomain ? 'positive' : 'outline'}
                      data-testid="billing-feature-custom-domain"
                    >
                      {t(entitlements.features.customDomain ? 'customDomainIncluded' : 'customDomainNotIncluded')}
                    </Badge>
                    <Badge
                      variant={entitlements.features.dedicatedApp ? 'positive' : 'outline'}
                      data-testid="billing-feature-dedicated-app"
                    >
                      {t(entitlements.features.dedicatedApp ? 'dedicatedAppIncluded' : 'dedicatedAppNotIncluded')}
                    </Badge>
                  </div>
                )}
              </>
            ) : (
              <p className="m-0" data-testid="billing-no-plan">
                {t('noPlan', { used: entitlements.usage.staffSeats })}
              </p>
            )}
          </Section>
          <Section title={t('subscriptionTitle')}>
            {subscription ? (
              <>
                <Badge
                  variant={
                    subscription.state === 'active'
                      ? 'positive'
                      : subscription.state === 'read_only'
                        ? 'destructive'
                        : subscription.state === 'past_due'
                          ? 'outline'
                          : 'secondary'
                  }
                  data-testid="billing-state"
                  data-state={subscription.state}
                >
                  {t(`state_${subscription.state}` as 'state_active')}
                </Badge>
                <dl className="m-0 grid gap-3">
                  {subscription.state === 'trialing' && (
                    <div className="flex flex-wrap justify-between gap-2">
                      <dt>{t('trialEnds')}</dt>
                      <dd className="m-0 font-medium" data-testid="billing-trial-end">
                        {date(subscription.trialEndsAt)}
                      </dd>
                    </div>
                  )}
                  {subscription.pastDueSince && (
                    <div className="flex flex-wrap justify-between gap-2">
                      <dt>{t('overdueSince')}</dt>
                      <dd className="m-0 font-medium" data-testid="billing-past-due-since">
                        {date(subscription.pastDueSince)}
                      </dd>
                    </div>
                  )}
                  {subscription.readOnlyAt && (
                    <div className="flex flex-wrap justify-between gap-2">
                      <dt>{t(subscription.state === 'read_only' ? 'readOnlySince' : 'readOnlyFrom')}</dt>
                      <dd className="m-0 font-medium" data-testid="billing-read-only-at">
                        {date(subscription.readOnlyAt)}
                      </dd>
                    </div>
                  )}
                </dl>
                <p className="m-0 text-small">{t('howToPay')}</p>
              </>
            ) : (
              <p className="m-0" data-testid="billing-no-subscription">
                {t('noSubscription')}
              </p>
            )}
          </Section>
          <div className="rail:col-span-full">
            <Section title={t('invoicesTitle')}>
              {invoices && invoices.items.length === 0 && (
                <Empty data-testid="billing-invoices-empty">
                  <EmptyHeader>
                    <EmptyTitle>{t('invoicesEmpty')}</EmptyTitle>
                    <EmptyDescription>{t('invoicesEmptyHelp')}</EmptyDescription>
                  </EmptyHeader>
                </Empty>
              )}
              {invoices && invoices.items.length > 0 && (
                <ItemGroup className="gap-2" data-testid="billing-invoices">
                  {invoices.items.map((invoice) => (
                    <Item
                      variant="outline"
                      role="listitem"
                      key={invoice.id}
                      data-testid="billing-invoice-row"
                      data-id={invoice.id}
                    >
                      <ItemContent>
                        <ItemTitle className="wrap-anywhere">
                          <span data-testid="billing-invoice-number">{invoice.number}</span>
                          <span data-testid="billing-invoice-amount">
                            {formatMoney(invoice.amount, invoice.currency, LOCALE)}
                          </span>
                        </ItemTitle>
                        <ItemDescription className="wrap-anywhere">
                          {t('period', { start: date(invoice.periodStart), end: date(invoice.periodEnd) })}
                        </ItemDescription>
                        <div className="flex flex-wrap items-center gap-2">
                          <Badge
                            variant={invoice.status === 'paid' ? 'positive' : 'outline'}
                            data-testid="billing-invoice-status"
                            data-status={invoice.status}
                          >
                            {t(invoice.status === 'paid' ? 'invoicePaid' : 'invoiceOpen')}
                          </Badge>
                          <span className="text-small" data-testid="billing-invoice-due">
                            {invoice.status === 'paid' && invoice.paidAt
                              ? t('paidOn', { date: date(invoice.paidAt) })
                              : t('dueOn', { date: date(invoice.dueAt) })}
                          </span>
                        </div>
                      </ItemContent>
                    </Item>
                  ))}
                </ItemGroup>
              )}
              {invoices && invoices.total > 0 && (
                <Pagination aria-label={t('pages')} data-testid="billing-pagination">
                  <PaginationContent>
                    <PaginationItem>
                      <PaginationPrevious
                        data-testid="billing-page-previous"
                        disabled={busy || shownPage === 0}
                        onClick={() => load(shownPage - 1)}
                      >
                        {t('previous')}
                      </PaginationPrevious>
                    </PaginationItem>
                    <PaginationItem>
                      <PaginationCurrent data-testid="billing-page">
                        {t('page', { page: shownPage + 1, pages: Math.max(1, Math.ceil(invoices.total / PAGE)) })}
                      </PaginationCurrent>
                    </PaginationItem>
                    <PaginationItem>
                      <PaginationNext
                        data-testid="billing-page-next"
                        disabled={busy || (shownPage + 1) * PAGE >= invoices.total}
                        onClick={() => load(shownPage + 1)}
                      >
                        {t('next')}
                      </PaginationNext>
                    </PaginationItem>
                  </PaginationContent>
                </Pagination>
              )}
            </Section>
          </div>
        </>
      )}
    </div>
  );
}
