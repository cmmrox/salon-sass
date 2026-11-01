'use client';
import { todayInZone } from '@salon/format';
import { useRouter } from 'next/navigation';
import { useEffect, useState } from 'react';
import {
  ensurePrincipal,
  IdentityFailure,
  identityRequest,
  leaveEndedSession,
  principalInsideLock,
  supportsLocks,
  withIdentityLock,
  type Kind,
} from '@/features/identity';
import {
  badDays,
  choiceFormOf,
  choiceSaveOf,
  comingDays,
  dayOfField,
  sameWeek,
  shownYear,
  weekFormOf,
  weekSaveOf,
  type ChoiceForm,
  type Closure,
  type ClosureImpact,
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
 * The Hours & closures tab's state (F04-S03): the weekly hours, the published holidays with the salon's
 * choices, and the special closures, each read through the workspace BFF. Everything saves at once: hours
 * with their version, holiday choices by acceptance and then one day at a time, closures as they are added
 * or reopened. Each change runs under the session's cross-tab lock.
 */
export function useHoursSettings(kind: Kind, timeZone: string, notify: (event: HoursEvent) => void) {
  const router = useRouter();
  const today = todayInZone(timeZone);
  const [hours, setHours] = useState<HoursSettings | null>(null);
  const [week, setWeek] = useState<DayForm[]>([]);
  const [bad, setBad] = useState<WeekdayName[]>([]);
  const [year, setYear] = useState<HolidayYear | null>(null);
  const [choices, setChoices] = useState<Record<string, ChoiceForm>>({});
  const [closures, setClosures] = useState<Closure[]>([]);
  const [loading, setLoading] = useState(true);
  const [loaded, setLoaded] = useState(false);
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

  function showYear(next: HolidayYear | null) {
    setYear(next);
    setChoices(next ? Object.fromEntries(comingDays(next).map((day) => [day.date, choiceFormOf(day)])) : {});
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

  async function load() {
    setLoading(true);
    try {
      await ensurePrincipal(kind);
      const [settings, holidays, upcoming] = await Promise.all([
        identityRequest<HoursSettings>(kind, 'hours'),
        identityRequest<SalonHolidays>(kind, 'holidays'),
        identityRequest<Closure[]>(kind, 'closures'),
      ]);
      showHours(settings);
      showYear(shownYear(holidays.years, today));
      setClosures(upcoming);
      setNotice(null);
      setLoaded(true);
    } catch (error) {
      failed(error);
    } finally {
      setLoading(false);
    }
  }

  useEffect(() => {
    let active = true;
    Promise.resolve().then(() => {
      if (active) void load();
    });
    return () => {
      active = false;
    };
    // Loaded once when the tab opens; reload is the person's choice after a conflict.
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, []);

  /** One BFF change under the session's cross-tab lock, as every credential-bearing change is. */
  async function request<T>(action: string, body: unknown): Promise<T> {
    if (!supportsLocks()) throw new IdentityFailure('browser_unsupported');
    return withIdentityLock(kind, async () => {
      await principalInsideLock(kind);
      return identityRequest<T>(kind, action, body);
    });
  }

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
      showHours(await request<HoursSettings>('hours-save', weekSaveOf(week, hours.version)));
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

  async function choose(date: string, change: Partial<ChoiceForm>) {
    const next = { ...choices[date]!, ...change };
    setChoices((current) => ({ ...current, [date]: next }));
    // Before acceptance a choice is a proposal; after it, each change saves at once (AC-5).
    if (!year || year.version === 0) return;
    if (next.choice === 'special' && next.closes <= next.opens) return;
    setBusy('holidays');
    try {
      const saved = await request<HolidayYear>('holiday-choose', { year: year.year, ...choiceSaveOf(date, next) });
      showYear(saved);
      setNotice(null);
      notify('holidaySaved');
    } catch (error) {
      if (failed(error)) setNotice('fix');
    }
    setBusy(null);
  }

  async function accept() {
    if (!year) return;
    const coming = comingDays(year);
    if (
      coming.some(
        (day) => choices[day.date]!.choice === 'special' && choices[day.date]!.closes <= choices[day.date]!.opens,
      )
    ) {
      setNotice('fix');
      return;
    }
    setBusy('holidays');
    try {
      const saved = await request<HolidayYear>('holidays-accept', {
        year: year.year,
        version: year.version,
        choices: coming.map((day) => choiceSaveOf(day.date, choices[day.date]!)),
      });
      showYear(saved);
      setNotice(null);
      notify('holidaysAccepted');
    } catch (error) {
      if (failed(error)) setNotice('fix');
    }
    setBusy(null);
  }

  async function impactOn(date: string) {
    setClosureRefused(null);
    setImpact({ state: 'loading' });
    try {
      const answer = await identityRequest<ClosureImpact>(kind, `closure-impact?date=${encodeURIComponent(date)}`);
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
      const added = await request<Closure>('closure-add', { date, reason: reason.trim() === '' ? null : reason });
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
      await request<{ ok: boolean }>('closure-reopen', { id: closure.id });
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
    year,
    choices,
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
