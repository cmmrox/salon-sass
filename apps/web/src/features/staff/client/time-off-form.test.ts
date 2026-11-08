import { describe, expect, it } from 'vitest';
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
} from './time-off-form';

const leave = (firstDay: string, id = firstDay) => ({ id, firstDay, lastDay: firstDay, bookingsToResolve: 0 });
const block = (date: string, starts: string) => ({
  id: `${date}-${starts}`,
  date,
  starts,
  finishes: '23:45',
  label: 'Training',
  bookingsToResolve: 0,
});

describe('a leave form (F06-S03 AC-1)', () => {
  const filled = { ...newLeave(), firstDay: '2026-11-12', lastDay: '2026-11-14' };

  it('starts on Annual leave with nothing to lose', () => {
    expect(newLeave().type).toBe('annual');
    expect(leaveIsEdited(newLeave())).toBe(false);
  });

  it.each([[{ firstDay: '2026-11-12' }], [{ lastDay: '2026-11-12' }], [{ type: 'sick' as const }], [{ note: 'A' }]])(
    'holds something to lose once %j is entered',
    (change) => {
      expect(leaveIsEdited({ ...newLeave(), ...change })).toBe(true);
    },
  );

  it('asks for both days, then that the last is not before the first', () => {
    expect(leaveProblem(newLeave())).toBe('daysMissing');
    expect(leaveProblem({ ...newLeave(), firstDay: '2026-11-12' })).toBe('daysMissing');
    expect(leaveProblem({ ...newLeave(), lastDay: '2026-11-12' })).toBe('daysMissing');
    expect(leaveProblem({ ...filled, lastDay: '2026-11-11' })).toBe('lastBeforeFirst');
  });

  it('takes one day alone and a range', () => {
    expect(leaveProblem({ ...filled, lastDay: '2026-11-12' })).toBeNull();
    expect(leaveProblem(filled)).toBeNull();
  });

  it('asks about bookings only for a period a save would take', () => {
    expect(leaveAsksImpact(filled)).toBe(true);
    expect(leaveAsksImpact(newLeave())).toBe(false);
    expect(leaveAsksImpact({ ...filled, lastDay: '2026-11-11' })).toBe(false);
  });

  it('sends the note trimmed, and none for blank', () => {
    expect(leaveSaveOf({ ...filled, note: '  Trip  ' })).toEqual({
      firstDay: '2026-11-12',
      lastDay: '2026-11-14',
      type: 'annual',
      note: 'Trip',
    });
    expect(leaveSaveOf({ ...filled, note: '   ' }).note).toBeNull();
  });

  it('says what the API named', () => {
    expect(leaveRefusalOfFields(['firstDay'], filled)).toBe('daysRule');
    expect(leaveRefusalOfFields(['lastDay'], filled)).toBe('daysRule');
    expect(leaveRefusalOfFields(['lastDay'], { ...filled, lastDay: '2026-11-11' })).toBe('lastBeforeFirst');
    expect(leaveRefusalOfFields(['note'], filled)).toBe('note');
    expect(leaveRefusalOfFields(['other'], filled)).toBeNull();
  });

  it('lists in date order after one is added', () => {
    expect(withLeave([leave('2026-11-12'), leave('2026-12-01')], leave('2026-11-20')).map((each) => each.id)).toEqual([
      '2026-11-12',
      '2026-11-20',
      '2026-12-01',
    ]);
  });
});

describe('a block form (F06-S03 AC-4)', () => {
  const filled = { ...newBlock(), date: '2026-11-12', label: 'Training' };

  it('starts at 10:00 to 11:00 with nothing to lose', () => {
    expect(newBlock()).toMatchObject({ starts: '10:00', finishes: '11:00' });
    expect(blockIsEdited(newBlock())).toBe(false);
  });

  it.each([[{ date: '2026-11-12' }], [{ starts: '09:00' }], [{ finishes: '12:00' }], [{ label: 'A' }]])(
    'holds something to lose once %j is entered',
    (change) => {
      expect(blockIsEdited({ ...newBlock(), ...change })).toBe(true);
    },
  );

  it('says its rules in the prototype order', () => {
    expect(blockProblem({ ...filled, date: null, starts: '11:00', label: '' })).toBe('dateMissing');
    expect(blockProblem({ ...filled, starts: '11:00', label: '' })).toBe('endAfterStart');
    expect(blockProblem({ ...filled, starts: '10:00', finishes: '10:00' })).toBe('endAfterStart');
    expect(blockProblem({ ...filled, label: '' })).toBe('label');
    expect(blockProblem(filled)).toBeNull();
  });

  it('counts the label trimmed, from 2 to 40', () => {
    expect(blockProblem({ ...filled, label: ' A ' })).toBe('label');
    expect(blockProblem({ ...filled, label: ' AB ' })).toBeNull();
    expect(blockProblem({ ...filled, label: 'x'.repeat(40) })).toBeNull();
    expect(blockProblem({ ...filled, label: 'x'.repeat(41) })).toBe('label');
  });

  it('asks about bookings with a date and a finish after the start, whatever the label', () => {
    expect(blockAsksImpact({ ...filled, label: '' })).toBe(true);
    expect(blockAsksImpact({ ...filled, date: null })).toBe(false);
    expect(blockAsksImpact({ ...filled, starts: '11:00' })).toBe(false);
  });

  it('sends the label trimmed', () => {
    expect(blockSaveOf({ ...filled, label: '  Training  ' }).label).toBe('Training');
  });

  it('says what the API named', () => {
    expect(blockRefusalOfFields(['date', 'label'])).toBe('dateRule');
    expect(blockRefusalOfFields(['finishes'])).toBe('endAfterStart');
    expect(blockRefusalOfFields(['starts'])).toBe('endAfterStart');
    expect(blockRefusalOfFields(['label'])).toBe('label');
    expect(blockRefusalOfFields(['other'])).toBeNull();
  });

  it('lists in date and time order after one is added', () => {
    expect(
      withBlock([block('2026-11-12', '09:00'), block('2026-11-13', '09:00')], block('2026-11-12', '15:00')).map(
        (each) => each.id,
      ),
    ).toEqual(['2026-11-12-09:00', '2026-11-12-15:00', '2026-11-13-09:00']);
  });
});
