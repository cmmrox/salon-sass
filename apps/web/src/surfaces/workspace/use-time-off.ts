'use client';
import { useRef, useState, type RefObject } from 'react';
import { IdentityFailure, type Kind } from '@/features/identity';
import {
  blockAsksImpact,
  blockIsEdited,
  blockProblem,
  blockRefusalOfFields,
  blockSaveOf,
  leaveAsksImpact,
  leaveIsEdited,
  leaveProblem,
  leaveRefusalOfFields,
  leaveSaveOf,
  newBlock,
  newLeave,
  withBlock,
  withLeave,
  type BlockForm,
  type BlockRefusal,
  type LeaveForm,
  type LeaveRefusal,
  type MemberBlock,
  type MemberLeave,
  type MemberSchedule,
  type PeriodImpact,
  type PeriodImpactState,
  useStaffAsk,
  type StaffChange,
} from '@/features/staff';

/** The passing notices of leave and blocks. */
export type TimeOffToast =
  'leaveAdded' | 'leaveAddedFlagged' | 'leaveRemoved' | 'timeBlocked' | 'timeBlockedFlagged' | 'blockRemoved';

type Deps = {
  kind: Kind;
  /** The sheet's opening an answer belongs to: an answer for a sheet since closed or reopened is dropped. */
  opening: RefObject<number>;
  /** A change made under the session's lock. */
  send: StaffChange;
  /** A failure the sheet answers itself; one the caller says in its own words is returned to it. */
  failed: (error: unknown) => IdentityFailure | null;
  clearSaid: () => void;
  notify: (toast: TimeOffToast, values?: { count: number }) => void;
  /** The card's badge follows a person's leave. */
  onLeaveChanged: () => void;
};

const IDLE: PeriodImpactState = { state: 'idle' };

/**
 * The schedule sheet's Leave and Blocked time tabs (F06-S03): the lists, each form with its checks and the bookings its period
 * would affect, and every change, each under the session's lock. The API's answer says which bookings a period holds; nothing
 * about them is kept here.
 */
export function useTimeOff({ kind, opening, send, failed, clearSaid, notify, onLeaveChanged }: Deps) {
  const [leave, setLeave] = useState<MemberLeave[]>([]);
  const [blocks, setBlocks] = useState<MemberBlock[]>([]);
  const [leaveForm, setLeaveForm] = useState<LeaveForm | null>(null);
  const [blockForm, setBlockForm] = useState<BlockForm | null>(null);
  const [leaveRefusal, setLeaveRefusal] = useState<LeaveRefusal | null>(null);
  const [blockRefusal, setBlockRefusal] = useState<BlockRefusal | null>(null);
  const [leaveImpact, setLeaveImpact] = useState<PeriodImpactState>(IDLE);
  const [blockImpact, setBlockImpact] = useState<PeriodImpactState>(IDLE);
  const [removingLeave, setRemovingLeave] = useState<MemberLeave | null>(null);
  const [busy, setBusy] = useState(false);
  // The latest question each form asked: an answer to an earlier period is dropped.
  const leaveAsked = useRef(0);
  const blockAsked = useRef(0);

  const ask = useStaffAsk(kind);

  async function askImpact(
    asking: () => Promise<PeriodImpact>,
    asked: RefObject<number>,
    show: (state: PeriodImpactState) => void,
  ): Promise<void> {
    asked.current += 1;
    const question = asked.current;
    const started = opening.current;
    show({ state: 'loading' });
    try {
      const answer = await asking();
      if (asked.current === question && opening.current === started)
        show({ state: 'ready', bookings: answer.affected });
    } catch (error) {
      if (asked.current !== question || opening.current !== started) return;
      const failure = error instanceof IdentityFailure ? error : new IdentityFailure('service_unavailable');
      if (failure.code === 'invalid_session') failed(failure);
      show(failure.code === 'bad_request' ? IDLE : { state: 'unavailable' });
    }
  }

  function askLeaveImpact(memberId: string, form: LeaveForm) {
    if (!leaveAsksImpact(form)) {
      leaveAsked.current += 1;
      setLeaveImpact(IDLE);
      return;
    }
    const query = { memberId, firstDay: form.firstDay!, lastDay: form.lastDay! };
    void askImpact(() => ask('team-leave-affected', query), leaveAsked, setLeaveImpact);
  }

  function askBlockImpact(memberId: string, form: BlockForm, previous: BlockForm | null) {
    if (!blockAsksImpact(form)) {
      blockAsked.current += 1;
      setBlockImpact(IDLE);
      return;
    }
    // The label is no part of the period.
    if (previous?.date === form.date && previous?.starts === form.starts && previous?.finishes === form.finishes)
      return;
    const query = { memberId, date: form.date!, starts: form.starts, finishes: form.finishes };
    void askImpact(() => ask('team-block-affected', query), blockAsked, setBlockImpact);
  }

  async function addLeave(memberId: string) {
    if (!leaveForm) return;
    const problem = leaveProblem(leaveForm);
    if (problem) {
      setLeaveRefusal(problem);
      return;
    }
    const started = opening.current;
    setBusy(true);
    clearSaid();
    setLeaveRefusal(null);
    try {
      const answer = await send('team-leave-add', { id: memberId, ...leaveSaveOf(leaveForm) });
      if (opening.current !== started) return;
      setLeave((list) => withLeave(list, answer.leave));
      setLeaveForm(null);
      setLeaveImpact(IDLE);
      const count = answer.affected.length;
      if (count > 0) notify('leaveAddedFlagged', { count });
      else notify('leaveAdded');
      onLeaveChanged();
    } catch (error) {
      if (opening.current !== started) return;
      const left = failed(error);
      if (left?.code === 'conflict') setLeaveRefusal('overlap');
      else if (left?.code === 'limit_reached') setLeaveRefusal('limit');
      else if (left?.code === 'bad_request') setLeaveRefusal(leaveRefusalOfFields(left.fields, leaveForm));
    } finally {
      if (opening.current === started) setBusy(false);
    }
  }

  async function confirmRemoveLeave(memberId: string) {
    const target = removingLeave;
    if (!target) return;
    const started = opening.current;
    setRemovingLeave(null);
    setBusy(true);
    clearSaid();
    try {
      await send('team-leave-remove', { id: memberId, leaveId: target.id });
      if (opening.current !== started) return;
      setLeave((list) => list.filter((each) => each.id !== target.id));
      notify('leaveRemoved');
      onLeaveChanged();
    } catch (error) {
      if (opening.current === started) failed(error);
    } finally {
      if (opening.current === started) setBusy(false);
    }
  }

  async function addBlock(memberId: string) {
    if (!blockForm) return;
    const problem = blockProblem(blockForm);
    if (problem) {
      setBlockRefusal(problem);
      return;
    }
    const started = opening.current;
    setBusy(true);
    clearSaid();
    setBlockRefusal(null);
    try {
      const answer = await send('team-block-add', { id: memberId, ...blockSaveOf(blockForm) });
      if (opening.current !== started) return;
      setBlocks((list) => withBlock(list, answer.block));
      setBlockForm(null);
      setBlockImpact(IDLE);
      const count = answer.affected.length;
      if (count > 0) notify('timeBlockedFlagged', { count });
      else notify('timeBlocked');
    } catch (error) {
      if (opening.current !== started) return;
      const left = failed(error);
      if (left?.code === 'conflict') setBlockRefusal('overlap');
      else if (left?.code === 'limit_reached') setBlockRefusal('limit');
      else if (left?.code === 'bad_request') setBlockRefusal(blockRefusalOfFields(left.fields));
    } finally {
      if (opening.current === started) setBusy(false);
    }
  }

  async function removeBlock(memberId: string, target: MemberBlock) {
    const started = opening.current;
    setBusy(true);
    clearSaid();
    try {
      await send('team-block-remove', { id: memberId, blockId: target.id });
      if (opening.current !== started) return;
      setBlocks((list) => list.filter((each) => each.id !== target.id));
      notify('blockRemoved');
    } catch (error) {
      if (opening.current === started) failed(error);
    } finally {
      if (opening.current === started) setBusy(false);
    }
  }

  /** Back to nothing started, the lists as they were read or empty. */
  function reset(schedule: MemberSchedule | null) {
    leaveAsked.current += 1;
    blockAsked.current += 1;
    setLeave(schedule?.leave ?? []);
    setBlocks(schedule?.blockedTime ?? []);
    setLeaveForm(null);
    setBlockForm(null);
    setLeaveRefusal(null);
    setBlockRefusal(null);
    setLeaveImpact(IDLE);
    setBlockImpact(IDLE);
    setRemovingLeave(null);
    setBusy(false);
  }

  return {
    leave,
    blocks,
    leaveForm,
    blockForm,
    leaveRefusal,
    blockRefusal,
    leaveImpact,
    blockImpact,
    removingLeave,
    busy,
    leaveEdited: Boolean(leaveForm && leaveIsEdited(leaveForm)),
    blockEdited: Boolean(blockForm && blockIsEdited(blockForm)),
    reset,
    addLeave,
    addBlock,
    removeBlock,
    confirmRemoveLeave,
    askRemoveLeave: setRemovingLeave,
    cancelRemoveLeave: () => setRemovingLeave(null),
    startLeave: () => {
      clearSaid();
      setLeaveForm(newLeave());
      setLeaveRefusal(null);
      setLeaveImpact(IDLE);
    },
    editLeave: (memberId: string, form: LeaveForm) => {
      const periodChanged = form.firstDay !== leaveForm?.firstDay || form.lastDay !== leaveForm?.lastDay;
      setLeaveForm(form);
      setLeaveRefusal(null);
      if (periodChanged) askLeaveImpact(memberId, form);
    },
    cancelLeave: () => {
      leaveAsked.current += 1;
      setLeaveForm(null);
      setLeaveRefusal(null);
      setLeaveImpact(IDLE);
    },
    startBlock: () => {
      clearSaid();
      setBlockForm(newBlock());
      setBlockRefusal(null);
      setBlockImpact(IDLE);
    },
    editBlock: (memberId: string, form: BlockForm) => {
      const previous = blockForm;
      setBlockForm(form);
      setBlockRefusal(null);
      askBlockImpact(memberId, form, previous);
    },
    cancelBlock: () => {
      blockAsked.current += 1;
      setBlockForm(null);
      setBlockRefusal(null);
      setBlockImpact(IDLE);
    },
  };
}
