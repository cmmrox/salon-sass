'use client';
import { useTranslations } from 'next-intl';
import { Button } from '@/components/ui/button';
import { Empty, EmptyContent, EmptyDescription, EmptyHeader, EmptyTitle } from '@/components/ui/empty';
import {
  Pagination,
  PaginationContent,
  PaginationCurrent,
  PaginationItem,
  PaginationNext,
  PaginationPrevious,
} from '@/components/ui/pagination';
import { filtered, PAGE_SIZE, type BookingPage, type BookingsQuery } from '@/features/booking';

/**
 * Nothing on the day for the tools chosen (the prototype's `f7Empty`, AC-3): "No bookings match." with **Clear
 * filters** when anything narrows the list, and **+ New appointment** for the desk when nothing does.
 */
export function BookingsEmpty({
  query,
  canBook,
  onClear,
  onNew,
}: {
  query: BookingsQuery;
  canBook: boolean;
  onClear: () => void;
  onNew: () => void;
}) {
  const t = useTranslations('bookings');
  const narrowed = filtered(query);
  return (
    <Empty surface="card" data-testid="bookings-empty">
      <EmptyHeader>
        <EmptyTitle>{t('emptyTitle')}</EmptyTitle>
        <EmptyDescription>{t('emptyHint')}</EmptyDescription>
      </EmptyHeader>
      {(narrowed || canBook) && (
        <EmptyContent>
          {narrowed ? (
            <Button variant="outline" size="sm" onClick={onClear} data-testid="bookings-clear">
              {t('clear')}
            </Button>
          ) : (
            <Button size="sm" onClick={onNew} data-testid="bookings-empty-new">
              {t('new')}
            </Button>
          )}
        </EmptyContent>
      )}
    </Empty>
  );
}

/** More than a page of the day's bookings (AC-3): Previous, the page, Next; nothing for one page. */
export function BookingsPager({ page, onPage }: { page: BookingPage; onPage: (page: number) => void }) {
  const t = useTranslations('bookings.pager');
  const pages = Math.max(1, Math.ceil(page.total / PAGE_SIZE));
  if (pages <= 1) return null;
  const shown = page.page + 1;
  return (
    <Pagination aria-label={t('label')} data-testid="bookings-pager">
      <PaginationContent>
        <PaginationItem>
          <PaginationPrevious disabled={shown <= 1} onClick={() => onPage(shown - 1)} data-testid="bookings-previous">
            {t('previous')}
          </PaginationPrevious>
        </PaginationItem>
        <PaginationItem>
          <PaginationCurrent data-testid="bookings-page">{t('page', { page: shown, pages })}</PaginationCurrent>
        </PaginationItem>
        <PaginationItem>
          <PaginationNext disabled={shown >= pages} onClick={() => onPage(shown + 1)} data-testid="bookings-next">
            {t('next')}
          </PaginationNext>
        </PaginationItem>
      </PaginationContent>
    </Pagination>
  );
}
