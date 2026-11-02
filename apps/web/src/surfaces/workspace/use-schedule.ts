'use client';
import { useRouter } from 'next/navigation';
import { useRef, useState } from 'react';
import {
  IdentityFailure,
  identityRequest,
  leaveEndedSession,
  principalInsideLock,
  supportsLocks,
  withIdentityLock,
  type Kind,
} from '@/features/identity';
import {
  changeIsEdited,
  changeSaveOf,
  copyMonday,
  newChange,
  problemOfField,
  problemsOfWeekFields,
  salonHoursOn,
  sameWorkWeek,
  weekProblems,
  withChange,
  workWeekFormOf,
  workWeekSaveOf,
  type AddedDateChange,
  type ChangeForm,
  type ChangeRefusal,
  type HoursProblem,
  type MemberDateChange,
  type MemberSchedule,
  type SalonOpeningWeek,
  type SavedMemberWeek,
  type ScheduleTab,
  type TeamAffectedBooking,
  type TeamMember,
  type WeekProblem,
  type Weekday,
  type WeekdayName,
  type WorkdayForm,
} from '@/features/staff';

/** What the sheet says above its tabs: someone else changed it, it could not be reached, or a change was refused. */
export type ScheduleNotice = 'conflict' | 'unavailable' | 'refused' | 'unchanged' | null;

/** The passing notices the sheet announces in a toast. */
export type ScheduleToast = 'hoursSaved' | 'dateSaved' | 'dateRemoved';

type Loaded = {
  version: number;
  today: string;
  saved: WorkdayForm[];
  salonWeek: Weekday[] | null;
  changes: MemberDateChange[];
};

/**
 * The schedule sheet's state (F06-S02): the person's week and date changes, the salon's week beside them, the form with its
 * checks, and every change, each under the session's cross-tab lock. A saved week with no booking left outside closes the
 * sheet, as the prototype's does; one that leaves bookings outside stays open to list them (AC-11). Bookings are the API's.
 */
export function useSchedule(
  kind: Kind,
  canManage: boolean,
  notify: (toast: ScheduleToast) => void,
  onSaved: () => void,
) {
  const router = useRouter();
  const [member, setMember] = useState<TeamMember | null>(null);
  const [open, setOpen] = useState(false);
  const [opened, setOpened] = useState(0);
  const [tab, setTab] = useState<ScheduleTab>('week');
  const [loaded, setLoaded] = useState<Loaded | null>(null);
  const [week, setWeek] = useState<WorkdayForm[]>([]);
  const [apiProblems, setApiProblems] = useState<WeekProblem[]>([]);
  const [change, setChange] = useState<ChangeForm | null>(null);
  const [changeRefusal, setChangeRefusal] = useState<ChangeRefusal>(null);
  const [changeApiProblems, setChangeApiProblems] = useState<HoursProblem[]>([]);
  const [affected, setAffected] = useState<TeamAffectedBooking[]>([]);
  const [affectedOnDate, setAffectedOnDate] = useState(false);
  const [loading, setLoading] = useState(false);
  const [busy, setBusy] = useState(false);
  const [notice, setNotice] = useState<ScheduleNotice>(null);
  const [locked, setLocked] = useState(false);
  // The opening a read or a change belongs to: an answer for a sheet since closed or reopened is dropped.
  const opening = useRef(0);

  const may = canManage && !locked;
  const clientProblems = weekProblems(week);
  const problems = clientProblems.length > 0 ? clientProblems : apiProblems;
  const weekEdited = Boolean(loaded && !sameWorkWeek(week, loaded.saved));
  const dirty = open && may && (weekEdited || Boolean(change && changeIsEdited(change)));

  /** A failure the sheet answers itself; one the caller says in its own words is returned to it. */
  function failed(error: unknown): IdentityFailure | null {
    const failure = error instanceof IdentityFailure ? error : new IdentityFailure('service_unavailable');
    if (failure.code === 'invalid_session') {
      leaveEndedSession(kind, router);
      return null;
    }
    if (['bad_request', 'limit_reached', 'invalid_state'].includes(failure.code)) return failure;
    if (failure.code === 'conflict') return failure;
    if (failure.code === 'not_found') setNotice('conflict');
    else if (failure.code === 'service_unavailable' || failure.code === 'browser_unsupported') setNotice('unavailable');
    else {
      setNotice('refused');
      setLocked(true);
    }
    return null;
  }

  async function load(person: TeamMember, started: number) {
    setLoading(true);
    try {
      const [schedule, salon] = await Promise.all([
        identityRequest<MemberSchedule>(kind, `team-schedule?memberId=${encodeURIComponent(person.id)}`),
        identityRequest<SalonOpeningWeek>(kind, 'team-opening-hours'),
      ]);
      if (opening.current !== started) return;
      const saved = workWeekFormOf(schedule.weekly);
      setLoaded({
        version: schedule.version,
        today: schedule.today,
        saved,
        salonWeek: salon.weekly ?? null,
        changes: schedule.dateChanges,
      });
      setWeek(saved);
      setApiProblems([]);
      setNotice((current) => (current === 'refused' ? current : null));
    } catch (error) {
      if (opening.current === started) failed(error);
    } finally {
      if (opening.current === started) setLoading(false);
    }
  }

  /** A change made under the session's lock. */
  async function send<T>(action: string, body: unknown): Promise<T> {
    if (!supportsLocks()) throw new IdentityFailure('browser_unsupported');
    return withIdentityLock(kind, async () => {
      await principalInsideLock(kind);
      return identityRequest<T>(kind, action, body);
    });
  }

  function clearSaid() {
    setNotice((current) => (current === 'refused' ? current : null));
    setAffected([]);
  }

  async function saveWeek() {
    if (!member || !loaded || clientProblems.length > 0) return;
    const started = opening.current;
    setBusy(true);
    clearSaid();
    setApiProblems([]);
    try {
      const answer = await send<SavedMemberWeek>('team-week-save', {
        id: member.id,
        version: loaded.version,
        weekly: workWeekSaveOf(week),
      });
      if (opening.current !== started) return;
      const saved = workWeekFormOf(answer.weekly);
      setLoaded({ ...loaded, version: answer.version, saved });
      setWeek(saved);
      setAffected(answer.affected);
      setAffectedOnDate(false);
      notify('hoursSaved');
      onSaved();
      if (answer.affected.length === 0) setOpen(false);
    } catch (error) {
      if (opening.current !== started) return;
      const left = failed(error);
      if (left?.code === 'bad_request') setApiProblems(problemsOfWeekFields(left.fields, week));
      else if (left?.code === 'invalid_state') setNotice('unchanged');
      else if (left?.code === 'conflict') setNotice('conflict');
    } finally {
      if (opening.current === started) setBusy(false);
    }
  }

  async function addChange() {
    if (!member || !loaded || !change) return;
    if (!change.date) {
      setChangeRefusal('dateMissing');
      return;
    }
    const started = opening.current;
    setBusy(true);
    clearSaid();
    setChangeRefusal(null);
    setChangeApiProblems([]);
    try {
      const answer = await send<AddedDateChange>('team-date-change-add', {
        id: member.id,
        ...changeSaveOf(change),
      });
      if (opening.current !== started) return;
      setLoaded({ ...loaded, changes: withChange(loaded.changes, answer.change) });
      setChange(null);
      setAffected(answer.affected);
      setAffectedOnDate(true);
      notify('dateSaved');
    } catch (error) {
      if (opening.current !== started) return;
      const left = failed(error);
      if (left?.code === 'conflict') setChangeRefusal('dateTaken');
      else if (left?.code === 'limit_reached') setChangeRefusal('limit');
      else if (left?.code === 'bad_request') {
        if (left.fields.includes('date')) setChangeRefusal('dateRule');
        else if (left.fields.includes('note')) setChangeRefusal('note');
        setChangeApiProblems(
          left.fields.flatMap((field) => {
            const problem = problemOfField(field, change.breaks.length);
            return problem ? [problem] : [];
          }),
        );
      }
    } finally {
      if (opening.current === started) setBusy(false);
    }
  }

  async function removeChange(target: MemberDateChange) {
    if (!member || !loaded) return;
    const started = opening.current;
    setBusy(true);
    clearSaid();
    try {
      await send<{ ok: true }>('team-date-change-remove', { id: member.id, changeId: target.id });
      if (opening.current !== started) return;
      setLoaded({ ...loaded, changes: loaded.changes.filter((each) => each.id !== target.id) });
      notify('dateRemoved');
    } catch (error) {
      if (opening.current === started) failed(error);
    } finally {
      if (opening.current === started) setBusy(false);
    }
  }

  /** Reads again after a conflict, edits dropped. */
  async function reload() {
    if (!member) return;
    setChange(null);
    setChangeRefusal(null);
    setAffected([]);
    await load(member, opening.current);
    onSaved();
  }

  return {
    member,
    open,
    opened,
    tab,
    today: loaded?.today ?? '',
    salonWeek: loaded?.salonWeek ?? null,
    changes: loaded?.changes ?? [],
    ready: loaded !== null,
    week,
    problems,
    change,
    changeRefusal,
    changeApiProblems,
    affected,
    affectedOnDate,
    weekEdited,
    loading,
    busy,
    notice,
    may,
    dirty,
    saveWeek,
    addChange,
    removeChange,
    reload,
    openFor: (person: TeamMember) => {
      opening.current += 1;
      setMember(person);
      setOpened(opening.current);
      setOpen(true);
      setTab('week');
      setLoaded(null);
      setWeek([]);
      setChange(null);
      setChangeRefusal(null);
      setAffected([]);
      setNotice(null);
      void load(person, opening.current);
    },
    close: () => {
      opening.current += 1;
      setOpen(false);
      setBusy(false);
      setLoading(false);
    },
    selectTab: (next: ScheduleTab) => setTab(next),
    changeDay: (day: WeekdayName, edit: Partial<WorkdayForm>) => {
      setWeek((current) => current.map((each) => (each.day === day ? { ...each, ...edit } : each)));
      setApiProblems([]);
      clearSaid();
    },
    copyMonday: () => {
      setWeek(copyMonday);
      setApiProblems([]);
      clearSaid();
    },
    applySalonHours: () => {
      const salon = loaded?.salonWeek;
      if (!salon) return;
      setWeek((current) => salonHoursOn(current, salon));
      setApiProblems([]);
      clearSaid();
    },
    startChange: () => {
      setChange(newChange());
      setChangeRefusal(null);
      setChangeApiProblems([]);
      setAffected([]);
    },
    editChange: (form: ChangeForm) => {
      setChange(form);
      setChangeRefusal(null);
      setChangeApiProblems([]);
    },
    cancelChange: () => {
      setChange(null);
      setChangeRefusal(null);
      setChangeApiProblems([]);
    },
  };
}
