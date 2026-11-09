'use client';
import { formatClock, formatDay } from '@salon/format';
import { useTranslations } from 'next-intl';
import { Alert, AlertDescription } from '@/components/ui/alert';
import { Item, ItemContent, ItemDescription, ItemGroup, ItemTitle } from '@/components/ui/item';
import { Skeleton } from '@/components/ui/skeleton';
import type { ImpactState } from '../client/time-off-form';

/**
 * What the period a form holds would affect, before it is saved (F06-S03 AC-8, the prototype's `conflictNote`): saying so
 * does not stop the save, and nothing about a booking changes. It announces itself as it changes.
 */
export function PeriodImpactNote({ impact }: { impact: ImpactState }) {
  const t = useTranslations('team.schedule');
  return (
    <div aria-live="polite" data-testid="period-impact">
      {impact.state === 'loading' && <Skeleton className="h-12 w-full rounded-card" />}
      {impact.state === 'unavailable' && (
        <Alert variant="caution" data-testid="period-unavailable">
          <AlertDescription>{t('period.unavailable')}</AlertDescription>
        </Alert>
      )}
      {impact.state === 'ready' && impact.bookings.length > 0 && (
        <Alert variant="caution" data-testid="period-affected">
          <AlertDescription>
            <p className="m-0" data-testid="period-affected-text">
              {t.rich('period.affected', {
                count: impact.bookings.length,
                b: (chunks) => <b className="font-semibold">{chunks}</b>,
              })}
            </p>
            <ItemGroup className="mt-2 gap-1">
              {impact.bookings.map((booking) => (
                <Item
                  key={`${booking.date}-${booking.time}-${booking.customerName}`}
                  className="justify-between gap-2 rounded-none p-0"
                  data-testid="period-affected-booking"
                >
                  <ItemContent>
                    <ItemDescription className="text-body wrap-anywhere text-foreground">
                      {t('affectedAt', {
                        date: formatDay(booking.date),
                        time: formatClock(booking.time),
                        customer: booking.customerName,
                      })}
                    </ItemDescription>
                  </ItemContent>
                  <ItemContent className="flex-none">
                    <ItemTitle className="wrap-anywhere">{booking.serviceName}</ItemTitle>
                  </ItemContent>
                </Item>
              ))}
            </ItemGroup>
          </AlertDescription>
        </Alert>
      )}
    </div>
  );
}
