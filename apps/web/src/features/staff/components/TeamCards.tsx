'use client';
import { formatClock, formatShortDay, formatShortPeriod } from '@salon/format';
import { useTranslations } from 'next-intl';
import { Avatar, AvatarFallback, AvatarImage } from '@/components/ui/avatar';
import { Badge } from '@/components/ui/badge';
import { Button } from '@/components/ui/button';
import { Card } from '@/components/ui/card';
import { Empty, EmptyContent, EmptyDescription, EmptyHeader, EmptyTitle } from '@/components/ui/empty';
import { cn } from '@/lib/utils';
import { hoursSummaryOf } from '../client/schedule-form';
import { initialOf, performedCategoryNames, toneOf } from '../client/team-form';
import type { NextLeave, TeamCategory, TeamMember, TeamService, TeamWorkday } from '../types';

/** Where the web serves a team photo on the salon's own hostname (F06-S01 AC-9): through its BFF route. */
const teamPhotoSource = (photoId: string) => `/team-photos/${photoId}`;

/** An image beside the person's name, which says nothing the name does not: no text alternative. */
const DECORATIVE = '';

/** A person's photo, or their initial on a colour chosen by their id. The name beside it says who it is. */
export function MemberAvatar({
  id,
  name,
  photoId,
  large = false,
}: {
  id: string;
  name: string;
  photoId: string | null;
  /** The sheet's 84px, not the card's 52px, each with its initial in proportion as the prototype's `avH` draws it. */
  large?: boolean;
}) {
  return (
    <Avatar className={large ? 'size-21' : 'size-13'} data-testid="team-member-avatar">
      {photoId && <AvatarImage src={teamPhotoSource(photoId)} alt={DECORATIVE} data-testid="team-member-photo" />}
      <AvatarFallback
        className={cn('font-semibold text-avatar-ink', large ? 'text-4xl' : 'text-xl', id ? toneOf(id) : 'bg-avatar-1')}
        data-testid="team-member-initial"
      >
        {initialOf(name)}
      </AvatarFallback>
    </Avatar>
  );
}

type Props = {
  /** The salon's date, which decides whether the leave badge says the year (decision 107). */
  today: string;
  members: TeamMember[];
  categories: TeamCategory[];
  services: TeamService[];
  canManage: boolean;
  canSeeAccounts: boolean;
  onAdd: () => void;
  onEdit: (member: TeamMember) => void;
  onSchedule: (member: TeamMember) => void;
};

/**
 * A person's week in one line (F06-S02 AC-12, the prototype's `hrsLine`): "Mon–Sat, 09:00 – 18:00 · break 13:00", a single
 * day, "Varies by day" or "No working days". It says the weekly hours, whatever date changes there are.
 */
function HoursLine({ weekly }: { weekly: TeamWorkday[] }) {
  const t = useTranslations('team.card');
  const short = useTranslations('settings.hours.short');
  const summary = hoursSummaryOf(weekly);
  if (summary.kind === 'none') return t('noHours');
  if (summary.kind === 'varies') return t('variesByDay');
  const times = { starts: formatClock(summary.starts), finishes: formatClock(summary.finishes) };
  const line =
    summary.first === summary.last
      ? t('hoursDay', { day: short(summary.first), ...times })
      : t('hoursRun', { first: short(summary.first), last: short(summary.last), ...times });
  const breaks = summary.breaks;
  if ('at' in breaks) return line + t('oneBreak', { at: formatClock(breaks.at) });
  return breaks.count > 1 ? line + t('manyBreaks', { count: breaks.count }) : line;
}

/**
 * A person's current or next leave (F06-S03 AC-9, the prototype's `nextOff`): with `team.manage` its type and days, and
 * otherwise only that they are on leave, since the type is private (decision 104). Its days are in the short form of
 * decision 107 (F06-S03 AC-16): no year within the salon's current year.
 */
function LeaveBadge({ leave, today }: { leave: NextLeave; today: string }) {
  const t = useTranslations('team');
  const days =
    leave.firstDay === leave.lastDay
      ? formatShortDay(leave.firstDay, today)
      : t('leaveDays.range', formatShortPeriod(leave.firstDay, leave.lastDay, today));
  return (
    <Badge variant="caution" className="whitespace-normal" data-testid="team-card-leave">
      {leave.type ? t('card.leaveTyped', { type: t(`leaveTypes.${leave.type}`), days }) : t('card.leaveDays', { days })}
    </Badge>
  );
}

/**
 * The Team tab's cards (F06-S01 AC-12, the prototype's `aStaff`): photo, name, title, bio, Services, Hours, the Online
 * booking badge and, for those who may see accounts, Sign-in; **Schedule** for everyone who reads the team, and **Edit
 * profile** for those who may change it. With nobody on the team it shows the prototype's Empty state.
 */
export function TeamCards({
  today,
  members,
  categories,
  services,
  canManage,
  canSeeAccounts,
  onAdd,
  onEdit,
  onSchedule,
}: Props) {
  const t = useTranslations('team');
  if (members.length === 0)
    return (
      <Empty surface="card" data-testid="team-empty">
        <EmptyHeader>
          <EmptyTitle>{t('emptyTitle')}</EmptyTitle>
          <EmptyDescription>{t('emptyBody')}</EmptyDescription>
        </EmptyHeader>
        {canManage && (
          <EmptyContent>
            <Button size="sm" onClick={onAdd} data-testid="team-empty-add">
              {t('add')}
            </Button>
          </EmptyContent>
        )}
      </Empty>
    );
  return (
    // The prototype's `.gc3`: one column on a phone, two from 900px and three from 1200px.
    <div className="grid gap-3 rail:grid-cols-2 wide:grid-cols-3" data-testid="team-list">
      {members.map((member) => {
        const names = performedCategoryNames(member, categories, services);
        return (
          <Card key={member.id} className="gap-3 p-4" data-testid="team-card" data-id={member.id}>
            <div className="flex min-w-0 items-center gap-3">
              <MemberAvatar id={member.id} name={member.name} photoId={member.photoId} />
              <div className="min-w-0">
                <h2 className="font-medium wrap-anywhere" data-testid="team-card-name">
                  {member.name}
                </h2>
                {member.title && (
                  <p className="text-small wrap-anywhere text-muted-foreground" data-testid="team-card-title">
                    {member.title}
                  </p>
                )}
              </div>
            </div>
            {member.bio && (
              <p className="text-small wrap-anywhere text-muted-foreground" data-testid="team-card-bio">
                {member.bio}
              </p>
            )}
            {/* The prototype's `.kv`: a line under each row but the last, muted labels on the white card. */}
            <dl className="flex flex-col divide-y divide-border text-small">
              <div className="flex items-start justify-between gap-4 py-2">
                <dt className="shrink-0 text-muted-foreground">{t('card.services')}</dt>
                <dd className="text-right font-medium wrap-anywhere" data-testid="team-card-services">
                  {names.length > 0 ? names.join(', ') : t('card.noServices')}
                </dd>
              </div>
              <div className="flex items-start justify-between gap-4 py-2">
                <dt className="shrink-0 text-muted-foreground">{t('card.hours')}</dt>
                <dd className="text-right font-medium wrap-anywhere" data-testid="team-card-hours">
                  <HoursLine weekly={member.weekly} />
                </dd>
              </div>
              <div className="flex items-center justify-between gap-4 py-2">
                <dt className="shrink-0 text-muted-foreground">{t('card.online')}</dt>
                <dd>
                  <Badge variant={member.shownOnline ? 'positive' : 'outline'} data-testid="team-card-online">
                    {member.shownOnline ? t('card.shown') : t('card.hidden')}
                  </Badge>
                </dd>
              </div>
              {canSeeAccounts && (
                <div className="flex items-start justify-between gap-4 py-2">
                  <dt className="shrink-0 text-muted-foreground">{t('card.signIn')}</dt>
                  <dd className="text-right font-medium wrap-anywhere" data-testid="team-card-sign-in">
                    {member.signInLabel ?? t('card.noSignIn')}
                  </dd>
                </div>
              )}
            </dl>
            {member.nextLeave && <LeaveBadge leave={member.nextLeave} today={today} />}
            <div className="flex flex-wrap gap-1.5">
              {canManage && (
                <Button
                  variant="outline"
                  size="sm"
                  aria-label={t('card.editLabel', { name: member.name })}
                  onClick={() => onEdit(member)}
                  data-testid="team-card-edit"
                >
                  {t('card.edit')}
                </Button>
              )}
              <Button
                variant="outline"
                size="sm"
                aria-label={t('card.scheduleLabel', { name: member.name })}
                onClick={() => onSchedule(member)}
                data-testid="team-card-schedule"
              >
                {t('card.schedule')}
              </Button>
            </div>
          </Card>
        );
      })}
    </div>
  );
}
