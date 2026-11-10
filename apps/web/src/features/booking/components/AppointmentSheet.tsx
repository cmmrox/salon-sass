'use client';
import { formatClock, formatDay } from '@salon/format';
import { useTranslations } from 'next-intl';
import type { ReactNode } from 'react';
import { Alert } from '@/components/ui/alert';
import { Button } from '@/components/ui/button';
import { Card } from '@/components/ui/card';
import { Item, ItemContent, ItemDescription, ItemGroup, ItemTitle } from '@/components/ui/item';
import { Sheet, SheetClose, SheetContent, SheetTitle } from '@/components/ui/sheet';
import { Skeleton } from '@/components/ui/skeleton';
import { Spinner } from '@/components/ui/spinner';
import { cn } from '@/lib/utils';
import {
  actorOf,
  changeOf,
  explanationOf,
  newestFirst,
  offeredActions,
  type SheetAction,
} from '../client/appointment-rules';
import { localClock, localDate, whenOf } from '../client/booking-time';
import type { BookingDetail } from '../types';
import { SourceBadge, StatusBadge } from './BookingBadges';
import { SHEET_CLASSES } from './sheet-classes';

/** What the appointment sheet says when the API refused a change or could not be reached (AC-9). */
export type AppointmentRefusal =
  'changed' | 'gone' | 'taken' | 'permission' | 'support' | 'salonReadOnly' | 'offline' | 'failed';

type RowKey = 'reference' | 'mobile' | 'service' | 'professional' | 'date' | 'time' | 'source';

const VARIANTS: Record<SheetAction, 'default' | 'outline' | 'destructive'> = {
  arrive: 'default',
  start: 'default',
  'no-show': 'outline',
  'undo-no-show': 'outline',
  cancel: 'destructive',
  release: 'destructive',
};

type Props = {
  open: boolean;
  /** The booking as the list showed it, until its detail is read: its name heads the sheet at once. */
  name: string;
  detail: BookingDetail | null;
  loading: boolean;
  unavailable: boolean;
  /** Whether the caller may act at all here (not a support session or a read-only salon). */
  canAct: boolean;
  today: string;
  now: number | null;
  refusal: AppointmentRefusal | null;
  /** The action being sent, while it is. */
  busy: SheetAction | null;
  notices?: ReactNode;
  onAction: (action: SheetAction) => void;
  onRetry: () => void;
  onClose: () => void;
  onClosed: () => void;
};

/**
 * The appointment sheet (the prototype's `openA`, AC-7): the customer, the status and source, the booking's facts,
 * exactly the actions the API allows now with the prototype's explanation under them, and the history newest first:
 * what changed, who changed it and when, and a cancellation's reason and note.
 */
export function AppointmentSheet(props: Props) {
  const t = useTranslations('bookings.appointment');
  const channels = useTranslations('bookings.channels');
  const reasons = useTranslations('bookings.cancel.reasons');
  const { detail } = props;
  const booking = detail?.booking ?? null;
  const actions = detail && props.canAct ? offeredActions(detail.allowedActions) : [];
  const explanation = detail ? explanationOf(detail, props.today) : null;
  const when = booking ? whenOf(booking) : null;
  const rows: [RowKey, string][] =
    booking && when
      ? [
          ['reference', booking.reference],
          ['mobile', booking.customer.mobile],
          [
            'service',
            booking.service.variantName
              ? `${booking.service.name} · ${booking.service.variantName}`
              : booking.service.name,
          ],
          ['professional', booking.member.name],
          ['date', formatDay(when.date)],
          ['time', `${formatClock(when.starts)} – ${formatClock(when.ends)}`],
          ['source', channels(booking.channel)],
        ]
      : [];
  return (
    <Sheet open={props.open} onOpenChange={(next) => !next && props.onClose()}>
      <SheetContent
        side="bottom"
        className={cn(SHEET_CLASSES)}
        data-testid="appointment-sheet"
        aria-describedby={undefined}
        onCloseAutoFocus={(event) => {
          event.preventDefault();
          props.onClosed();
        }}
      >
        <div className="flex items-start justify-between gap-3">
          <SheetTitle className="min-w-0 wrap-anywhere rail:text-workspace-title" data-testid="appointment-name">
            {booking?.customer.name ?? props.name}
          </SheetTitle>
          <SheetClose asChild>
            <Button variant="outline" size="sm" data-testid="appointment-close">
              {t('close')}
            </Button>
          </SheetClose>
        </div>
        {booking && (
          <div className="-mt-2 flex flex-wrap gap-1.5">
            <StatusBadge booking={booking} now={props.now} testId="appointment-status" />
            <SourceBadge booking={booking} testId="appointment-source" />
          </div>
        )}
        {props.notices}
        {props.refusal && (
          <Alert variant="notice" role="alert" data-testid="appointment-refusal" data-refusal={props.refusal}>
            {t(`refusals.${props.refusal}`)}
          </Alert>
        )}
        {props.loading && !detail ? (
          <div
            className="flex flex-col gap-3"
            role="status"
            aria-busy="true"
            aria-label={t('loading')}
            data-testid="appointment-loading"
          >
            <Skeleton className="h-64 w-full rounded-card" />
            <Skeleton className="h-24 w-full rounded-card" />
          </div>
        ) : props.unavailable && !detail ? (
          <Alert variant="caution" data-testid="appointment-unavailable">
            {t('unavailable')}
            <Button
              variant="outline"
              size="sm"
              className="w-fit"
              onClick={props.onRetry}
              data-testid="appointment-retry"
            >
              {t('retry')}
            </Button>
          </Alert>
        ) : detail && booking ? (
          <>
            <Card className="gap-0 px-5 py-2" data-testid="appointment-details">
              <dl className="flex flex-col">
                {rows.map(([key, value]) => (
                  <div
                    key={key}
                    className="flex flex-wrap justify-between gap-x-4 gap-y-0.5 border-b border-border py-2.5 text-small last:border-b-0"
                    data-testid="appointment-row"
                    data-row={key}
                  >
                    <dt className="text-muted-foreground">{t(`rows.${key}`)}</dt>
                    <dd className="m-0 font-medium wrap-anywhere">{value}</dd>
                  </div>
                ))}
              </dl>
            </Card>
            {actions.length > 0 && (
              <div className="flex flex-wrap gap-2" data-testid="appointment-actions">
                {actions.map((action) => (
                  <Button
                    key={action}
                    size="sm"
                    variant={VARIANTS[action]}
                    disabled={props.busy !== null}
                    aria-busy={props.busy === action || undefined}
                    onClick={() => props.onAction(action)}
                    data-testid={`appointment-${action}`}
                  >
                    {props.busy === action && <Spinner />}
                    {t(`actions.${action}`)}
                  </Button>
                ))}
              </div>
            )}
            {explanation && (
              <p
                className="text-small text-foreground"
                data-testid="appointment-explanation"
                data-explanation={explanation}
              >
                {t(`explanations.${explanation}`)}
              </p>
            )}
            <section className="flex flex-col gap-2" aria-labelledby="appointment-history-heading">
              <h2 id="appointment-history-heading" className="font-semibold">
                {t('history')}
              </h2>
              <Card className="gap-0 px-5 py-1">
                <ItemGroup data-testid="appointment-history">
                  {newestFirst(detail.history).map((entry, index) => {
                    const actor = actorOf(entry);
                    const who =
                      actor.kind === 'label' ? actor.label : actor.kind === 'system' ? t('system') : t('teamMember');
                    return (
                      <Item
                        key={`${entry.at}-${index}`}
                        variant="divided"
                        data-testid="appointment-history-row"
                        data-to={entry.to}
                      >
                        <ItemContent className="gap-0.5">
                          <ItemTitle className="font-medium">{t(`changes.${changeOf(entry)}`)}</ItemTitle>
                          {entry.reason && (
                            <ItemDescription
                              className="text-small text-muted-foreground"
                              data-testid="appointment-history-why"
                            >
                              {entry.note
                                ? t('why', { reason: reasons(entry.reason), note: entry.note })
                                : reasons(entry.reason)}
                            </ItemDescription>
                          )}
                          <ItemDescription
                            className="text-small text-muted-foreground"
                            data-testid="appointment-history-by"
                          >
                            {t('by', {
                              who,
                              when: t('when', {
                                day: formatDay(localDate(entry.at)),
                                time: formatClock(localClock(entry.at)),
                              }),
                            })}
                          </ItemDescription>
                        </ItemContent>
                      </Item>
                    );
                  })}
                </ItemGroup>
              </Card>
            </section>
          </>
        ) : null}
      </SheetContent>
    </Sheet>
  );
}
