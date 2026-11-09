import { describe, expect, it } from 'vitest';
import { filtered, listRequest, queryOf, searchOf, type BookingsQuery } from './bookings-query';

const ID = '3f1c6a52-8f0e-4c3b-9a0d-1b2c3d4e5f60';
const base: BookingsQuery = { day: null, q: '', status: 'all', professional: null, page: 1, booking: null };

describe('the Bookings page address (F07-S03 AC-3)', () => {
  it('reads the day, the search, the status, the professional, the page and the open booking', () => {
    const query = queryOf(
      new URLSearchParams(`day=2086-06-16&q=Amaya&status=no_show&professional=${ID}&page=3&booking=${ID}`),
    );
    expect(query).toEqual({ day: '2086-06-16', q: 'Amaya', status: 'no_show', professional: ID, page: 3, booking: ID });
  });

  it('leaves anything malformed at its default', () => {
    expect(
      queryOf(new URLSearchParams('day=16-06-2086&status=pending_payment&professional=x&page=0&booking=1')),
    ).toEqual(base);
    expect(queryOf(new URLSearchParams('day=2086-02-30x&page=1.5'))).toEqual(base);
  });

  it('cuts a search to the 60 characters the API takes', () => {
    expect(queryOf(new URLSearchParams(`q=${'a'.repeat(70)}`)).q).toHaveLength(60);
  });

  it('writes defaults out of the address, so a reload of the plain page is the plain page', () => {
    expect(searchOf(base)).toBe('');
    const full = { day: '2086-06-16', q: 'K7M', status: 'held', professional: ID, page: 2, booking: ID } as const;
    expect(queryOf(new URLSearchParams(searchOf(full).slice(1)))).toEqual(full);
  });

  it('says whether anything narrows the day', () => {
    expect(filtered(base)).toBe(false);
    expect(filtered({ ...base, q: '  ' })).toBe(false);
    expect(filtered({ ...base, status: 'held' })).toBe(true);
    expect(filtered({ ...base, professional: ID })).toBe(true);
  });
});

describe('the list read', () => {
  it('asks for one day, its state, professional, search and page from 0', () => {
    const request = listRequest(
      { ...base, q: ' 077 123 ', status: 'in_service', professional: ID, page: 2 },
      '2086-06-15',
      true,
    );
    const [action, query] = request.split('?') as [string, string];
    expect(action).toBe('bookings');
    expect(Object.fromEntries(new URLSearchParams(query))).toEqual({
      from: '2086-06-15',
      to: '2086-06-15',
      page: '1',
      size: '50',
      state: 'in_service',
      memberId: ID,
      q: '077 123',
    });
  });

  it('sends no professional for a caller who sees only their own, and no state for all', () => {
    const query = new URLSearchParams(listRequest({ ...base, professional: ID }, '2086-06-15', false).split('?')[1]);
    expect(query.has('memberId')).toBe(false);
    expect(query.has('state')).toBe(false);
    expect(query.has('q')).toBe(false);
  });
});
