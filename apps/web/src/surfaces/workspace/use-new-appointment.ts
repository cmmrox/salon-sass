'use client';
import { formatDay } from '@salon/format';
import { useTranslations } from 'next-intl';
import { useEffect, useRef, useState } from 'react';
import {
  availabilityRequest,
  aYearOn,
  holdRequest,
  minutesLeft,
  newAppointment,
  problemsOf,
  problemsOfFields,
  secondsLeft,
  started,
  withService,
  type AppointmentChoices,
  type AppointmentProblem,
  type AppointmentValues,
  type Availability,
  type Booking,
  type BookingDetail,
  type NewAppointmentRefusal,
  type OpenDays,
  type TimesState,
} from '@/features/booking';
import type { ServiceList } from '@/features/catalogue';
import type { Kind } from '@/features/identity';
import type { TeamMemberList } from '@/features/staff';
import { bookingChange, bookingRead, failureOf, releaseKept } from './booking-requests';

/** How long to wait before asking again whether a hold the countdown ended has run out by the API's clock. */
const RECHECK_MS = 2000;

/** The refusal each API code is said with; anything else is said as a failure. */
const REFUSALS: Record<string, NewAppointmentRefusal> = {
  slot_taken: 'taken',
  slot_unavailable: 'unavailable',
  hold_expired: 'ranOut',
  rules_not_set: 'rulesNotSet',
  permission_denied: 'permission',
  support_read_only: 'support',
  salon_read_only: 'salonReadOnly',
  service_unavailable: 'offline',
  browser_unsupported: 'offline',
  not_found: 'gone',
};

/**
 * New appointment (F07-S03 AC-4 to AC-6): the services, team and open days it offers, the form and its checks, the free
 * times, and one hold at a time, which the sheet owns: confirmed, released on "Choose another time", on closing, and on
 * leaving the page, and checked with the API when its countdown (from the API's expiry and clock) reaches zero.
 */
export function useNewAppointment(
  kind: Kind,
  today: string | null,
  now: number | null,
  region: string,
  onBooked: (booking: Booking) => void,
  leave: (session: unknown) => void,
) {
  const t = useTranslations('bookings.newAppointment');
  const [open, setOpen] = useState(false);
  const [opened, setOpened] = useState(0);
  const [choices, setChoices] = useState<AppointmentChoices | null>(null);
  const [values, setValues] = useState<AppointmentValues>(newAppointment);
  const [asked, setAsked] = useState(false);
  const [apiProblems, setApiProblems] = useState<AppointmentProblem[]>([]);
  const [times, setTimes] = useState<TimesState>({ state: 'idle' });
  const [hold, setHold] = useState<Booking | null>(null);
  const [refusal, setRefusal] = useState<NewAppointmentRefusal | null>(null);
  const [busy, setBusy] = useState(false);
  const [announcement, setAnnouncement] = useState('');
  const attempt = useRef<{ startsAt: string; key: string } | null>(null);
  const holdId = useRef<string | null>(null);
  const holdTitle = useRef<HTMLHeadingElement>(null);

  const problems = asked ? [...new Set([...problemsOf(values, region), ...apiProblems])] : [];

  /** The free times read again for the values (after a refusal), keeping what the person chose. */
  async function search(next: AppointmentValues) {
    setTimes({ state: 'searching' });
    try {
      const answer = await bookingRead<Availability>(kind, availabilityRequest(next));
      const professional =
        next.professional === 'any' ? null : (choices?.members.find((m) => m.id === next.professional)?.name ?? null);
      if (answer.times.length > 0) {
        setTimes({ state: 'times', day: answer.date, noPreference: professional === null, times: answer.times });
        setAnnouncement(t('timesIntro', { day: formatDay(answer.date) }));
      } else {
        const reason =
          answer.reason === 'not_working' && professional === null
            ? 'nobody_working'
            : (answer.reason ?? 'fully_booked');
        setTimes({ state: 'none', day: answer.date, reason, professional });
        setAnnouncement('');
      }
    } catch (error) {
      setTimes({ state: 'idle' });
      refused(error);
    }
  }

  function refused(error: unknown) {
    const failure = failureOf(error);
    if (failure.code === 'invalid_session') return leave(failure);
    if (failure.code === 'bad_request' && problemsOfFields(failure.fields).length > 0) {
      setApiProblems(problemsOfFields(failure.fields));
      return;
    }
    setRefusal(REFUSALS[failure.code] ?? 'failed');
  }

  /**
   * Releases the hold the sheet owns, if any, by a request that survives leaving the page at once; when that one
   * cannot say (an access credential to renew), under the session's lock. A hold already gone is no matter.
   */
  async function releaseHold() {
    const id = holdId.current;
    if (!id) return;
    holdId.current = null;
    setHold(null);
    if (!(await releaseKept(kind, id))) await bookingChange(kind, 'booking-release', { id }).catch(() => undefined);
  }

  // Leaving the page, or the page going away, releases the hold (design notes: "on leaving the page").
  useEffect(() => {
    const gone = () => holdId.current && void releaseKept(kind, holdId.current);
    window.addEventListener('pagehide', gone);
    return () => {
      window.removeEventListener('pagehide', gone);
      gone();
    };
  }, [kind]);

  // A chosen time's chip is gone once it is held: keyboard focus goes to the hold, once it is on screen (AC-11).
  const heldId = hold?.id ?? null;
  useEffect(() => {
    if (heldId) holdTitle.current?.focus();
  }, [heldId]);

  // At zero the countdown asks the API whether the hold has run out, and says what the API says (design notes).
  const left = hold?.holdExpiresAt && now !== null ? secondsLeft(hold.holdExpiresAt, now) : null;
  const [checks, setChecks] = useState(0);
  useEffect(() => {
    if (left !== 0 || !hold) return;
    let active = true;
    bookingRead<BookingDetail>(kind, `booking?id=${encodeURIComponent(hold.id)}`)
      .then((detail) => {
        if (!active) return;
        if (detail.booking.state === 'held') setTimeout(() => active && setChecks((n) => n + 1), RECHECK_MS);
        else ranOut();
      })
      .catch((error) => {
        if (!active) return;
        if (failureOf(error).code === 'not_found') ranOut();
        else setTimeout(() => active && setChecks((n) => n + 1), RECHECK_MS);
      });
    return () => {
      active = false;
    };
    // Asked once at zero, and again while the API still holds it.
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [left === 0, hold?.id, checks]);

  // The countdown is announced once a minute, never each second (AC-11).
  const minutes = left === null ? null : minutesLeft(left);
  const [announcedMinutes, setAnnouncedMinutes] = useState<number | null>(null);
  if (minutes !== announcedMinutes) {
    setAnnouncedMinutes(minutes);
    if (minutes !== null && minutes > 0 && announcedMinutes !== null) setAnnouncement(t('minutesLeft', { minutes }));
  }

  function ranOut() {
    holdId.current = null;
    setHold(null);
    setTimes({ state: 'idle' });
    setRefusal('ranOut');
    setAnnouncement('');
  }

  async function loadChoices(day: string) {
    try {
      const [services, team, open] = await Promise.all([
        bookingRead<ServiceList>(kind, 'catalogue-services'),
        bookingRead<TeamMemberList>(kind, 'team-members'),
        bookingRead<OpenDays>(kind, `booking-open-days?from=${day}&to=${aYearOn(day)}`),
      ]);
      const next: AppointmentChoices = {
        services: services.items.map((service) => ({
          id: service.id,
          name: service.name,
          categoryId: service.categoryId,
          currency: service.terms.currency,
          priceMinMinor: service.terms.priceMinMinor,
          priceMaxMinor: service.terms.priceMaxMinor,
          variantSetName: service.terms.variantSetName,
          variants: service.terms.variants.map(({ id, name, priceMinor }) => ({ id, name, priceMinor })),
        })),
        members: team.items.map((member) => ({
          id: member.id,
          name: member.name,
          serviceIds: member.services.serviceIds,
          categoryIds: member.services.categoryIds,
        })),
        openDays: open.days,
      };
      setChoices(next);
      setValues((current) => {
        const first = next.services[0];
        const withDay = { ...current, day: current.day ?? (open.days.includes(day) ? day : (open.days[0] ?? null)) };
        return first && !current.serviceId ? withService(withDay, first, next.members) : withDay;
      });
    } catch (error) {
      refused(error);
    }
  }

  return {
    open,
    opened,
    choices,
    values,
    problems,
    times,
    hold,
    refusal,
    busy,
    announcement,
    holdTitle,
    dirty: open && started(values),
    start: () => {
      if (!today) return;
      setValues(newAppointment());
      setAsked(false);
      setApiProblems([]);
      setTimes({ state: 'idle' });
      setRefusal(null);
      setAnnouncement('');
      setChoices(null);
      setOpened((n) => n + 1);
      setOpen(true);
      void loadChoices(today);
    },
    /** Closes the sheet, releasing its hold. */
    close: () => {
      void releaseHold();
      setOpen(false);
    },
    /** A changed field clears the times and gives back a held time (AC-5). */
    change: (next: AppointmentValues) => {
      setValues(next);
      setApiProblems([]);
      setTimes({ state: 'idle' });
      setRefusal(null);
      void releaseHold();
    },
    find: () => {
      setAsked(true);
      setRefusal(null);
      const broken = problemsOf(values, region);
      if (broken.length > 0) {
        setTimes({ state: 'idle' });
        document.getElementById(`new-appointment-${broken[0]}`)?.focus();
        return;
      }
      void search(values);
    },
    /** Holds a time: one attempt per chip, so a double click sends one key and places one hold (AC-6). */
    holdTime: async (time: Availability['times'][number]) => {
      if (busy) return;
      if (attempt.current?.startsAt !== time.startsAt)
        attempt.current = { startsAt: time.startsAt, key: crypto.randomUUID() };
      setBusy(true);
      setRefusal(null);
      try {
        const held = await bookingChange<Booking>(
          kind,
          'booking-place',
          holdRequest(values, time.time, attempt.current.key),
        );
        attempt.current = null;
        holdId.current = held.id;
        setHold(held);
        setAnnouncement(t('holding'));
      } catch (error) {
        attempt.current = null;
        refused(error);
        const code = failureOf(error).code;
        if (code === 'slot_taken' || code === 'slot_unavailable') await search(values);
      }
      setBusy(false);
    },
    confirm: async () => {
      if (!hold || busy) return;
      setBusy(true);
      setRefusal(null);
      try {
        const booking = await bookingChange<Booking>(kind, 'booking-confirm', { id: hold.id });
        holdId.current = null;
        setHold(null);
        setOpen(false);
        onBooked(booking);
      } catch (error) {
        const code = failureOf(error).code;
        if (code === 'hold_expired' || code === 'not_found') ranOut();
        else refused(error);
      }
      setBusy(false);
    },
    /** Gives back the held time and shows the day's times again. */
    another: async () => {
      setRefusal(null);
      // The times shown before the hold are stale until the release lands and they are read again.
      setTimes({ state: 'searching' });
      await releaseHold();
      await search(values);
    },
  };
}
