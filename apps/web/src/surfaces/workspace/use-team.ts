'use client';
import { useRouter } from 'next/navigation';
import { useEffect, useRef, useState } from 'react';
import type { CategoryList, ServiceList } from '@/features/catalogue';
import { IdentityFailure, leaveEndedSession, type Kind } from '@/features/identity';
import { change as changeOnce, read, workspaceSession } from '@/lib/api-core/request';
import {
  memberProblemsOf,
  memberProblemsOfFields,
  memberRequestOf,
  memberValuesOf,
  newMember,
  sameMemberValues,
  type MemberPhotoUpload,
  type MemberProblem,
  type MemberValues,
  type TeamCategory,
  type TeamMember,
  type TeamMemberList,
  type TeamService,
  type TeamSignInAccount,
  type TeamSignInAccountList,
} from '@/features/staff';

/** What the page says above its list: someone else changed it, the API could not be reached, or a change was refused. */
export type TeamNotice = 'conflict' | 'unavailable' | 'refused' | null;

/** Why a change was not made, said in an Alert: a rule of the team rather than a broken field. */
export type TeamRefusal =
  | { reason: 'seat' | 'memberLimit' | 'unchanged' | 'noPhoto' }
  | { reason: 'inUseRemoval'; name: string; count: number }
  | { reason: 'inUseServices'; services: string }
  | null;

/** The passing notices the page announces in a toast. */
export type TeamToast = 'added' | 'saved' | 'removed' | 'photoSaved' | 'photoRemoved';

type SheetState = { member: TeamMember | null; values: MemberValues; apiProblems: MemberProblem[] };

/**
 * The Team page's state (F06-S01): the team, the salon's categories and services for the picker and the cards, the
 * accounts offered for linking, the profile sheet with its checks, the confirmation, and every change, each under the
 * session's cross-tab lock. A 403 on a change makes the page read-only from then on and says so.
 */
export function useTeam(kind: Kind, canManage: boolean, notify: (toast: TeamToast) => void) {
  const router = useRouter();
  const [members, setMembers] = useState<TeamMember[] | null>(null);
  const [categories, setCategories] = useState<TeamCategory[]>([]);
  const [services, setServices] = useState<TeamService[]>([]);
  const [accounts, setAccounts] = useState<TeamSignInAccount[]>([]);
  const [loading, setLoading] = useState(true);
  const [notice, setNotice] = useState<TeamNotice>(null);
  const [refusal, setRefusal] = useState<TeamRefusal>(null);
  const [locked, setLocked] = useState(false);
  const [busy, setBusy] = useState(false);
  // A sheet keeps its last content while it closes, so that focus can go back to what opened it.
  const [sheet, setSheet] = useState<SheetState | null>(null);
  const [sheetOpen, setSheetOpen] = useState(false);
  const [opened, setOpened] = useState(0);
  // The same count, read when a change made from a sheet answers: its result belongs to the opening that sent it.
  const opening = useRef(0);
  const [photoUpload, setPhotoUpload] = useState<MemberPhotoUpload>({ state: 'idle' });
  const [confirming, setConfirming] = useState(false);

  /** A failure the page answers itself; one the caller says in its own words is returned to it. */
  function failed(error: unknown): IdentityFailure | null {
    const failure = error instanceof IdentityFailure ? error : new IdentityFailure('service_unavailable');
    if (failure.code === 'invalid_session') {
      leaveEndedSession(kind, router);
      return null;
    }
    if (['bad_request', 'limit_reached', 'in_use', 'invalid_state', 'entitlement_exceeded'].includes(failure.code))
      return failure;
    if (failure.code === 'conflict' || failure.code === 'not_found') setNotice('conflict');
    else if (failure.code === 'service_unavailable' || failure.code === 'browser_unsupported') setNotice('unavailable');
    else {
      setNotice('refused');
      setLocked(true);
      setSheetOpen(false);
    }
    return null;
  }

  /** Reads the team and what its cards and picker name; null when they could not be read (the page says why). */
  async function load(): Promise<TeamMember[] | null> {
    setLoading(true);
    try {
      const [team, nextCategories, nextServices, nextAccounts] = await Promise.all([
        read<TeamMemberList>(workspaceSession(kind), 'team-members'),
        read<CategoryList>(workspaceSession(kind), 'catalogue-categories'),
        read<ServiceList>(workspaceSession(kind), 'catalogue-services'),
        canManage
          ? read<TeamSignInAccountList>(workspaceSession(kind), 'team-sign-in-accounts').then((answer) => answer.items)
          : Promise.resolve([]),
      ]);
      setMembers(team.items);
      setCategories(nextCategories.items.map(({ id, name }) => ({ id, name })));
      setServices(nextServices.items.map(({ id, name, categoryId }) => ({ id, name, categoryId })));
      setAccounts(nextAccounts);
      setNotice((current) => (current === 'refused' ? current : null));
      return team.items;
    } catch (error) {
      failed(error);
      return null;
    } finally {
      setLoading(false);
    }
  }

  /** Reads again after a conflict, and an open sheet with it: its person as they are now, edits dropped. */
  async function reload() {
    setRefusal(null);
    const fresh = await load();
    if (!fresh) return;
    if (sheetOpen && sheet?.member) {
      const now = fresh.find((each) => each.id === sheet.member?.id);
      if (now) setSheet({ member: now, values: memberValuesOf(now), apiProblems: [] });
      else setSheetOpen(false);
    }
  }

  useEffect(() => {
    let active = true;
    Promise.resolve().then(() => {
      if (active) void load();
    });
    return () => {
      active = false;
    };
    // Loaded once when the page opens; reload is the person's choice after a conflict.
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, []);

  /** A change made under the session's lock. */
  async function change<T>(action: string, body: unknown): Promise<T> {
    return changeOnce<T>(workspaceSession(kind), action, body);
  }

  /** What a refusal says, from what the API named: the services and the number come from its `errors` items. */
  function refusalOf(failure: IdentityFailure, name: string): TeamRefusal {
    switch (failure.code) {
      case 'entitlement_exceeded':
        return { reason: 'seat' };
      case 'limit_reached':
        return { reason: 'memberLimit' };
      case 'invalid_state':
        return { reason: 'unchanged' };
      case 'in_use': {
        const count = failure.problems.find((problem) => problem.field === 'upcomingBookings');
        if (count) return { reason: 'inUseRemoval', name, count: Number(count.detail) };
        return {
          reason: 'inUseServices',
          services: failure.problems.map((problem) => problem.detail).join(', '),
        };
      }
      default:
        return null;
    }
  }

  const clientProblems = sheet ? memberProblemsOf(sheet.values) : [];
  const shownProblems = sheet ? [...new Set([...clientProblems, ...sheet.apiProblems])] : [];
  const sheetDirty = Boolean(
    sheetOpen && sheet && !sameMemberValues(sheet.values, sheet.member ? memberValuesOf(sheet.member) : newMember()),
  );

  function openSheet(next: SheetState) {
    setRefusal(null);
    setPhotoUpload({ state: 'idle' });
    setSheet(next);
    opening.current += 1;
    setOpened(opening.current);
    setSheetOpen(true);
  }

  async function save() {
    if (!sheet || clientProblems.length > 0) return;
    const editing = sheet.member;
    setBusy(true);
    setRefusal(null);
    try {
      const request = memberRequestOf(sheet.values);
      await (editing
        ? change<TeamMember>('team-member-save', { ...request, id: editing.id, version: editing.version })
        : change<TeamMember>('team-member-add', request));
      notify(editing ? 'saved' : 'added');
      setSheetOpen(false);
      await load();
    } catch (error) {
      const left = failed(error);
      if (left?.code === 'bad_request') {
        const apiProblems = memberProblemsOfFields(left.fields);
        setSheet((current) => (current ? { ...current, apiProblems } : current));
        // The account or the services the sheet offered have moved on: offer what there is now.
        if (apiProblems.includes('signInAccountId') || apiProblems.includes('services')) await load();
      } else if (left) setRefusal(refusalOf(left, sheet.values.name.trim()));
    }
    setBusy(false);
  }

  async function confirmRemove() {
    const member = sheet?.member;
    setConfirming(false);
    if (!member) return;
    setBusy(true);
    setRefusal(null);
    try {
      await change<{ ok: true }>('team-member-remove', { id: member.id, version: member.version });
      notify('removed');
      setSheetOpen(false);
      await load();
    } catch (error) {
      const left = failed(error);
      if (left) setRefusal(refusalOf(left, member.name));
    }
    setBusy(false);
  }

  /** The sheet's person with a new photo and version, kept if the same opening is still on screen. */
  function photoChanged(started: number, saved: TeamMember) {
    if (opening.current !== started) return;
    setSheet((current) => (current ? { ...current, member: saved } : current));
  }

  async function uploadPhoto(file: File) {
    const member = sheet?.member;
    if (!member) return;
    const started = opening.current;
    const stillOpen = () => opening.current === started;
    setPhotoUpload({ state: 'uploading' });
    const form = new FormData();
    form.append('memberId', member.id);
    form.append('file', file);
    try {
      const saved = await change<TeamMember>('team-photo', form);
      photoChanged(started, saved);
      if (stillOpen()) setPhotoUpload({ state: 'idle' });
      notify('photoSaved');
      await load();
    } catch (error) {
      const failure = error instanceof IdentityFailure ? error : new IdentityFailure('service_unavailable');
      if (['bad_request', 'request_rejected', 'service_unavailable'].includes(failure.code)) {
        if (stillOpen()) setPhotoUpload({ state: 'refused', reason: failure.code });
      } else {
        if (stillOpen()) setPhotoUpload({ state: 'idle' });
        failed(error);
      }
    }
  }

  async function removePhoto() {
    const member = sheet?.member;
    if (!member) return;
    const started = opening.current;
    setBusy(true);
    setRefusal(null);
    try {
      const saved = await change<TeamMember>('team-photo-remove', { id: member.id });
      photoChanged(started, saved);
      notify('photoRemoved');
      await load();
    } catch (error) {
      if (failed(error)?.code === 'invalid_state') setRefusal({ reason: 'noPhoto' });
    }
    setBusy(false);
  }

  /** The accounts the open sheet offers: those no other person is linked to (AC-5). */
  const offeredAccounts = accounts.filter(
    (account) => account.memberId === null || account.memberId === sheet?.member?.id,
  );

  return {
    members,
    categories,
    services,
    offeredAccounts,
    loading,
    notice,
    refusal,
    locked,
    busy,
    sheet,
    sheetOpen,
    opened,
    photoUpload,
    confirming,
    shownProblems,
    sheetDirty,
    reload,
    save,
    uploadPhoto,
    removePhoto,
    confirmRemove,
    newPerson: () => openSheet({ member: null, values: newMember(), apiProblems: [] }),
    editPerson: (member: TeamMember) => openSheet({ member, values: memberValuesOf(member), apiProblems: [] }),
    changeValues: (values: MemberValues) => {
      setSheet((current) => (current ? { ...current, values, apiProblems: [] } : current));
      setRefusal(null);
    },
    closeSheet: () => {
      setSheetOpen(false);
      setNotice((current) => (current === 'conflict' ? null : current));
      setRefusal(null);
    },
    askRemove: () => setConfirming(true),
    cancelRemove: () => setConfirming(false),
  };
}
