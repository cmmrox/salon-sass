'use client';
import { useTranslations } from 'next-intl';
import { Avatar, AvatarFallback, AvatarImage } from '@/components/ui/avatar';
import { Badge } from '@/components/ui/badge';
import { Button } from '@/components/ui/button';
import { Card } from '@/components/ui/card';
import { Empty, EmptyContent, EmptyDescription, EmptyHeader, EmptyTitle } from '@/components/ui/empty';
import { cn } from '@/lib/utils';
import { initialOf, performedCategoryNames, toneOf } from '../client/team-form';
import type { TeamCategory, TeamMember, TeamService } from '../types';

/** Where the web serves a team photo on the salon's own hostname (F06-S01 AC-9): through its BFF route. */
export const teamPhotoSource = (photoId: string) => `/team-photos/${photoId}`;

/** An image beside the person's name, which says nothing the name does not: no text alternative. */
const DECORATIVE = '';

/** A person's photo, or their initial on a colour chosen by their id. The name beside it says who it is. */
export function MemberAvatar({
  id,
  name,
  photoId,
  className,
}: {
  id: string;
  name: string;
  photoId: string | null;
  className?: string;
}) {
  return (
    <Avatar className={cn('size-13', className)} data-testid="team-member-avatar">
      {photoId && <AvatarImage src={teamPhotoSource(photoId)} alt={DECORATIVE} data-testid="team-member-photo" />}
      <AvatarFallback
        className={cn('font-semibold text-avatar-ink', id ? toneOf(id) : 'bg-avatar-1')}
        data-testid="team-member-initial"
      >
        {initialOf(name)}
      </AvatarFallback>
    </Avatar>
  );
}

type Props = {
  members: TeamMember[];
  categories: TeamCategory[];
  services: TeamService[];
  canManage: boolean;
  canSeeAccounts: boolean;
  onAdd: () => void;
  onEdit: (member: TeamMember) => void;
};

/**
 * The Team tab's cards (F06-S01 AC-12, the prototype's `aStaff`): photo, name, title, bio, Services, the Online booking
 * badge and, for those who may see accounts, Sign-in. The card's Hours row and Schedule button are F06-S02's. With
 * nobody on the team it shows the prototype's Empty state.
 */
export function TeamCards({ members, categories, services, canManage, canSeeAccounts, onAdd, onEdit }: Props) {
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
    <div className="grid gap-3 wide:grid-cols-2" data-testid="team-list">
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
                  <p className="text-small text-foreground wrap-anywhere" data-testid="team-card-title">
                    {member.title}
                  </p>
                )}
              </div>
            </div>
            {member.bio && (
              <p className="text-small text-foreground wrap-anywhere" data-testid="team-card-bio">
                {member.bio}
              </p>
            )}
            <dl className="flex flex-col gap-2 text-small">
              <div className="flex items-start justify-between gap-3">
                <dt className="shrink-0 text-foreground">{t('card.services')}</dt>
                <dd className="text-right font-medium wrap-anywhere" data-testid="team-card-services">
                  {names.length > 0 ? names.join(', ') : t('card.noServices')}
                </dd>
              </div>
              <div className="flex items-center justify-between gap-3">
                <dt className="shrink-0 text-foreground">{t('card.online')}</dt>
                <dd>
                  <Badge variant={member.shownOnline ? 'positive' : 'outline'} data-testid="team-card-online">
                    {member.shownOnline ? t('card.shown') : t('card.hidden')}
                  </Badge>
                </dd>
              </div>
              {canSeeAccounts && (
                <div className="flex items-start justify-between gap-3">
                  <dt className="shrink-0 text-foreground">{t('card.signIn')}</dt>
                  <dd className="text-right font-medium wrap-anywhere" data-testid="team-card-sign-in">
                    {member.signInLabel ?? t('card.noSignIn')}
                  </dd>
                </div>
              )}
            </dl>
            {canManage && (
              <Button
                variant="outline"
                size="sm"
                className="w-fit"
                aria-label={t('card.editLabel', { name: member.name })}
                onClick={() => onEdit(member)}
                data-testid="team-card-edit"
              >
                {t('card.edit')}
              </Button>
            )}
          </Card>
        );
      })}
    </div>
  );
}
