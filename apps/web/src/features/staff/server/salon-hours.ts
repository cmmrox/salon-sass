import 'server-only';

import { createApiClient, type components } from '@salon/api-contracts/staff';

import { createServerApiClient } from '@/lib/api-core/client';

type Call = { headers: Record<string, string | undefined>; signal: AbortSignal };
type WeekdayInput = components['schemas']['OpeningWeekdayInput'];
type ChoiceInput = components['schemas']['SalonHolidayChoiceInput'];

const text = (value: unknown): string | null => (typeof value === 'string' ? value : null);
const record = (value: unknown): Record<string, unknown> =>
  typeof value === 'object' && value !== null && !Array.isArray(value) ? (value as Record<string, unknown>) : {};
const list = (value: unknown): unknown[] => (Array.isArray(value) ? value : []);
/**
 * A path segment checked for its form, so nothing else reaches the path; the API decides the rest. A year may
 * come as a number (the tab sends the year the API answered).
 */
const segment = (value: unknown, pattern: RegExp) => {
  const text = typeof value === 'number' && Number.isInteger(value) ? String(value) : value;
  return typeof text === 'string' && pattern.test(text) ? text : '0';
};

const YEAR = /^\d{4}$/;
const DATE = /^\d{4}-\d{2}-\d{2}$/;
const UUID = /^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$/;

function weekday(value: unknown): WeekdayInput {
  const day = record(value);
  return {
    day: (text(day.day) ?? '') as WeekdayInput['day'],
    open: day.open as boolean,
    opens: text(day.opens),
    closes: text(day.closes),
  };
}

function choice(value: unknown): ChoiceInput {
  const each = record(value);
  return {
    date: text(each.date) ?? '',
    choice: (text(each.choice) ?? '') as ChoiceInput['choice'],
    opens: text(each.opens),
    closes: text(each.closes),
  };
}

/**
 * The staff paths the workspace BFF reaches for a settings user, under the salon's hostname (F04-S03): weekly
 * hours, holiday choices and closures. Every request is rebuilt from the contract's own fields; the BFF checks
 * the session and the permission before any of them. Null when the API is not configured.
 */
export function salonHoursFor(hostname: string) {
  const client = createServerApiClient(createApiClient, { hostname });
  if (!client) return null;
  return {
    hours: (call: Call) => client.GET('/api/v1/salon/opening-hours/settings', call),
    saveHours: (call: Call, version: unknown, weekly: unknown) =>
      client.PUT('/api/v1/salon/opening-hours/settings', {
        ...call,
        body: { version: version as number, weekly: list(weekly).map(weekday) },
      }),
    holidays: (call: Call) => client.GET('/api/v1/salon/holidays', call),
    acceptHolidays: (call: Call, year: unknown, version: unknown, choices: unknown) =>
      client.POST('/api/v1/salon/holidays/{year}/acceptance', {
        ...call,
        params: { path: { year: segment(year, YEAR) } },
        body: { version: version as number, choices: list(choices).map(choice) },
      }),
    chooseHoliday: (call: Call, year: unknown, date: unknown, value: unknown) => {
      const picked = choice(value);
      return client.PUT('/api/v1/salon/holidays/{year}/days/{date}', {
        ...call,
        params: { path: { year: segment(year, YEAR), date: segment(date, DATE) } },
        body: { choice: picked.choice, opens: picked.opens, closes: picked.closes },
      });
    },
    closures: (call: Call) => client.GET('/api/v1/salon/closures', call),
    closureImpact: (call: Call, date: string) =>
      client.GET('/api/v1/salon/closures/impact', { ...call, params: { query: { date } } }),
    addClosure: (call: Call, date: unknown, reason: unknown) =>
      client.POST('/api/v1/salon/closures', { ...call, body: { date: text(date) ?? '', reason: text(reason) } }),
    reopenClosure: (call: Call, id: unknown) =>
      client.DELETE('/api/v1/salon/closures/{id}', {
        ...call,
        params: { path: { id: segment(id, UUID) } },
      }),
  };
}
