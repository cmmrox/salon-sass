'use client';
import { formatClock, formatDay } from '@salon/format';
import { useTranslations } from 'next-intl';
import type { MouseEvent } from 'react';
import { Button } from '@/components/ui/button';
import { Card } from '@/components/ui/card';
import { Item, ItemContent, ItemDescription, ItemGroup, ItemTitle } from '@/components/ui/item';
import { Table, TableBody, TableCell, TableHead, TableHeader, TableRow } from '@/components/ui/table';
import { useIsMobile } from '@/hooks/use-mobile';
import { cn } from '@/lib/utils';
import { whenOf } from '../client/booking-time';
import type { Booking } from '../types';
import { SourceBadge, StatusBadge } from './BookingBadges';

/** The prototype's table (`.tbl`): small muted headings on white, roomier cells, rows ruled in the line colour. */
const HEAD = 'h-12 px-4 text-small font-medium text-muted-foreground';
const CELL = 'px-4 py-3 text-body align-top whitespace-normal wrap-anywhere';

type Props = {
  day: string;
  bookings: Booking[];
  /** The API's now, for a hold's countdown. */
  now: number | null;
  onOpen: (booking: Booking) => void;
};

/**
 * One day's bookings and holds in time order (AC-2): from 900px a Table of When, Customer, Service, Professional,
 * Status and Reference (the prototype's `aApptsW`), and below it the prototype's rows (`aAppts`) with the same facts.
 * A row opens its booking: anywhere on it with a pointer, and its customer's name by keyboard.
 */
export function BookingsList({ day, bookings, now, onOpen }: Props) {
  const t = useTranslations('bookings');
  const narrow = useIsMobile();
  const service = (booking: Booking) =>
    booking.service.variantName
      ? t('serviceVariant', { service: booking.service.name, variant: booking.service.variantName })
      : booking.service.name;
  const time = (booking: Booking) => {
    const when = whenOf(booking);
    return t('time', { starts: formatClock(when.starts), ends: formatClock(when.ends) });
  };
  // A click on the row opens it, but the name's own button already did when it was the target.
  const rowClick = (booking: Booking) => (event: MouseEvent<HTMLElement>) => {
    if (!(event.target as HTMLElement).closest('button')) onOpen(booking);
  };
  const open = (booking: Booking) => (
    <Button
      variant="link"
      size="link"
      className="h-auto min-h-0 p-0 text-left text-body font-medium text-foreground"
      aria-label={t('open', { name: booking.customer.name, time: time(booking) })}
      onClick={() => onOpen(booking)}
      data-testid="booking-open"
      data-id={booking.id}
    >
      {booking.customer.name}
    </Button>
  );

  if (narrow)
    return (
      <ItemGroup className="gap-2" aria-label={t('list', { day: formatDay(day) })} data-testid="bookings-items">
        {bookings.map((booking) => (
          <Item
            key={booking.id}
            variant="outline"
            className="cursor-pointer items-start bg-card"
            onClick={rowClick(booking)}
            data-testid="booking-item"
            data-id={booking.id}
            data-state={booking.state}
          >
            <span className="w-14 shrink-0 font-semibold" data-testid="booking-time">
              {formatClock(whenOf(booking).starts)}
            </span>
            <ItemContent className="min-w-0 gap-1">
              <ItemTitle data-testid="booking-service">{service(booking)}</ItemTitle>
              <div>{open(booking)}</div>
              <ItemDescription className="text-small text-muted-foreground">
                {t.rich('itemWhen', {
                  when: time(booking),
                  mobile: booking.customer.mobile,
                  m: (chunks) => <span data-testid="booking-mobile">{chunks}</span>,
                })}
              </ItemDescription>
              <ItemDescription className="text-small text-muted-foreground">
                {t.rich('itemFacts', {
                  professional: booking.member.name,
                  reference: booking.reference,
                  p: (chunks) => <span data-testid="booking-professional">{chunks}</span>,
                  r: (chunks) => <span data-testid="booking-reference">{chunks}</span>,
                })}
              </ItemDescription>
              <div className="flex flex-wrap gap-1.5">
                <StatusBadge booking={booking} now={now} testId="booking-status" />
                <SourceBadge booking={booking} testId="booking-source" />
              </div>
            </ItemContent>
          </Item>
        ))}
      </ItemGroup>
    );

  return (
    <Card className="p-0" data-testid="bookings-table">
      <Table aria-label={t('table.label', { day: formatDay(day) })}>
        <TableHeader>
          <TableRow className="border-border hover:bg-transparent">
            <TableHead className={HEAD}>{t('table.when')}</TableHead>
            <TableHead className={HEAD}>{t('table.customer')}</TableHead>
            <TableHead className={HEAD}>{t('table.service')}</TableHead>
            <TableHead className={HEAD}>{t('table.professional')}</TableHead>
            <TableHead className={HEAD}>{t('table.status')}</TableHead>
            <TableHead className={HEAD}>{t('table.reference')}</TableHead>
          </TableRow>
        </TableHeader>
        <TableBody>
          {bookings.map((booking) => (
            <TableRow
              key={booking.id}
              className="cursor-pointer border-border"
              onClick={rowClick(booking)}
              data-testid="booking-row"
              data-id={booking.id}
              data-state={booking.state}
            >
              <TableCell className={CELL}>
                <span className="block font-medium">{formatDay(whenOf(booking).date)}</span>
                <span className="block text-small text-muted-foreground" data-testid="booking-when">
                  {time(booking)}
                </span>
              </TableCell>
              <TableCell className={CELL}>
                {open(booking)}
                <span className="block text-small text-muted-foreground" data-testid="booking-mobile">
                  {booking.customer.mobile}
                </span>
              </TableCell>
              <TableCell className={CELL} data-testid="booking-service">
                {service(booking)}
              </TableCell>
              <TableCell className={CELL} data-testid="booking-professional">
                {booking.member.name}
              </TableCell>
              <TableCell className={cn(CELL, 'wrap-normal')}>
                <div className="flex flex-wrap gap-1.5">
                  <StatusBadge booking={booking} now={now} testId="booking-status" />
                  <SourceBadge booking={booking} testId="booking-source" />
                </div>
              </TableCell>
              <TableCell className={cn(CELL, 'text-small')} data-testid="booking-reference">
                {booking.reference}
              </TableCell>
            </TableRow>
          ))}
        </TableBody>
      </Table>
    </Card>
  );
}
