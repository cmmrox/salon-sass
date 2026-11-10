'use client';
import { formatClock, formatDay } from '@salon/format';
import Link from 'next/link';
import { useTranslations } from 'next-intl';
import type { Ref } from 'react';
import { Alert } from '@/components/ui/alert';
import { Badge } from '@/components/ui/badge';
import { Button } from '@/components/ui/button';
import { Card } from '@/components/ui/card';
import { Skeleton } from '@/components/ui/skeleton';
import { Spinner } from '@/components/ui/spinner';
import { Toggle } from '@/components/ui/toggle';
import { firstName } from '../client/appointment-form';
import { countdownText, secondsLeft, whenOf } from '../client/booking-time';
import type { Availability, Booking } from '../types';

/** Why a day has no free time, as the screen words it: "not working" names the professional chosen, if any (AC-5). */
type NoTimeReason = 'closed' | 'nobody_performs' | 'not_working' | 'nobody_working' | 'fully_booked';

/** The free times being looked for, found, or not there. */
export type TimesState =
  | { state: 'idle' }
  | { state: 'searching' }
  | { state: 'times'; day: string; noPreference: boolean; times: Availability['times'] }
  | { state: 'none'; day: string; reason: NoTimeReason; professional: string | null };

/** What New appointment says when the API refused a search, a hold or a confirmation (AC-6, AC-9). */
export type NewAppointmentRefusal =
  | 'taken'
  | 'ranOut'
  | 'unavailable'
  | 'rulesNotSet'
  | 'permission'
  | 'support'
  | 'salonReadOnly'
  | 'offline'
  | 'gone'
  | 'failed';

type Props = {
  times: TimesState;
  hold: Booking | null;
  /** The source chosen, for the hold's facts. */
  source: string;
  now: number | null;
  refusal: NewAppointmentRefusal | null;
  /** Whether the refusal for unset rules links to them (`settings.manage`). */
  canSetRules: boolean;
  busy: boolean;
  holdTitle: Ref<HTMLHeadingElement>;
  onHold: (time: Availability['times'][number]) => void;
  onConfirm: () => void;
  onAnother: () => void;
};

/**
 * New appointment's lower half (the prototype's `f7Find`, `f7Hold` and `f7HoldPaint`): the refusal, if any; then the
 * day's free times as chips ("10:30 · Nimal") or why there are none; or, once a time is chosen, the hold with its
 * countdown from the API's expiry, its facts, **Confirm booking** and **Choose another time**.
 */
export function FreeTimes(props: Props) {
  const t = useTranslations('bookings.newAppointment');
  const { times, hold } = props;
  return (
    <div className="flex flex-col gap-3" data-testid="new-appointment-results">
      {props.refusal && (
        <Alert
          variant={props.refusal === 'taken' || props.refusal === 'ranOut' ? 'destructive' : 'notice'}
          role="alert"
          data-testid="new-appointment-refusal"
          data-refusal={props.refusal}
        >
          {t(`refusals.${props.refusal}`)}
          {props.refusal === 'rulesNotSet' && props.canSetRules && (
            <>
              {' '}
              <Link
                href="/workspace/settings/rules"
                className="text-foreground"
                data-testid="new-appointment-rules-link"
              >
                {t('refusals.rulesLink')}
              </Link>
            </>
          )}
        </Alert>
      )}
      {hold ? (
        <HoldCard
          hold={hold}
          source={props.source}
          now={props.now}
          busy={props.busy}
          titleRef={props.holdTitle}
          onConfirm={props.onConfirm}
          onAnother={props.onAnother}
        />
      ) : times.state === 'searching' ? (
        <div
          className="flex flex-col gap-2"
          role="status"
          aria-busy="true"
          aria-label={t('finding')}
          data-testid="new-appointment-searching"
        >
          <Skeleton className="h-5 w-3/4" />
          <Skeleton className="h-11 w-full rounded-action" />
        </div>
      ) : times.state === 'none' ? (
        <Alert
          variant={times.reason === 'fully_booked' ? 'destructive' : 'notice'}
          data-testid="new-appointment-reason"
          data-reason={times.reason}
        >
          {t(`reasons.${times.reason}`, { day: formatDay(times.day), name: times.professional ?? '' })}
        </Alert>
      ) : times.state === 'times' ? (
        <section className="flex flex-col gap-2" aria-labelledby="new-appointment-times-intro">
          <p
            id="new-appointment-times-intro"
            className="text-small text-foreground"
            data-testid="new-appointment-times-intro"
          >
            {t('timesIntro', { day: formatDay(times.day) })}
          </p>
          {times.noPreference && (
            <p className="text-small text-foreground" data-testid="new-appointment-no-preference">
              {t('noPreferenceLine')}
            </p>
          )}
          <div
            className="flex flex-wrap gap-2"
            role="group"
            aria-label={t('times')}
            data-testid="new-appointment-times"
          >
            {times.times.map((time) => (
              <Toggle
                key={time.startsAt}
                variant="chip"
                pressed={false}
                disabled={props.busy}
                onClick={() => props.onHold(time)}
                data-testid="new-appointment-time"
                data-time={time.time}
                data-member={time.member.id}
              >
                {t('chip', { time: formatClock(time.time), name: firstName(time.member.name) })}
              </Toggle>
            ))}
          </div>
        </section>
      ) : null}
    </div>
  );
}

/** The held time (`f7HoldPaint`): its countdown is a timer that is not read out each second (AC-11). */
function HoldCard({
  hold,
  source,
  now,
  busy,
  titleRef,
  onConfirm,
  onAnother,
}: {
  hold: Booking;
  source: string;
  now: number | null;
  busy: boolean;
  titleRef: Ref<HTMLHeadingElement>;
  onConfirm: () => void;
  onAnother: () => void;
}) {
  const t = useTranslations('bookings.newAppointment');
  const channel = useTranslations('bookings.channels');
  const when = whenOf(hold);
  const left = now === null || !hold.holdExpiresAt ? null : secondsLeft(hold.holdExpiresAt, now);
  const rows: ['customer' | 'service' | 'professional' | 'when' | 'source', string, string][] = [
    ['customer', t('customerValue', { name: hold.customer.name, mobile: hold.customer.mobile }), 'hold-customer'],
    [
      'service',
      hold.service.variantName ? `${hold.service.name} · ${hold.service.variantName}` : hold.service.name,
      'hold-service',
    ],
    ['professional', hold.member.name, 'hold-professional'],
    [
      'when',
      t('whenValue', { day: formatDay(when.date), starts: formatClock(when.starts), ends: formatClock(when.ends) }),
      'hold-when',
    ],
    ['source', channel(source as Booking['channel']), 'hold-source'],
  ];
  return (
    <>
      <Card className="gap-2 p-5" data-testid="new-appointment-hold" data-id={hold.id}>
        <div className="flex flex-wrap items-center justify-between gap-2">
          <h3
            ref={titleRef}
            tabIndex={-1}
            className="m-0 font-semibold outline-none"
            data-testid="new-appointment-holding"
          >
            {t('holding')}
          </h3>
          {left !== null && (
            <Badge variant="caution" role="timer" aria-live="off" data-testid="new-appointment-countdown">
              {t('left', { left: countdownText(left) })}
            </Badge>
          )}
        </div>
        <dl className="flex flex-col">
          {rows.map(([key, value, testId]) => (
            <div
              key={key}
              className="flex flex-wrap justify-between gap-x-4 gap-y-0.5 border-b border-border py-2 text-small last:border-b-0"
            >
              <dt className="text-muted-foreground">{t(`rows.${key}`)}</dt>
              <dd className="m-0 font-medium wrap-anywhere" data-testid={testId}>
                {value}
              </dd>
            </div>
          ))}
        </dl>
        <p className="text-small text-muted-foreground" data-testid="new-appointment-hold-note">
          {t('holdNote')}
        </p>
      </Card>
      <Button onClick={onConfirm} disabled={busy} data-testid="new-appointment-confirm">
        {busy && <Spinner />}
        {t('confirm')}
      </Button>
      <Button variant="outline" onClick={onAnother} disabled={busy} data-testid="new-appointment-another">
        {t('another')}
      </Button>
    </>
  );
}
