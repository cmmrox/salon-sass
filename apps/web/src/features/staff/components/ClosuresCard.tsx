'use client';
import { formatDay } from '@salon/format';
import { useTranslations } from 'next-intl';
import type { ReactNode } from 'react';
import { Button } from '@/components/ui/button';
import { Card, CardDescription, CardTitle } from '@/components/ui/card';
import { Empty, EmptyHeader, EmptyTitle } from '@/components/ui/empty';
import { Item, ItemActions, ItemContent, ItemDescription, ItemGroup, ItemTitle } from '@/components/ui/item';
import type { Closure } from '../types';

type Props = {
  closures: Closure[];
  disabled: boolean;
  reopening: string | null;
  addClosure: ReactNode;
  onReopen: (closure: Closure) => void;
};

/**
 * The Special closures card (F04-S03): + Add closure (the sheet), each coming closure as an Item with its
 * date, the team's reason and the bookings it affects, and Reopen; or "No closures planned".
 */
export function ClosuresCard({ closures, disabled, reopening, addClosure, onReopen }: Props) {
  const t = useTranslations('settings.closures');
  return (
    <Card className="gap-3" data-testid="closures-card">
      <div className="flex flex-wrap items-center justify-between gap-2">
        <CardTitle className="text-large">{t('title')}</CardTitle>
        {addClosure}
      </div>
      <CardDescription>{t('help')}</CardDescription>
      {closures.length === 0 ? (
        <Empty className="p-4" data-testid="closures-empty">
          <EmptyHeader>
            <EmptyTitle className="text-small font-normal">{t('none')}</EmptyTitle>
          </EmptyHeader>
        </Empty>
      ) : (
        <ItemGroup className="gap-2" data-testid="closures-list">
          {closures.map((closure) => (
            <Item variant="outline" role="listitem" key={closure.id} data-testid="closure-row" data-date={closure.date}>
              <ItemContent>
                <ItemTitle>{formatDay(closure.date)}</ItemTitle>
                <ItemDescription className="text-foreground">
                  {[
                    closure.reason ?? t('noReason'),
                    closure.affectedBookings > 0 ? t('toResolve', { count: closure.affectedBookings }) : null,
                  ]
                    .filter(Boolean)
                    .join(' · ')}
                </ItemDescription>
              </ItemContent>
              <ItemActions>
                <Button
                  variant="outline"
                  size="sm"
                  disabled={disabled || reopening !== null}
                  onClick={() => onReopen(closure)}
                  aria-label={t('reopenDay', { date: formatDay(closure.date) })}
                  data-testid="closure-reopen"
                >
                  {t('reopen')}
                </Button>
              </ItemActions>
            </Item>
          ))}
        </ItemGroup>
      )}
    </Card>
  );
}
