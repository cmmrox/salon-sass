'use client';
import { formatInstant } from '@salon/format';
import { useTranslations } from 'next-intl';
import { Alert } from '@/components/ui/alert';
import { Badge } from '@/components/ui/badge';
import { Button } from '@/components/ui/button';
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
import { Sheet, SheetContent, SheetDescription, SheetTitle, SheetTrigger } from '@/components/ui/sheet';
import { Skeleton } from '@/components/ui/skeleton';
import { cn } from '@/lib/utils';
import type { TaxHistory } from '../types';
import { SHEET_CLASSES } from './sheet-classes';

/** The history being read, read, or not reachable. */
export type TaxHistoryState = { state: 'loading' } | { state: 'ready'; history: TaxHistory } | { state: 'unavailable' };

type Props = {
  open: boolean;
  onOpenChange: (open: boolean) => void;
  history: TaxHistoryState;
  /** The staff account looking, whose own versions say "You"; null for a support session. */
  viewer: string | null;
  timeZone: string;
  onPage: (page: number) => void;
};

/**
 * The tax settings' history (F05-S03 AC-3), as the booking rules' (F04-S04): History opens a Sheet (bottom sheet on
 * phones, right drawer from 900px) listing each version as an Item, newest first: its number, when it was saved and
 * by whom ("You" for the viewer's own, the author as the staff list names them otherwise, or "A team member"), and
 * the settings it changed; paged.
 */
export function TaxHistorySheet({ open, onOpenChange, history, viewer, timeZone, onPage }: Props) {
  const t = useTranslations('settings.tax');
  const ready = history.state === 'ready' ? history.history : null;
  const pages = ready ? Math.max(1, Math.ceil(ready.total / ready.size)) : 1;
  return (
    <Sheet open={open} onOpenChange={onOpenChange}>
      <SheetTrigger asChild>
        <Button variant="outline" className="w-full rail:w-fit" data-testid="tax-history">
          {t('history')}
        </Button>
      </SheetTrigger>
      <SheetContent side="bottom" data-testid="tax-history-sheet" className={cn(SHEET_CLASSES)}>
        <SheetTitle className="rail:text-workspace-title">{t('historyTitle')}</SheetTitle>
        <SheetDescription>{t('historyDescription')}</SheetDescription>
        {history.state === 'loading' && (
          <div
            className="flex flex-col gap-2"
            role="status"
            aria-busy="true"
            aria-label={t('historyLoading')}
            data-testid="tax-history-loading"
          >
            <Skeleton className="h-20 w-full rounded-card" />
            <Skeleton className="h-20 w-full rounded-card" />
          </div>
        )}
        {history.state === 'unavailable' && (
          <Alert variant="caution" data-testid="tax-history-unavailable">
            {t('historyFailed')}
          </Alert>
        )}
        {ready && ready.items.length === 0 && (
          <Empty data-testid="tax-history-empty">
            <EmptyHeader>
              <EmptyTitle>{t('historyEmpty')}</EmptyTitle>
              <EmptyDescription>{t('historyEmptyHelp')}</EmptyDescription>
            </EmptyHeader>
          </Empty>
        )}
        {ready && ready.items.length > 0 && (
          <ItemGroup className="gap-2" data-testid="tax-history-list">
            {ready.items.map((version) => (
              <Item
                key={version.versionId}
                variant="outline"
                role="listitem"
                data-testid="tax-history-row"
                data-version={version.version}
              >
                <ItemContent>
                  <ItemTitle>
                    {t('historyVersion', { version: version.version })}
                    {ready.page === 0 && version === ready.items[0] && (
                      <Badge variant="secondary" data-testid="tax-history-current">
                        {t('historyCurrent')}
                      </Badge>
                    )}
                  </ItemTitle>
                  <ItemDescription data-testid="tax-history-by">
                    {t('historyBy', {
                      time: formatInstant(version.createdAt, timeZone),
                      author:
                        version.createdBy === viewer
                          ? t('historyYou')
                          : (version.createdByLabel ?? t('historyTeamMember')),
                    })}
                  </ItemDescription>
                  <ItemDescription data-testid="tax-history-changed">
                    {t('historyChanged', {
                      fields: version.changedFields
                        .map((field) => t(`fieldNames.${field}` as 'fieldNames.vat.registered'))
                        .join(', '),
                    })}
                  </ItemDescription>
                </ItemContent>
              </Item>
            ))}
          </ItemGroup>
        )}
        {ready && pages > 1 && (
          <Pagination aria-label={t('pagination.label')} data-testid="tax-history-pagination">
            <PaginationContent>
              <PaginationItem>
                <PaginationPrevious
                  disabled={ready.page === 0}
                  onClick={() => onPage(ready.page - 1)}
                  data-testid="tax-history-newer"
                >
                  {t('pagination.previous')}
                </PaginationPrevious>
              </PaginationItem>
              <PaginationItem>
                <PaginationCurrent data-testid="tax-history-page">
                  {t('pagination.page', { page: ready.page + 1, pages })}
                </PaginationCurrent>
              </PaginationItem>
              <PaginationItem>
                <PaginationNext
                  disabled={ready.page + 1 >= pages}
                  onClick={() => onPage(ready.page + 1)}
                  data-testid="tax-history-older"
                >
                  {t('pagination.next')}
                </PaginationNext>
              </PaginationItem>
            </PaginationContent>
          </Pagination>
        )}
      </SheetContent>
    </Sheet>
  );
}
