'use client';
import { formatClock, formatDay } from '@salon/format';
import { useRouter } from 'next/navigation';
import { useTranslations } from 'next-intl';
import { useCallback, useRef, useState } from 'react';
import { toast } from 'sonner';
import { Alert, AlertDescription, AlertTitle } from '@/components/ui/alert';
import { Button } from '@/components/ui/button';
import { Skeleton } from '@/components/ui/skeleton';
import { Toaster } from '@/components/ui/sonner';
import {
  AppointmentSheet,
  BookingsList,
  BookingsTools,
  CancelBookingDialog,
  FreeTimes,
  NewAppointmentSheet,
  firstName,
  whenOf,
  type Booking,
  type SheetAction,
} from '@/features/booking';
import { LeaveDialog, useLeaveGuard } from '@/features/branding';
import { leaveEndedSession, type Kind } from '@/features/identity';
import { BookingsEmpty, BookingsPager } from './BookingsParts';
import { useApiClock } from './use-api-clock';
import { useAppointment } from './use-appointment';
import { useBookings } from './use-bookings';
import { useNewAppointment } from './use-new-appointment';

type Props = {
  kind: Kind;
  timeZone: string;
  /** The caller's data scope: `salon` sees everyone's bookings and the professional filter, `own` their own (AC-10). */
  scope: string;
  /** Whether this person may book, confirm, release and cancel (`booking.manage`, in a salon that is not read-only). */
  canBook: boolean;
  /** Whether the actions the API allows may be offered at all (not a support session or a read-only salon). */
  canAct: boolean;
  /** Whether a refusal for unset booking rules links to them (`settings.manage`). */
  canSetRules: boolean;
  readOnly: 'support' | 'salon' | null;
  /** The region a mobile number is checked against (decision 084). */
  phoneRegion: string;
};

/**
 * The Bookings page (F07-S03, the prototype's `aApptsW` and `aAppts`): one day's bookings and holds with the tools that
 * narrow them, New appointment for the desk, and the appointment sheet with its allowed changes and history. Loading,
 * empty, refused and unavailable states; the day and filters are kept in the address.
 */
export function BookingsScreen({ kind, timeZone, scope, canBook, canAct, canSetRules, readOnly, phoneRegion }: Props) {
  const t = useTranslations('bookings');
  const router = useRouter();
  const leave = useCallback(() => leaveEndedSession(kind, router), [kind, router]);
  const clock = useApiClock(kind, timeZone, true);
  const list = useBookings(kind, clock.today, clock.now, scope === 'salon', leave);
  function booked(booking: Booking) {
    const when = whenOf(booking);
    toast.success(
      t('toasts.booked', {
        day: formatDay(when.date),
        time: formatClock(when.starts),
        name: firstName(booking.member.name),
        reference: booking.reference,
      }),
    );
    list.reload();
  }
  function done(action: SheetAction, booking: Booking | null) {
    toast.success(t(`toasts.${action}`, { name: firstName(booking?.customer.name ?? '') }));
  }

  const appointment = useAppointment(
    kind,
    list.query.booking,
    (action, booking) => {
      done(action, booking);
      list.reload();
      if (action === 'release') list.openBooking(null);
    },
    list.reload,
    leave,
  );
  const desk = useNewAppointment(kind, clock.today, clock.now, phoneRegion, booked, leave);
  const [closing, setClosing] = useState(false);
  const guard = useLeaveGuard(desk.dirty, (href) => {
    desk.close();
    router.push(href);
  });
  // What opened a sheet, which gets focus back when it closes.
  const opener = useRef<HTMLElement | null>(null);
  const remember = () => {
    opener.current = document.activeElement instanceof HTMLElement ? document.activeElement : null;
  };
  const giveFocusBack = () => opener.current?.isConnected && opener.current.focus();

  const day = list.day;
  const unavailable = clock.failed || list.problem === 'unavailable';
  const items = list.page?.items ?? [];
  const shownName = items.find((each) => each.id === list.query.booking)?.customer.name ?? '';
  const notices = readOnly && (
    <Alert variant="notice" data-testid="bookings-read-only">
      {readOnly === 'support' ? t('notices.readOnlySupport') : t('notices.readOnlySalon')}
    </Alert>
  );
  return (
    <div className="flex min-w-0 flex-col gap-4" data-testid="bookings-page">
      <div className="-mt-3 flex flex-wrap items-center justify-between gap-3">
        <p className="text-small text-foreground" aria-live="polite" data-testid="bookings-heading">
          {day && list.page && !list.loading ? t('heading', { count: list.page.total, day: formatDay(day) }) : ''}
        </p>
        {canBook && (
          <Button
            size="sm"
            disabled={!clock.today}
            onClick={() => {
              remember();
              desk.start();
            }}
            data-testid="bookings-new"
          >
            {t('new')}
          </Button>
        )}
      </div>
      {notices}
      {day && clock.today && (
        <BookingsTools
          day={day}
          today={clock.today}
          search={list.typed}
          status={list.query.status}
          professional={list.query.professional}
          members={scope === 'salon' ? list.members : null}
          onSearch={list.search}
          onDay={(next) => list.change({ day: next === clock.today ? null : next })}
          onStatus={(status) => list.change({ status })}
          onProfessional={(professional) => list.change({ professional })}
        />
      )}
      {unavailable ? (
        <Alert variant="caution" data-testid="bookings-unavailable">
          <AlertTitle>{t('notices.unavailableTitle')}</AlertTitle>
          <AlertDescription>{t('notices.unavailableBody')}</AlertDescription>
          <Button
            variant="outline"
            size="sm"
            className="w-fit"
            onClick={() => (clock.failed ? clock.retry() : list.reload())}
            data-testid="bookings-retry"
          >
            {t('notices.retry')}
          </Button>
        </Alert>
      ) : list.problem === 'refused' ? (
        <Alert variant="notice" data-testid="bookings-error">
          {t('notices.refused')}
        </Alert>
      ) : list.loading || !day ? (
        <div
          className="flex flex-col gap-2"
          role="status"
          aria-busy="true"
          aria-label={t('loading')}
          data-testid="bookings-loading"
        >
          <Skeleton className="h-14 w-full rounded-card" />
          <Skeleton className="h-14 w-full rounded-card" />
          <Skeleton className="h-14 w-full rounded-card" />
        </div>
      ) : items.length === 0 ? (
        <BookingsEmpty
          query={list.query}
          canBook={canBook}
          onClear={list.clear}
          onNew={() => {
            remember();
            desk.start();
          }}
        />
      ) : (
        <>
          <BookingsList
            day={day}
            bookings={items}
            now={clock.now}
            onOpen={(booking) => {
              remember();
              list.openBooking(booking.id);
            }}
          />
          {list.page && <BookingsPager page={list.page} onPage={(page) => list.change({ page }, true)} />}
        </>
      )}
      <AppointmentSheet
        open={list.query.booking !== null}
        name={shownName}
        detail={appointment.detail}
        loading={appointment.loading}
        unavailable={appointment.unavailable}
        canAct={canAct}
        today={clock.today ?? ''}
        now={clock.now}
        refusal={appointment.refusal}
        busy={appointment.busy}
        notices={notices}
        onAction={appointment.act}
        onRetry={appointment.retry}
        onClose={() => list.openBooking(null)}
        onClosed={giveFocusBack}
      />
      <CancelBookingDialog
        key={`cancel-${appointment.cancelOpened}`}
        booking={appointment.detail?.booking ?? null}
        open={appointment.cancelling}
        onKeep={appointment.keep}
        onCancel={appointment.cancel}
      />
      {clock.today && (
        <NewAppointmentSheet
          key={`desk-${desk.opened}`}
          open={desk.open}
          today={clock.today}
          choices={desk.choices}
          values={desk.values}
          problems={desk.problems}
          searching={desk.times.state === 'searching'}
          announcement={desk.announcement}
          onChange={desk.change}
          onFind={desk.find}
          onClose={() => (desk.dirty ? setClosing(true) : desk.close())}
          onClosed={giveFocusBack}
          results={
            <FreeTimes
              times={desk.times}
              hold={desk.hold}
              source={desk.values.source}
              now={clock.now}
              refusal={desk.refusal}
              canSetRules={canSetRules}
              busy={desk.busy}
              holdTitle={desk.holdTitle}
              onHold={(time) => void desk.holdTime(time)}
              onConfirm={() => void desk.confirm()}
              onAnother={() => void desk.another()}
            />
          }
        />
      )}
      <LeaveDialog
        area="new-appointment"
        open={closing || guard.pending !== null}
        onStay={() => {
          setClosing(false);
          guard.stay();
        }}
        onLeave={() => {
          setClosing(false);
          desk.close();
          guard.leave();
        }}
      />
      <Toaster position="bottom-center" />
    </div>
  );
}
