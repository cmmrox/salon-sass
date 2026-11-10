'use client';
import { todayInZone } from '@salon/format';
import { useRouter } from 'next/navigation';
import { useState } from 'react';
import { IdentityFailure, leaveEndedSession, usePrincipal, type Kind } from '@/features/identity';
import { useReadFailure } from '@/lib/api-core/data-layer';
import {
  badDays,
  choiceFormOf,
  choiceSaveOf,
  comingDays,
  dayOfField,
  sameWeek,
  shownYears,
  weekFormOf,
  weekSaveOf,
  useClosures,
  useHolidays,
  useOpeningHours,
  useSetStaffAnswer,
  useStaffAsk,
  useStaffChanges,
  type ChoiceForm,
  type Closure,
  type DayForm,
  type HolidayYear,
  type HoursSettings,
  type ImpactState,
  type SalonHolidays,
  type WeekdayName,
} from '@/features/staff';
import type { SettingsNotice } from './use-settings-draft';

type Busy = 'hours' | 'holidays' | 'closure' | 'reopen' | null;
export type HoursEvent = 'hoursSaved' | 'holidaysAccepted' | 'holidaySaved' | 'closed' | 'reopened';

/**
 * The Hours & closures tab's state (F04-S03): the weekly hours, the published holiday years with the salon's
 * choices (each year with its own version, accepted on its own), and the special closures, each read through the workspace BFF. Everything saves at once: hours
 * with their version, holiday choices by acceptance and then one day at a time, closures as they are added
 * or reopened. Each change runs under the session's cross-tab lock.
 */
export function useHoursSettings(kind: Kind, timeZone: string, notify: (event: HoursEvent) => void) {
  const router = useRouter();
  const today = todayInZone(timeZone);
  const [hours, setHours] = useState<HoursSettings | null>(null);
  const [week, setWeek] = useState<DayForm[]>([]);
  const [bad, setBad] = useState<WeekdayName[]>([]);
  const [years, setYears] = useState<HolidayYear[]>([]);
  /** Each shown year's choices, by year and then date. */
  const [choices, setChoices] = useState<Record<number, Record<string, ChoiceForm>>>({});
  /** The year a holiday change is being saved for. */
  const [holidayYear, setHolidayYear] = useState<number | null>(null);
  const reads = {
    principal: usePrincipal(kind),
    hours: useOpeningHours(kind),
    holidays: useHolidays(kind),
    closures: useClosures(kind),
  };
  const ask = useStaffAsk(kind);
  const request = useStaffChanges(kind).run;
  const setAnswer = useSetStaffAnswer(kind);
  const loaded = Boolean(reads.principal.data && reads.hours.data && reads.holidays.data && reads.closures.data);
  const closures = loaded ? (reads.closures.data ?? []) : [];
  const setClosures = (next: (current: Closure[]) => Closure[]) =>
    setAnswer('closures', next(reads.closures.data ?? []));
  const loading = Object.values(reads).some((each) => each.isFetching) || (!loaded && !failedRead(reads));
  const [notice, setNotice] = useState<SettingsNotice>(null);
  const [refusal, setRefusal] = useState<string | null>(null);
  const [busy, setBusy] = useState<Busy>(null);
  const [reopening, setReopening] = useState<string | null>(null);
  const [sheetOpen, setSheetOpen] = useState(false);
  const [impact, setImpact] = useState<ImpactState>({ state: 'idle' });
  const [closureRefused, setClosureRefused] = useState<'date' | 'reason' | 'taken' | null>(null);

  function showHours(next: HoursSettings) {
    setHours(next);
    setWeek(weekFormOf(next));
    setBad([]);
  }

  const choicesOf = (year: HolidayYear) =>
    Object.fromEntries(comingDays(year).map((day) => [day.date, choiceFormOf(day)]));

  function showYears(next: HolidayYear[]) {
    setYears(next);
    setChoices(Object.fromEntries(next.map((year) => [year.year, choicesOf(year)])));
  }

  /** One year as the API answered a change to it; the other years and their unsaved choices are kept. */
  function showYear(next: HolidayYear) {
    setYears((current) => current.map((year) => (year.year === next.year ? next : year)));
    setChoices((current) => ({ ...current, [next.year]: choicesOf(next) }));
  }

  // The forms start from each version read: the week from the hours, the years and choices from the holidays.
  const [shown, setShown] = useState<{ hours?: HoursSettings; holidays?: SalonHolidays }>({});
  if (loaded && (reads.hours.data !== shown.hours || reads.holidays.data !== shown.holidays)) {
    setShown({ hours: reads.hours.data, holidays: reads.holidays.data });
    if (reads.hours.data !== shown.hours && reads.hours.data) showHours(reads.hours.data);
    if (reads.holidays.data !== shown.holidays && reads.holidays.data) showYears(shownYears(reads.holidays.data.years));
  }

  function failed(error: unknown): IdentityFailure | null {
    const failure = error instanceof IdentityFailure ? error : new IdentityFailure('service_unavailable');
    if (failure.code === 'invalid_session') {
      leaveEndedSession(kind, router);
      return null;
    }
    if (failure.code === 'conflict') {
      setNotice('conflict');
      return null;
    }
    if (failure.code === 'service_unavailable' || failure.code === 'browser_unsupported') {
      setNotice('unavailable');
      return null;
    }
    if (failure.code === 'bad_request') return failure;
    setRefusal(failure.code);
    setNotice('refused');
    return null;
  }

  /** Reads the tab again after a conflict: what is on the server replaces what was edited. */
  async function load() {
    const [principal, settings, holidays, upcoming] = await Promise.all([
      reads.principal.refetch(),
      reads.hours.refetch(),
      reads.holidays.refetch(),
      reads.closures.refetch(),
    ]);
    if ([principal, settings, holidays, upcoming].some((each) => each.error)) return;
    if (settings.data) showHours(settings.data);
    if (holidays.data) showYears(shownYears(holidays.data.years));
    setShown({ hours: settings.data, holidays: holidays.data });
    setNotice(null);
  }

  useReadFailure(Object.values(reads), (failure) => failed(failure));

  function changeDay(day: WeekdayName, change: Partial<DayForm>) {
    setWeek((current) => current.map((each) => (each.day === day ? { ...each, ...change } : each)));
    setBad((current) => current.filter((each) => each !== day));
  }

  async function saveHours() {
    if (!hours) return;
    const wrong = badDays(week);
    if (wrong.length > 0) {
      setBad(wrong);
      setNotice('fix');
      return;
    }
    setBusy('hours');
    try {
      const saved = await request('hours-save', weekSaveOf(week, hours.version));
      setAnswer('hours', saved);
      setShown((current) => ({ ...current, hours: saved }));
      showHours(saved);
      setNotice(null);
      notify('hoursSaved');
    } catch (error) {
      const refused = failed(error);
      if (refused) {
        setBad(refused.fields.map(dayOfField).filter((day): day is WeekdayName => day !== null));
        setNotice('fix');
      }
    }
    setBusy(null);
  }

  async function choose(yearNumber: number, date: string, change: Partial<ChoiceForm>) {
    const year = years.find((each) => each.year === yearNumber);
    if (!year) return;
    const next = { ...choices[yearNumber]![date]!, ...change };
    setChoices((current) => ({ ...current, [yearNumber]: { ...current[yearNumber], [date]: next } }));
    // Before acceptance a choice is a proposal; after it, each change saves at once (AC-5).
    if (year.version === 0) return;
    if (next.choice === 'special' && next.closes <= next.opens) return;
    setBusy('holidays');
    setHolidayYear(yearNumber);
    try {
      const saved = await request('holiday-choose', { year: year.year, ...choiceSaveOf(date, next) });
      showYear(saved);
      setNotice(null);
      notify('holidaySaved');
    } catch (error) {
      if (failed(error)) setNotice('fix');
    }
    setBusy(null);
    setHolidayYear(null);
  }

  async function accept(yearNumber: number) {
    const year = years.find((each) => each.year === yearNumber);
    if (!year) return;
    const coming = comingDays(year);
    const chosen = choices[yearNumber]!;
    if (
      coming.some(
        (day) => chosen[day.date]!.choice === 'special' && chosen[day.date]!.closes <= chosen[day.date]!.opens,
      )
    ) {
      setNotice('fix');
      return;
    }
    setBusy('holidays');
    setHolidayYear(yearNumber);
    try {
      const saved = await request('holidays-accept', {
        year: year.year,
        version: year.version,
        choices: coming.map((day) => choiceSaveOf(day.date, chosen[day.date]!)),
      });
      showYear(saved);
      setNotice(null);
      notify('holidaysAccepted');
    } catch (error) {
      if (failed(error)) setNotice('fix');
    }
    setBusy(null);
    setHolidayYear(null);
  }

  async function impactOn(date: string) {
    setClosureRefused(null);
    setImpact({ state: 'loading' });
    try {
      const answer = await ask('closure-impact', { date });
      setImpact({ state: 'ready', bookings: answer.bookings });
    } catch (error) {
      const failure = error instanceof IdentityFailure ? error : new IdentityFailure('service_unavailable');
      if (failure.code === 'bad_request') setClosureRefused('date');
      setImpact(failure.code === 'bad_request' ? { state: 'idle' } : { state: 'unavailable' });
    }
  }

  async function close(date: string, reason: string) {
    setBusy('closure');
    setClosureRefused(null);
    try {
      const added = await request('closure-add', { date, reason: reason.trim() === '' ? null : reason });
      setClosures((current) => [...current, added].sort((a, b) => a.date.localeCompare(b.date)));
      setSheetOpen(false);
      setImpact({ state: 'idle' });
      setNotice(null);
      notify('closed');
    } catch (error) {
      const failure = error instanceof IdentityFailure ? error : new IdentityFailure('service_unavailable');
      if (failure.code === 'conflict') setClosureRefused('taken');
      else if (failure.code === 'bad_request') setClosureRefused(failure.fields.includes('reason') ? 'reason' : 'date');
      else {
        setSheetOpen(false);
        failed(error);
      }
    }
    setBusy(null);
  }

  async function reopen(closure: Closure) {
    setReopening(closure.id);
    try {
      await request('closure-reopen', { id: closure.id });
      setClosures((current) => current.filter((each) => each.id !== closure.id));
      setNotice(null);
      notify('reopened');
    } catch (error) {
      failed(error);
    }
    setReopening(null);
  }

  const dirty = Boolean(hours && week.length > 0 && !sameWeek(week, weekFormOf(hours)));

  return {
    today,
    hours,
    week,
    bad,
    years,
    choices,
    holidayYear,
    closures,
    loading,
    loaded,
    notice,
    refusal,
    busy,
    reopening,
    dirty,
    sheetOpen,
    impact,
    closureRefused,
    setSheetOpen: (open: boolean) => {
      setSheetOpen(open);
      if (!open) {
        setImpact({ state: 'idle' });
        setClosureRefused(null);
      }
    },
    reload: load,
    changeDay,
    saveHours,
    choose,
    accept,
    impactOn,
    close,
    reopen,
  };
}

/** Whether one of the tab's reads failed, so that it is not loading any more. */
function failedRead(reads: Record<string, { error: unknown }>) {
  return Object.values(reads).some((each) => each.error);
}
