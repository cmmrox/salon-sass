'use client';
import { formatClock, formatDay } from '@salon/format';
import { CalendarIcon } from 'lucide-react';
import { useTranslations } from 'next-intl';
import { useState } from 'react';
import { Alert, AlertDescription, AlertTitle } from '@/components/ui/alert';
import { Button } from '@/components/ui/button';
import { Calendar } from '@/components/ui/calendar';
import { Field, FieldDescription, FieldError, FieldLabel } from '@/components/ui/field';
import { Input } from '@/components/ui/input';
import { Item, ItemContent, ItemDescription, ItemGroup, ItemTitle } from '@/components/ui/item';
import { Popover, PopoverContent, PopoverTrigger } from '@/components/ui/popover';
import { Sheet, SheetContent, SheetDescription, SheetTitle, SheetTrigger } from '@/components/ui/sheet';
import { Skeleton } from '@/components/ui/skeleton';
import { Spinner } from '@/components/ui/spinner';
import { dayButtonLabel } from '@/lib/dates/calendar-labels';
import { cn } from '@/lib/utils';
import { lastClosableDate } from '../client/hours-form';
import type { AffectedBooking } from '../types';

/** The bookings on the chosen date: not asked yet, being asked, known, or not reachable. */
export type ImpactState =
  { state: 'idle' } | { state: 'loading' } | { state: 'ready'; bookings: AffectedBooking[] } | { state: 'unavailable' };

type Props = {
  open: boolean;
  onOpenChange: (open: boolean) => void;
  /** The salon's today, `YYYY-MM-DD`. */
  today: string;
  disabled: boolean;
  busy: boolean;
  impact: ImpactState;
  /** The field the API refused, beside it. */
  refused: 'date' | 'reason' | 'taken' | null;
  onDate: (date: string) => void;
  onClose: (date: string, reason: string) => void;
};

/** A `YYYY-MM-DD` date as the calendar's local Date, and back: only the date's parts are used. */
const asDate = (value: string) => {
  const [year, month, day] = value.split('-').map(Number) as [number, number, number];
  return new Date(year, month - 1, day);
};
const asText = (date: Date) =>
  `${date.getFullYear()}-${String(date.getMonth() + 1).padStart(2, '0')}-${String(date.getDate()).padStart(2, '0')}`;

/**
 * The Add a closure sheet (F04-S03, the prototype's `cloPaint`): a bottom sheet on phones and a right drawer
 * from 900px. The date is picked from a Popover with a Calendar, from today to a year ahead, the other days
 * disabled; the reason is optional and only the team sees it. Once a date is picked the sheet lists the
 * bookings on it, or says there are none, before Close the salon this day. Focus stays inside the sheet and
 * returns to + Add closure when it closes.
 */
export function AddClosureSheet({
  open,
  onOpenChange,
  today,
  disabled,
  busy,
  impact,
  refused,
  onDate,
  onClose,
}: Props) {
  const t = useTranslations('settings.closures');
  const [date, setDate] = useState<string | null>(null);
  const [reason, setReason] = useState('');
  const [picking, setPicking] = useState(false);
  const [wasOpen, setWasOpen] = useState(open);
  const last = lastClosableDate(today);

  // The sheet starts empty each time it opens or closes, however `open` changed: through the sheet, or by
  // the caller after a closure is added (which bypasses `change`).
  if (open !== wasOpen) {
    setWasOpen(open);
    setDate(null);
    setReason('');
    setPicking(false);
  }

  function change(next: boolean) {
    if (busy && !next) return;
    onOpenChange(next);
  }

  return (
    <Sheet open={open} onOpenChange={change}>
      <SheetTrigger asChild>
        <Button size="sm" disabled={disabled} data-testid="closure-add">
          {t('add')}
        </Button>
      </SheetTrigger>
      <SheetContent
        side="bottom"
        data-testid="closure-sheet"
        onEscapeKeyDown={(event) => {
          if (busy) event.preventDefault();
        }}
        onInteractOutside={(event) => {
          if (busy) event.preventDefault();
        }}
        className={cn(
          'mx-auto max-h-(--size-sheet-max-height) w-full max-w-(--size-customer) gap-4 overflow-y-auto rounded-(--radius-sheet) p-6 pb-[calc(var(--spacing)*6+env(safe-area-inset-bottom,0px))] outline-none',
          'before:mx-auto before:mb-2 before:block before:h-1 before:w-(--size-sheet-handle) before:rounded-action before:bg-border before:content-[""]',
          'rail:top-0 rail:left-auto rail:m-0 rail:h-full rail:max-h-none rail:w-(--size-drawer) rail:max-w-full rail:rounded-none rail:px-8 rail:py-6 rail:before:hidden',
        )}
      >
        <SheetTitle className="rail:text-workspace-title">{t('sheetTitle')}</SheetTitle>
        <SheetDescription>{t('sheetHelp')}</SheetDescription>
        <Field data-invalid={refused === 'date' || refused === 'taken' || undefined}>
          <FieldLabel htmlFor="closure-date">{t('day')}</FieldLabel>
          <Popover open={picking} onOpenChange={setPicking}>
            <PopoverTrigger asChild>
              <Button
                id="closure-date"
                variant="outline"
                className="w-full justify-between font-normal"
                aria-describedby={refused === 'date' || refused === 'taken' ? 'closure-date-error' : undefined}
                data-testid="closure-date"
              >
                {date ? formatDay(date) : t('chooseDay')}
                <CalendarIcon aria-hidden="true" />
              </Button>
            </PopoverTrigger>
            <PopoverContent
              className="w-auto p-0"
              align="start"
              aria-label={t('calendarLabel')}
              data-testid="closure-calendar"
            >
              <Calendar
                labels={{ labelDayButton: dayButtonLabel }}
                mode="single"
                selected={date ? asDate(date) : undefined}
                defaultMonth={date ? asDate(date) : asDate(today)}
                startMonth={asDate(today)}
                endMonth={asDate(last)}
                disabled={[{ before: asDate(today) }, { after: asDate(last) }]}
                onSelect={(picked) => {
                  if (!picked) return;
                  const chosen = asText(picked);
                  setDate(chosen);
                  setPicking(false);
                  onDate(chosen);
                }}
              />
            </PopoverContent>
          </Popover>
          {(refused === 'date' || refused === 'taken') && (
            <FieldError id="closure-date-error" data-testid="closure-date-error">
              {refused === 'taken' ? t('taken') : t('dateRule')}
            </FieldError>
          )}
        </Field>
        <Field data-invalid={refused === 'reason' || undefined}>
          <FieldLabel htmlFor="closure-reason">
            {t('reason')} <span className="font-normal">{t('reasonHint')}</span>
          </FieldLabel>
          <Input
            id="closure-reason"
            value={reason}
            maxLength={60}
            placeholder={t('reasonPlaceholder')}
            onChange={(event) => setReason(event.target.value)}
            aria-invalid={refused === 'reason' || undefined}
            aria-describedby={refused === 'reason' ? 'closure-reason-error' : 'closure-reason-help'}
            data-testid="closure-reason"
          />
          {refused === 'reason' ? (
            <FieldError id="closure-reason-error" data-testid="closure-reason-error">
              {t('reasonRule')}
            </FieldError>
          ) : (
            <FieldDescription id="closure-reason-help">{t('reasonHelp')}</FieldDescription>
          )}
        </Field>
        <div aria-live="polite" data-testid="closure-impact">
          {impact.state === 'loading' && <Skeleton className="h-12 w-full rounded-card" />}
          {impact.state === 'unavailable' && (
            <Alert variant="caution">
              <AlertDescription>{t('impactUnavailable')}</AlertDescription>
            </Alert>
          )}
          {impact.state === 'ready' &&
            (impact.bookings.length === 0 ? (
              <Alert variant="positive" data-testid="closure-no-bookings">
                <AlertDescription>{t('noBookings')}</AlertDescription>
              </Alert>
            ) : (
              <Alert variant="caution" data-testid="closure-bookings">
                <AlertTitle>{t('bookingsTitle', { count: impact.bookings.length })}</AlertTitle>
                <AlertDescription>
                  <p className="m-0">{t('bookingsBody')}</p>
                  <ItemGroup className="mt-2 gap-1">
                    {impact.bookings.map((booking) => (
                      <Item
                        key={`${booking.time}-${booking.customerName}`}
                        className="justify-between gap-2 rounded-none p-0"
                        data-testid="closure-booking"
                      >
                        <ItemContent>
                          <ItemDescription className="text-body text-foreground">
                            {t('bookingAt', { time: formatClock(booking.time), name: booking.customerName })}
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
            ))}
        </div>
        <Button
          className="w-full"
          disabled={disabled || busy || date === null}
          onClick={() => date && onClose(date, reason)}
          data-testid="closure-confirm"
        >
          {busy && <Spinner />}
          {t('confirm')}
        </Button>
      </SheetContent>
    </Sheet>
  );
}
