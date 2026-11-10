import type { MemberBlock, MemberLeave, TeamAffectedBooking } from '../types';

/** What kind of leave it is. */
export type LeaveType = 'annual' | 'sick' | 'personal' | 'other';
export const LEAVE_TYPES: LeaveType[] = ['annual', 'sick', 'personal', 'other'];

/** A leave's note is up to 60 characters, and a block's label 2 to 40 (F06-S03 AC-1, AC-4). */
export const NOTE_MAX = 60;
const LABEL_MIN = 2;
export const LABEL_MAX = 40;

/** Leave as the sheet's form holds it. */
export type LeaveForm = { firstDay: string | null; lastDay: string | null; type: LeaveType; note: string };

/** A block as the sheet's form holds it. */
export type BlockForm = { date: string | null; starts: string; finishes: string; label: string };

/** Why leave was refused beside its form: by the form's own checks when saving, or by the API. */
export type LeaveRefusal = 'daysMissing' | 'lastBeforeFirst' | 'daysRule' | 'overlap' | 'limit' | 'note';

/** Why a block was refused beside its form. */
export type BlockRefusal = 'dateMissing' | 'endAfterStart' | 'label' | 'dateRule' | 'overlap' | 'limit';

/** The bookings the period a form holds would affect, asked as it changes. */
export type ImpactState =
  | { state: 'idle' }
  | { state: 'loading' }
  | { state: 'ready'; bookings: TeamAffectedBooking[] }
  | { state: 'unavailable' };

/** The prototype's starting values: Annual leave, and 10:00 to 11:00. */
export const newLeave = (): LeaveForm => ({ firstDay: null, lastDay: null, type: 'annual', note: '' });
export const newBlock = (): BlockForm => ({ date: null, starts: '10:00', finishes: '11:00', label: '' });

/**
 * The leave with its first day chosen (F06-S03 AC-15, CR-026): an empty last day, or one before the new first day, becomes
 * that day, so a one-day leave takes one choice; a later last day already chosen is kept.
 */
export function withFirstDay(form: LeaveForm, firstDay: string): LeaveForm {
  const lastDay = form.lastDay === null || form.lastDay < firstDay ? firstDay : form.lastDay;
  return { ...form, firstDay, lastDay };
}

/** Whether the form holds anything to lose. */
export function leaveIsEdited(form: LeaveForm): boolean {
  const empty = newLeave();
  return form.firstDay !== null || form.lastDay !== null || form.type !== empty.type || form.note !== '';
}

export function blockIsEdited(form: BlockForm): boolean {
  const empty = newBlock();
  return form.date !== null || form.starts !== empty.starts || form.finishes !== empty.finishes || form.label !== '';
}

/** The first rule the leave breaks, in the order the prototype's `lvSave` says them; said when saving. */
export function leaveProblem(form: LeaveForm): LeaveRefusal | null {
  if (!form.firstDay || !form.lastDay) return 'daysMissing';
  if (form.lastDay < form.firstDay) return 'lastBeforeFirst';
  return null;
}

/** The first rule the block breaks, in the order the prototype's `blSave` says them; said when saving. */
export function blockProblem(form: BlockForm): BlockRefusal | null {
  if (!form.date) return 'dateMissing';
  if (form.finishes <= form.starts) return 'endAfterStart';
  const length = [...form.label.trim()].length;
  if (length < LABEL_MIN || length > LABEL_MAX) return 'label';
  return null;
}

/** The rule the API's fields name for leave; the days first, as the form says them. */
export function leaveRefusalOfFields(fields: string[], form: LeaveForm): LeaveRefusal | null {
  if (fields.includes('firstDay') || fields.includes('lastDay'))
    return form.firstDay && form.lastDay && form.lastDay < form.firstDay ? 'lastBeforeFirst' : 'daysRule';
  return fields.includes('note') ? 'note' : null;
}

/** The rule the API's fields name for a block. */
export function blockRefusalOfFields(fields: string[]): BlockRefusal | null {
  if (fields.includes('date')) return 'dateRule';
  if (fields.includes('starts') || fields.includes('finishes')) return 'endAfterStart';
  return fields.includes('label') ? 'label' : null;
}

/** Leave as the API takes it. */
export function leaveSaveOf(form: LeaveForm) {
  return {
    firstDay: form.firstDay,
    lastDay: form.lastDay,
    type: form.type,
    note: form.note.trim() === '' ? null : form.note.trim(),
  };
}

/** A block as the API takes it. */
export function blockSaveOf(form: BlockForm) {
  return { date: form.date, starts: form.starts, finishes: form.finishes, label: form.label.trim() };
}

/** Whether the period is one to ask about: the same rules a save checks, so that a broken one asks nothing. */
export const leaveAsksImpact = (form: LeaveForm) => leaveProblem(form) === null;
export const blockAsksImpact = (form: BlockForm) => Boolean(form.date) && form.finishes > form.starts;

/** The list after leave is added: in date order, as the API lists it. */
export function withLeave(list: MemberLeave[], leave: MemberLeave): MemberLeave[] {
  return [...list, leave].sort((a, b) => a.firstDay.localeCompare(b.firstDay));
}

/** The list after a block is added: in date and time order, as the API lists it. */
export function withBlock(list: MemberBlock[], block: MemberBlock): MemberBlock[] {
  return [...list, block].sort((a, b) => a.date.localeCompare(b.date) || a.starts.localeCompare(b.starts));
}
