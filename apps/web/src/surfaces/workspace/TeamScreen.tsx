'use client';
import { todayInZone } from '@salon/format';
import { useRouter } from 'next/navigation';
import { useTranslations } from 'next-intl';
import { useRef, useState } from 'react';
import { toast } from 'sonner';
import { Alert, AlertDescription, AlertTitle } from '@/components/ui/alert';
import { Button } from '@/components/ui/button';
import { Skeleton } from '@/components/ui/skeleton';
import { Toaster } from '@/components/ui/sonner';
import { LeaveDialog, useLeaveGuard } from '@/features/branding';
import type { Kind } from '@/features/identity';
import {
  AffectedBookings,
  BlockedTimeTab,
  ConfirmRemoveLeave,
  ConfirmRemoveMember,
  DateChangesTab,
  LeaveTab,
  MemberSheet,
  ScheduleSheet,
  TeamCards,
  WeeklyHoursTab,
} from '@/features/staff';
import { TeamFrame } from './TeamFrame';
import { useSchedule, type ScheduleToast } from './use-schedule';
import { useTeam, type TeamToast } from './use-team';

type Props = {
  kind: Kind;
  /** The salon's time zone: its date decides whether a card's leave badge says the year (decision 107). */
  timeZone: string;
  /** Whether this person may change the team (`team.manage`, in a salon that is not read-only). */
  canManage: boolean;
  /** Whether this person may add and remove blocked time (`team.block`, in a salon that is not read-only). */
  canBlock: boolean;
  /** Whether this person may see accounts, with `staff.list`: the tab bar and each card's Sign-in. */
  canSeeAccounts: boolean;
  readOnly: 'support' | 'salon' | null;
};

/**
 * The Team page's Team tab (F06-S01, the prototype's `aStaff`): the summary and + Add team member, the cards, and the
 * profile sheet and confirmation they open. Loading, empty, field-error, conflict, refused and unavailable states;
 * leaving the sheet with unsaved edits asks first. Those who may not change the team read it.
 */
export function TeamScreen({ kind, timeZone, canManage, canBlock, canSeeAccounts, readOnly }: Props) {
  const t = useTranslations('team');
  const router = useRouter();
  const team = useTeam(kind, canManage, (key: TeamToast) => toast.success(t(`toasts.${key}`)));
  const schedule = useSchedule(
    kind,
    canManage && !team.locked && readOnly === null,
    canBlock && readOnly === null,
    (key: ScheduleToast, values?: { count: number }) => toast.success(t(`toasts.${key}`, values)),
    () => void team.reload(),
  );
  const may = canManage && !team.locked;
  const [closing, setClosing] = useState(false);
  // What opened the sheet, which gets focus back when it closes: it opens from buttons on the page.
  const opener = useRef<HTMLElement | null>(null);
  const remember = (open: () => void) => {
    opener.current = document.activeElement instanceof HTMLElement ? document.activeElement : null;
    open();
  };
  const giveFocusBack = () => opener.current?.isConnected && opener.current.focus();
  const guard = useLeaveGuard(team.sheetDirty || schedule.dirty, (href) => router.push(href));

  const members = team.members ?? [];
  const shown = members.filter((member) => member.shownOnline).length;
  const loading = team.loading && !team.members;
  const notices = <Notices team={team} readOnly={readOnly} />;
  const summary =
    !team.members || members.length === 0 ? t('summaryEmpty') : t('summary', { people: members.length, shown });
  return (
    <div className="flex min-w-0 flex-col gap-4" data-testid="team-page">
      <TeamFrame
        tab="team"
        showTabs={canSeeAccounts}
        summary={team.members ? summary : ''}
        action={
          may &&
          team.members && (
            <Button size="sm" onClick={() => remember(team.newPerson)} data-testid="team-add">
              {t('add')}
            </Button>
          )
        }
      >
        {!team.sheetOpen && notices}
        {loading ? (
          <div
            className="grid gap-3 wide:grid-cols-2"
            role="status"
            aria-busy="true"
            aria-label={t('loading')}
            data-testid="team-loading"
          >
            <Skeleton className="h-48 w-full rounded-card" />
            <Skeleton className="h-48 w-full rounded-card" />
          </div>
        ) : team.members ? (
          <TeamCards
            today={todayInZone(timeZone)}
            members={members}
            categories={team.categories}
            services={team.services}
            canManage={may}
            canSeeAccounts={canSeeAccounts}
            onAdd={() => remember(team.newPerson)}
            onEdit={(member) => remember(() => team.editPerson(member))}
            onSchedule={(member) => remember(() => schedule.openFor(member))}
          />
        ) : null}
      </TeamFrame>
      {team.sheet && (
        <MemberSheet
          key={team.opened}
          open={team.sheetOpen}
          memberId={team.sheet.member?.id ?? null}
          values={team.sheet.values}
          problems={team.shownProblems}
          saving={team.busy}
          photoId={team.sheet.member?.photoId ?? null}
          photoUpload={team.photoUpload}
          categories={team.categories}
          services={team.services}
          accounts={team.offeredAccounts}
          onChange={team.changeValues}
          onSave={() => void team.save()}
          onClose={() => (team.sheetDirty ? setClosing(true) : team.closeSheet())}
          onClosed={giveFocusBack}
          onRemove={team.askRemove}
          onPhoto={(file) => void team.uploadPhoto(file)}
          onRemovePhoto={() => void team.removePhoto()}
          notices={team.sheetOpen ? notices : null}
        />
      )}
      {schedule.member && (
        <ScheduleSheet
          key={schedule.opened}
          open={schedule.open}
          member={schedule.member}
          tab={schedule.tab}
          loading={schedule.loading && !schedule.ready}
          onTab={schedule.selectTab}
          onClose={() => (schedule.dirty ? setClosing(true) : schedule.close())}
          onClosed={giveFocusBack}
          notices={<ScheduleNotices schedule={schedule} readOnly={readOnly} />}
          week={
            schedule.ready && (
              <WeeklyHoursTab
                week={schedule.week}
                salonWeek={schedule.salonWeek}
                problems={schedule.problems}
                edited={schedule.weekEdited}
                canChange={schedule.may}
                saving={schedule.busy}
                onDay={schedule.changeDay}
                onCopyMonday={schedule.copyMonday}
                onUseSalon={schedule.applySalonHours}
                onSave={() => void schedule.saveWeek()}
              />
            )
          }
          changes={
            schedule.ready && (
              <DateChangesTab
                today={schedule.today}
                changes={schedule.changes}
                form={schedule.change}
                refusal={schedule.changeRefusal}
                apiProblems={schedule.changeApiProblems}
                canChange={schedule.may}
                busy={schedule.busy}
                onStart={schedule.startChange}
                onForm={schedule.editChange}
                onCancel={schedule.cancelChange}
                onSave={() => void schedule.addChange()}
                onRemove={(change) => void schedule.removeChange(change)}
              />
            )
          }
          leave={
            schedule.ready && (
              <LeaveTab
                today={schedule.today}
                leave={schedule.timeOff.leave}
                form={schedule.timeOff.leaveForm}
                refusal={schedule.timeOff.leaveRefusal}
                impact={schedule.timeOff.leaveImpact}
                canChange={schedule.may}
                busy={schedule.timeOff.busy}
                onStart={schedule.timeOff.startLeave}
                onForm={(form) => schedule.timeOff.editLeave(schedule.member!.id, form)}
                onCancel={schedule.timeOff.cancelLeave}
                onSave={() => void schedule.timeOff.addLeave(schedule.member!.id)}
                onRemove={schedule.timeOff.askRemoveLeave}
              />
            )
          }
          blocked={
            schedule.ready && (
              <BlockedTimeTab
                today={schedule.today}
                blocks={schedule.timeOff.blocks}
                form={schedule.timeOff.blockForm}
                refusal={schedule.timeOff.blockRefusal}
                impact={schedule.timeOff.blockImpact}
                canChange={schedule.mayBlock}
                busy={schedule.timeOff.busy}
                onStart={schedule.timeOff.startBlock}
                onForm={(form) => schedule.timeOff.editBlock(schedule.member!.id, form)}
                onCancel={schedule.timeOff.cancelBlock}
                onSave={() => void schedule.timeOff.addBlock(schedule.member!.id)}
                onRemove={(block) => void schedule.timeOff.removeBlock(schedule.member!.id, block)}
              />
            )
          }
        />
      )}
      <ConfirmRemoveLeave
        open={schedule.timeOff.removingLeave !== null}
        onCancel={schedule.timeOff.cancelRemoveLeave}
        onConfirm={() => schedule.member && void schedule.timeOff.confirmRemoveLeave(schedule.member.id)}
      />
      <ConfirmRemoveMember
        open={team.confirming}
        name={team.sheet?.member?.name ?? ''}
        onCancel={team.cancelRemove}
        onConfirm={() => void team.confirmRemove()}
      />
      <LeaveDialog
        area="team"
        open={closing || guard.pending !== null}
        onStay={() => {
          setClosing(false);
          guard.stay();
        }}
        onLeave={() => {
          setClosing(false);
          team.closeSheet();
          schedule.close();
          guard.leave();
        }}
      />
      <Toaster position="bottom-center" />
    </div>
  );
}

/** What the schedule sheet says above its tabs. */
function ScheduleNotices({
  schedule,
  readOnly,
}: {
  schedule: ReturnType<typeof useSchedule>;
  readOnly: 'support' | 'salon' | null;
}) {
  const t = useTranslations('team.schedule.notices');
  const team = useTranslations('team.notices');
  return (
    <>
      {readOnly && (
        <Alert variant="notice" data-testid="schedule-read-only">
          {readOnly === 'support' ? team('readOnlySupport') : team('readOnlySalon')}
        </Alert>
      )}
      {schedule.notice === 'conflict' && (
        <Alert variant="caution" data-testid="schedule-conflict">
          <AlertTitle>{t('conflictTitle')}</AlertTitle>
          <AlertDescription>{t('conflictBody')}</AlertDescription>
          <Button
            type="button"
            variant="outline"
            size="sm"
            className="w-fit"
            onClick={() => void schedule.reload()}
            data-testid="schedule-conflict-reload"
          >
            {t('reload')}
          </Button>
        </Alert>
      )}
      {schedule.notice === 'unavailable' && (
        <Alert variant="caution" data-testid="schedule-unavailable">
          <AlertTitle>{t('unavailableTitle')}</AlertTitle>
          <AlertDescription>{schedule.ready ? t('unavailableKept') : t('unavailableBody')}</AlertDescription>
          {!schedule.ready && (
            <Button
              variant="outline"
              size="sm"
              className="w-fit"
              onClick={() => void schedule.reload()}
              data-testid="schedule-retry"
            >
              {t('retry')}
            </Button>
          )}
        </Alert>
      )}
      {schedule.notice === 'refused' && (
        <Alert variant="notice" role="alert" data-testid="schedule-refused">
          {t('refused')}
        </Alert>
      )}
      {schedule.notice === 'unchanged' && (
        <Alert variant="notice" role="alert" data-testid="schedule-unchanged">
          {t('unchanged')}
        </Alert>
      )}
      <AffectedBookings bookings={schedule.affected} onDate={schedule.affectedOnDate} />
    </>
  );
}

/** What the page says above its list, or in the sheet while it is open. */
function Notices({ team, readOnly }: { team: ReturnType<typeof useTeam>; readOnly: 'support' | 'salon' | null }) {
  const t = useTranslations('team.notices');
  const account = useTranslations('account');
  const refusal = team.refusal;
  return (
    <>
      {readOnly && (
        <Alert variant="notice" data-testid="team-read-only">
          {readOnly === 'support' ? t('readOnlySupport') : t('readOnlySalon')}
        </Alert>
      )}
      {team.notice === 'conflict' && (
        <Alert variant="caution" data-testid="team-conflict">
          <AlertTitle>{t('conflictTitle')}</AlertTitle>
          <AlertDescription>{t('conflictBody')}</AlertDescription>
          <Button
            type="button"
            variant="outline"
            size="sm"
            className="w-fit"
            onClick={() => void team.reload()}
            data-testid="team-conflict-reload"
          >
            {t('reload')}
          </Button>
        </Alert>
      )}
      {team.notice === 'unavailable' && (
        <Alert variant="caution" data-testid="team-unavailable">
          <AlertTitle>{t('unavailableTitle')}</AlertTitle>
          <AlertDescription>{team.members ? t('unavailableKept') : t('unavailableBody')}</AlertDescription>
          {!team.members && (
            <Button
              variant="outline"
              size="sm"
              className="w-fit"
              onClick={() => void team.reload()}
              data-testid="team-retry"
            >
              {t('retry')}
            </Button>
          )}
        </Alert>
      )}
      {team.notice === 'refused' && (
        <Alert variant="notice" role="alert" data-testid="team-refused">
          {t('refused')}
        </Alert>
      )}
      {refusal && (
        <Alert variant="notice" role="alert" data-testid="team-rule" data-reason={refusal.reason}>
          {refusal.reason === 'seat'
            ? account('entitlement_exceeded')
            : refusal.reason === 'inUseRemoval'
              ? t('inUseRemoval', { name: refusal.name, count: refusal.count })
              : refusal.reason === 'inUseServices'
                ? t('inUseServices', { services: refusal.services })
                : t(refusal.reason)}
        </Alert>
      )}
    </>
  );
}
