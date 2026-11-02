'use client';
import { formatClock, formatDay } from '@salon/format';
import { useTranslations } from 'next-intl';
import { Alert, AlertDescription, AlertTitle } from '@/components/ui/alert';
import { Item, ItemContent, ItemDescription, ItemGroup, ItemTitle } from '@/components/ui/item';
import type { TeamAffectedBooking } from '../types';

/** The bookings a change leaves outside the person's working time, which are kept (F06-S02 AC-11). */
export function AffectedBookings({
  bookings,
  onDate,
}: {
  bookings: TeamAffectedBooking[];
  /** Whether a date change affected them, rather than a saved week. */
  onDate: boolean;
}) {
  const t = useTranslations('team.schedule');
  if (bookings.length === 0) return null;
  return (
    <Alert variant="caution" data-testid="schedule-affected">
      <AlertTitle>{t(onDate ? 'affectedOnDate' : 'affected', { count: bookings.length })}</AlertTitle>
      <AlertDescription>
        <ItemGroup className="mt-2 gap-1">
          {bookings.map((booking) => (
            <Item
              key={`${booking.date}-${booking.time}-${booking.customerName}`}
              className="justify-between gap-2 rounded-none p-0"
              data-testid="schedule-affected-booking"
            >
              <ItemContent>
                <ItemDescription className="text-body text-foreground">
                  {t('affectedAt', {
                    date: formatDay(booking.date),
                    time: formatClock(booking.time),
                    customer: booking.customerName,
                  })}
                </ItemDescription>
              </ItemContent>
              <ItemContent className="flex-none">
                <ItemTitle>{booking.serviceName}</ItemTitle>
              </ItemContent>
            </Item>
          ))}
        </ItemGroup>
      </AlertDescription>
    </Alert>
  );
}
