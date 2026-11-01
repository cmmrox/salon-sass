import 'server-only';

import { NextResponse, type NextRequest } from 'next/server';

import { readBoundedBytes } from '@/lib/http/bounded-json';

import { platformClient } from './client';

type Client = NonNullable<ReturnType<typeof platformClient>>;
type Options = { headers: Record<string, string>; signal: AbortSignal };
type Problem = (code: string, status: number, extra?: Record<string, unknown>) => NextResponse;
type Output = (body: unknown) => NextResponse;

/** The console's holiday calendar reads (F04-S03). */
export const HOLIDAY_READS = ['holiday-years', 'holiday-year'];

/** Its changes: import, single-day edits and publication. Each is the Admin's; the API checks the role. */
export const HOLIDAY_MUTATIONS = [
  'holiday-import',
  'holiday-day-add',
  'holiday-day-change',
  'holiday-day-remove',
  'holiday-publish',
];

/** A year's CSV is at most 64 KB; this leaves room for the multipart wrapping and nothing like a large upload. */
const IMPORT_LIMIT = 128 * 1024;

const YEAR = /^\d{4}$/;
const DATE = /^\d{4}-\d{2}-\d{2}$/;

/**
 * A refusal passed on with what the console shows of it: each field, and for an import each bad line's number
 * and reason (the API's own words, which name no submitted value but a date the file repeats).
 */
function refused(
  problem: Problem,
  r: { error?: { code?: string; errors?: { field?: string; detail?: string; line?: number }[] }; response: Response },
) {
  return problem(r.error?.code ?? 'service_unavailable', r.response.status, {
    errors: (r.error?.errors ?? []).map((e) => ({
      field: e.field,
      ...(typeof e.line === 'number' ? { line: e.line, detail: e.detail } : {}),
    })),
  });
}

/**
 * Serves one holiday calendar action of the console BFF, after the operator's session is checked. Every value
 * goes on only through the contract's own fields; the year and the date are checked for their form here, and the
 * API decides the rest.
 */
export async function handleHoliday(
  request: NextRequest,
  action: string,
  client: Client,
  options: Options,
  body: Record<string, unknown>,
  problem: Problem,
  output: Output,
): Promise<NextResponse> {
  const text = (name: string) => (typeof body[name] === 'string' ? (body[name] as string) : '');
  const year = action === 'holiday-year' ? (request.nextUrl.searchParams.get('year') ?? '') : text('year');
  if (action === 'holiday-years') {
    const r = await client.GET('/api/v1/operator/holiday-calendars', options);
    return r.data ? output(r.data) : refused(problem, r);
  }
  if (action === 'holiday-import') return importFile(request, client, options, problem, output);
  if (!YEAR.test(year)) return problem('bad_request', 400, { errors: [{ field: 'year' }] });
  const path = { year };
  switch (action) {
    case 'holiday-year': {
      const r = await client.GET('/api/v1/operator/holiday-calendars/{year}', { ...options, params: { path } });
      return r.data ? output(r.data) : refused(problem, r);
    }
    case 'holiday-day-add': {
      const r = await client.POST('/api/v1/operator/holiday-calendars/{year}/days', {
        ...options,
        params: { path },
        body: { date: text('date'), name: text('name'), kind: text('kind') as 'poya' },
      });
      return r.data ? output(r.data) : refused(problem, r);
    }
    case 'holiday-day-change':
    case 'holiday-day-remove': {
      const date = text('date');
      if (!DATE.test(date)) return problem('bad_request', 400, { errors: [{ field: 'date' }] });
      const r =
        action === 'holiday-day-change'
          ? await client.PUT('/api/v1/operator/holiday-calendars/{year}/days/{date}', {
              ...options,
              params: { path: { year, date } },
              body: { name: text('name'), kind: text('kind') as 'poya' },
            })
          : await client.DELETE('/api/v1/operator/holiday-calendars/{year}/days/{date}', {
              ...options,
              params: { path: { year, date } },
            });
      return r.data ? output(r.data) : refused(problem, r);
    }
    case 'holiday-publish': {
      const r = await client.POST('/api/v1/operator/holiday-calendars/{year}/publication', {
        ...options,
        params: { path },
        body: { version: body.version as number },
      });
      return r.data ? output(r.data) : refused(problem, r);
    }
    default:
      return problem('not_found', 404);
  }
}

/** The browser's multipart form, read through a byte count; only its year and file go on. */
async function importFile(
  request: NextRequest,
  client: Client,
  options: Options,
  problem: Problem,
  output: Output,
): Promise<NextResponse> {
  const bytes = await readBoundedBytes(request, IMPORT_LIMIT);
  if (bytes === null) return problem('request_rejected', 413);
  let form: FormData;
  try {
    form = await new Response(bytes, {
      headers: { 'Content-Type': request.headers.get('content-type') ?? '' },
    }).formData();
  } catch {
    return problem('bad_request', 400, { errors: [{ field: 'file' }] });
  }
  const year = form.get('year');
  const file = form.get('file');
  if (typeof year !== 'string' || !YEAR.test(year)) return problem('bad_request', 400, { errors: [{ field: 'year' }] });
  if (!(file instanceof Blob)) return problem('bad_request', 400, { errors: [{ field: 'file' }] });
  const upstream = new FormData();
  upstream.append('file', file, 'calendar.csv');
  const r = await client.POST('/api/v1/operator/holiday-calendars/{year}/import', {
    ...options,
    params: { path: { year } },
    body: upstream as unknown as { file: string },
    bodySerializer: (value) => value as unknown as FormData,
  });
  return r.data ? output(r.data) : refused(problem, r);
}
