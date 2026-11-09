import { describe, expect, it } from 'vitest';
import type { BookingDetail } from '../types';
import { actorOf, changeOf, explanationOf, newestFirst, offeredActions } from './appointment-rules';

function detail(
  state: BookingDetail['booking']['state'],
  allowedActions: BookingDetail['allowedActions'],
  day = '2086-06-15',
): BookingDetail {
  return {
    booking: {
      id: 'b',
      reference: 'K7M-4QX',
      state,
      startsAt: `${day}T10:00:00+05:30`,
      endsAt: `${day}T11:00:00+05:30`,
      holdExpiresAt: null,
      member: { id: 'm', name: 'Sanduni' },
      service: { id: 's', name: 'Cut', variantId: null, variantName: null },
      customer: { name: 'Amaya', mobile: '077 123 4567' },
      channel: 'phone',
      priceMinor: 450000,
      currency: 'LKR',
      createdAt: `${day}T08:00:00+05:30`,
    },
    history: [],
    allowedActions,
  };
}

describe('the appointment sheet (F07-S03 AC-7)', () => {
  it("offers exactly what the API allows, in the prototype's order, never confirm", () => {
    expect(offeredActions(['cancel', 'no-show', 'arrive', 'confirm'])).toEqual(['arrive', 'no-show', 'cancel']);
    expect(offeredActions(['confirm', 'release'])).toEqual(['release']);
    expect(offeredActions([])).toEqual([]);
  });

  it('explains a confirmed booking by its day and the start', () => {
    expect(explanationOf(detail('confirmed', ['arrive', 'cancel'], '2086-06-16'), '2086-06-15')).toBe('arriveOnDay');
    expect(explanationOf(detail('confirmed', ['arrive', 'cancel']), '2086-06-15')).toBe('noShowAfterStart');
    expect(explanationOf(detail('confirmed', ['arrive', 'no-show']), '2086-06-15')).toBeNull();
  });

  it('explains every other state as the prototype does', () => {
    expect(explanationOf(detail('arrived', ['start']), '2086-06-15')).toBe('noCancelAfterArrival');
    expect(explanationOf(detail('in_service', []), '2086-06-15')).toBe('completedAtCheckout');
    expect(explanationOf(detail('no_show', ['undo-no-show']), '2086-06-15')).toBe('undoToday');
    expect(explanationOf(detail('no_show', []), '2086-06-16')).toBeNull();
    expect(explanationOf(detail('cancelled', []), '2086-06-15')).toBe('cancelledFinal');
    expect(explanationOf(detail('held', ['confirm', 'release']), '2086-06-15')).toBe('held');
  });

  it('words each change by where it went and from where', () => {
    expect(changeOf({ from: null, to: 'held' })).toBe('held');
    expect(changeOf({ from: 'held', to: 'confirmed' })).toBe('confirmed');
    expect(changeOf({ from: 'no_show', to: 'confirmed' })).toBe('noShowUndone');
    expect(changeOf({ from: 'arrived', to: 'in_service' })).toBe('started');
    expect(changeOf({ from: 'pending_payment', to: 'held' })).toBe('paymentReturned');
    expect(changeOf({ from: 'in_service', to: 'completed' })).toBe('completed');
  });

  it('says who: the label, a team member no longer known, or the system (decision 111)', () => {
    expect(actorOf({ by: { kind: 'staff', label: 'dilani@alphasalon.lk' } })).toEqual({
      kind: 'label',
      label: 'dilani@alphasalon.lk',
    });
    expect(actorOf({ by: { kind: 'staff', label: null } })).toEqual({ kind: 'teamMember' });
    expect(actorOf({ by: { kind: 'system', label: null } })).toEqual({ kind: 'system' });
  });

  it("lists the history newest first without changing the API's answer", () => {
    const history = [{ to: 'held' }, { to: 'confirmed' }] as BookingDetail['history'];
    expect(newestFirst(history).map((h) => h.to)).toEqual(['confirmed', 'held']);
    expect(history[0]!.to).toBe('held');
  });
});
