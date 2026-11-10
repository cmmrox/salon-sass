import { describe, expect, it } from 'vitest';
import {
  aYearOn,
  countdownText,
  dateInZone,
  minutesLeft,
  plusDays,
  secondsLeft,
  textOfDate,
  dateOfText,
  whenOf,
} from './booking-time';

describe('booking times (F07-S03)', () => {
  it("reads a booking on the salon's own clock, as the API writes it with the salon's offset", () => {
    expect(whenOf({ startsAt: '2086-06-15T23:30:00+05:30', endsAt: '2086-06-16T00:30:00+05:30' })).toEqual({
      date: '2086-06-15',
      starts: '23:30',
      ends: '00:30',
    });
  });

  it('counts down whole seconds to an instant, never below zero', () => {
    const at = Date.parse('2086-06-15T10:00:00+05:30');
    expect(secondsLeft('2086-06-15T10:10:00+05:30', at)).toBe(600);
    expect(secondsLeft('2086-06-15T10:10:00+05:30', at + 600_500)).toBe(0);
    expect(secondsLeft('2086-06-15T10:00:01+05:30', at + 1)).toBe(1);
  });

  it('shows the countdown as m:ss', () => {
    expect(countdownText(521)).toBe('8:41');
    expect(countdownText(60)).toBe('1:00');
    expect(countdownText(9)).toBe('0:09');
  });

  it('changes the announced minutes only as each minute is reached (AC-11)', () => {
    expect([600, 599, 541, 540, 539, 61, 60, 1].map(minutesLeft)).toEqual([10, 10, 10, 9, 9, 2, 1, 1]);
  });

  it("knows the salon's today from an instant", () => {
    expect(dateInZone('Asia/Colombo', Date.parse('2086-06-15T19:00:00Z'))).toBe('2086-06-16');
    expect(dateInZone('Europe/London', Date.parse('2086-06-15T19:00:00Z'))).toBe('2086-06-15');
  });

  it('counts days on and a year on (29 February to 28 February)', () => {
    expect(plusDays('2086-12-31', 1)).toBe('2087-01-01');
    expect(aYearOn('2086-06-15')).toBe('2087-06-15');
    expect(aYearOn('2088-02-29')).toBe('2089-02-28');
  });

  it("turns a date into the Calendar's local midnight and back", () => {
    expect(textOfDate(dateOfText('2086-06-15'))).toBe('2086-06-15');
  });
});
