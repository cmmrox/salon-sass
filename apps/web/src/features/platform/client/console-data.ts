'use client';
import type { components } from '@salon/api-contracts/platform';
import { useRouter } from 'next/navigation';
import { useCallback, useState } from 'react';
import { useQueryClient } from '@tanstack/react-query';
import { moduleData, useDataScope, useReadFailure } from '@/lib/api-core/data-layer';
import { consoleSession, type RequestFailure } from '@/lib/api-core/request';
import type { OperatorPrincipal } from '../types';

type Schemas = components['schemas'];

/** A page of a console directory as the API answers it. */
export type DirectoryPage<T> = { items: T[]; page: number; size: number; total: number };

/** A page of a list: which page, how many, and for the directories the search typed. */
type PageQuery = { page: number; size: number };
type SalonPageQuery = PageQuery & { id: string };

/** The console's BFF reads (`/operator-web/{action}`, F03, F04-S03), each with its query and answer. */
export type ConsoleReads = {
  principal: { answer: OperatorPrincipal };
  salons: { query: PageQuery & { q?: string }; answer: DirectoryPage<Schemas['SalonSummary']> };
  plans: { query: PageQuery & { q?: string }; answer: DirectoryPage<Schemas['PlanDefinition']> };
  salon: { query: { id: string }; answer: Schemas['SalonDetail'] };
  plan: { query: { id: string }; answer: Schemas['PlanDefinition'] };
  subscription: { query: { id: string }; answer: Schemas['SubscriptionView'] };
  invoices: { query: SalonPageQuery; answer: DirectoryPage<Schemas['InvoiceSummary']> };
  lifecycle: { query: SalonPageQuery; answer: Schemas['LifecyclePage'] };
  export: { query: { id: string }; answer: Schemas['ExportView'] };
  'support-sessions': { query: PageQuery & { state?: 'active' | 'ended' }; answer: Schemas['SupportSessionPage'] };
  'holiday-years': { answer: Schemas['HolidayYearList'] };
  'holiday-year': { query: { year: number }; answer: Schemas['HolidayYearDetail'] };
};

type Body = Record<string, unknown>;

/** The console's BFF changes, each with what it answers. The request is rebuilt on the server from its own fields. */
export type ConsoleChanges = {
  'plan-create': { body: Body; answer: Schemas['PlanDefinition'] };
  'salon-create': { body: Body; answer: Schemas['SalonCreated'] };
  'subscription-start': { body: Body; answer: unknown };
  'subscription-change': { body: Body; answer: unknown };
  'plan-move': { body: Body; answer: unknown };
  'payment-record': { body: Body; answer: Schemas['PaymentView'] };
  'salon-suspend': { body: Body; answer: unknown };
  'salon-reactivate': { body: Body; answer: unknown };
  'salon-close': { body: Body; answer: unknown };
  'support-start': { body: Body; answer: Schemas['SupportEntry'] };
  'support-entry-code': { body: Body; answer: Schemas['SupportEntry'] };
  'support-end': { body: Body; answer: unknown };
  /** A year's calendar read from a CSV file, sent as a form (F04-S03). */
  'holiday-import': { body: FormData; answer: Schemas['HolidayYearDetail'] };
  'holiday-day-add': { body: Body; answer: Schemas['HolidayYearDetail'] };
  'holiday-day-change': { body: Body; answer: Schemas['HolidayYearDetail'] };
  'holiday-day-remove': { body: Body; answer: Schemas['HolidayYearDetail'] };
  'holiday-publish': { body: Body; answer: Schemas['HolidayYearDetail'] };
};

const operator = moduleData<ConsoleReads, ConsoleChanges>('console');

type QueryOf<A extends keyof ConsoleReads> = ConsoleReads[A] extends { query: infer Q } ? Q : undefined;

/** Leaves a console session the API no longer accepts: the operator signs in again. */
function useLeaveEnded() {
  const router = useRouter();
  return (failure: RequestFailure) => {
    if (failure.code !== 'invalid_session') return;
    router.replace('/console/sign-in');
    router.refresh();
  };
}

/**
 * One console read on open: its answer, whether it is being read, and why it failed. A failure is not shown while it
 * is read again (as the screens always cleared it when they asked again); an ended session goes to sign-in.
 */
export function useConsoleRead<A extends keyof ConsoleReads & string>(
  action: A,
  query?: QueryOf<A>,
  options: { enabled?: boolean } = {},
) {
  const leave = useLeaveEnded();
  const read = operator.useRead(consoleSession, action, query as never, options);
  useReadFailure([read], leave);
  const failure = read.error && !read.isFetching && read.error.code !== 'invalid_session' ? read.error : null;
  return {
    data: read.data ?? null,
    /** The failure as it stands, also while the read is asked again (`not_found` may be an answer, not a failure). */
    error: read.error,
    loading: read.isFetching,
    failure,
    load: () => void read.refetch(),
  };
}

/** The console reads that are paged in place. */
type PagedAction = 'salons' | 'plans' | 'invoices' | 'lifecycle' | 'support-sessions';

/**
 * A console list paged in place: the page (and search) asked for, and the page on screen, which moves only once the
 * new page has arrived, as it always has. `queryOf` makes the read's query from the page and search (a filter, for
 * a list that has one instead, starting at `options.search`); a failure is
 * handed to `onFailure` when given, else an ended session goes to sign-in and any other failure is returned.
 */
export function useConsolePages<A extends PagedAction>(
  action: A,
  queryOf: (page: number, search: string) => QueryOf<A>,
  options: { onFailure?: (failure: RequestFailure) => void; search?: string } = {},
) {
  const leave = useLeaveEnded();
  const [asked, setAsked] = useState({ page: 0, search: options.search ?? '' });
  const read = operator.useRead(consoleSession, action, queryOf(asked.page, asked.search) as never, {
    keepPrevious: true,
  });
  useReadFailure([read], options.onFailure ?? leave);
  const fresh = read.data && !read.isPlaceholderData ? (read.data as ConsoleReads[A]['answer']) : null;
  const [shown, setShown] = useState<{ data: ConsoleReads[A]['answer']; page: number; search: string } | null>(null);
  if (fresh && fresh !== shown?.data) setShown({ data: fresh, page: asked.page, search: asked.search });
  const failure =
    !options.onFailure && read.error && !read.isFetching && read.error.code !== 'invalid_session' ? read.error : null;
  const { refetch } = read;
  /** Asks for a page and search: the same question is read again, another one replaces it once it answers. */
  const load = useCallback(
    (page: number, search = asked.search) => {
      if (page === asked.page && search === asked.search) void refetch();
      else setAsked({ page, search });
    },
    [asked, refetch],
  );
  return {
    data: shown?.data ?? null,
    page: shown?.page ?? 0,
    search: shown?.search ?? '',
    loading: read.isFetching,
    failure,
    load,
  };
}

/** Puts a change's answer in place of a read's: a holiday year as the change left it. */
export function useSetConsoleAnswer() {
  const client = useQueryClient();
  const scope = useDataScope();
  return <A extends keyof ConsoleReads & string>(action: A, query: QueryOf<A>, answer: ConsoleReads[A]['answer']) =>
    client.setQueryData(operator.keyOf(scope, consoleSession, action, query as never), answer);
}

/** The console's changes, each made once and typed by its action. */
export const useConsoleChanges = () => operator.useChanges(consoleSession);
