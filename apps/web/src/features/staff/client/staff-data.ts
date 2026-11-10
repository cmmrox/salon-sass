'use client';
import { moduleData } from '@/lib/api-core/data-layer';
import { workspaceSession, type WorkspaceKind } from '@/lib/api-core/request';
import type {
  AddedBlock,
  AddedDateChange,
  AddedLeave,
  Closure,
  ClosureImpact,
  HolidayYear,
  HoursSettings,
  MemberSchedule,
  PeriodImpact,
  SalonHolidays,
  SalonOpeningWeek,
  SavedMemberWeek,
  TeamMember,
  TeamMemberList,
  TeamSignInAccountList,
} from '../types';

/** The staff module's BFF reads: the team and its schedules (F06), the salon's hours and closures (F04-S03). */
export type StaffReads = {
  'team-members': { answer: TeamMemberList };
  'team-sign-in-accounts': { answer: TeamSignInAccountList };
  'team-schedule': { query: { memberId: string }; answer: MemberSchedule };
  'team-opening-hours': { answer: SalonOpeningWeek };
  'team-leave-affected': { query: { memberId: string; firstDay: string; lastDay: string }; answer: PeriodImpact };
  'team-block-affected': {
    query: { memberId: string; date: string; starts: string; finishes: string };
    answer: PeriodImpact;
  };
  hours: { answer: HoursSettings };
  holidays: { answer: SalonHolidays };
  closures: { answer: Closure[] };
  'closure-impact': { query: { date: string }; answer: ClosureImpact };
};

type Body = Record<string, unknown>;

/** The staff module's BFF changes, each with what it answers. The request is rebuilt on the server from its fields. */
export type StaffChanges = {
  'team-member-add': { body: Body; answer: TeamMember };
  'team-member-save': { body: Body; answer: TeamMember };
  'team-member-remove': { body: Body; answer: { ok: true } };
  'team-photo': { body: FormData; answer: TeamMember };
  'team-photo-remove': { body: Body; answer: TeamMember };
  'team-week-save': { body: Body; answer: SavedMemberWeek };
  'team-date-change-add': { body: Body; answer: AddedDateChange };
  'team-date-change-remove': { body: Body; answer: { ok: true } };
  'team-leave-add': { body: Body; answer: AddedLeave };
  'team-leave-remove': { body: Body; answer: { ok: true } };
  'team-block-add': { body: Body; answer: AddedBlock };
  'team-block-remove': { body: Body; answer: { ok: true } };
  'hours-save': { body: Body; answer: HoursSettings };
  'holidays-accept': { body: Body; answer: HolidayYear };
  'holiday-choose': { body: Body; answer: HolidayYear };
  'closure-add': { body: Body; answer: Closure };
  'closure-reopen': { body: Body; answer: { ok: boolean } };
};

const staff = moduleData<StaffReads, StaffChanges>('staff');

/** The salon's team (F06-S01). */
export const useTeamMembers = (kind: WorkspaceKind, enabled = true) =>
  staff.useRead(workspaceSession(kind), 'team-members', undefined, { enabled });

/** The sign-in accounts a person may be linked to, read only by those who manage the team. */
export const useSignInAccounts = (kind: WorkspaceKind, enabled: boolean) =>
  staff.useRead(workspaceSession(kind), 'team-sign-in-accounts', undefined, { enabled });

/** The salon's opening hours as Settings edits them (F04-S03). */
export const useOpeningHours = (kind: WorkspaceKind) => staff.useRead(workspaceSession(kind), 'hours');

/** The salon's public holidays and its choices for them. */
export const useHolidays = (kind: WorkspaceKind) => staff.useRead(workspaceSession(kind), 'holidays');

/** The salon's closures. */
export const useClosures = (kind: WorkspaceKind) => staff.useRead(workspaceSession(kind), 'closures');

/**
 * The staff questions asked when something happens: a person's schedule and the salon's week when their sheet opens
 * (F06-S02), the bookings a period of leave or a block would affect as it is chosen (F06-S03 AC-8), and those a
 * closure would (F04-S03).
 */
export const useStaffAsk = (kind: WorkspaceKind) => staff.useAsk(workspaceSession(kind));

/** Puts a change's answer in place of a read's. */
export const useSetStaffAnswer = (kind: WorkspaceKind) => staff.useSetAnswer(workspaceSession(kind));

/** The staff module's changes, each made once and typed by its action. */
export type StaffChange = ReturnType<typeof staff.useChanges>['run'];
export const useStaffChanges = (kind: WorkspaceKind) => staff.useChanges(workspaceSession(kind));
