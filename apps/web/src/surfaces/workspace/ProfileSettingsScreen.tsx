'use client';
import { useRouter } from 'next/navigation';
import { useTranslations } from 'next-intl';
import { toast } from 'sonner';
import { Button } from '@/components/ui/button';
import { Skeleton } from '@/components/ui/skeleton';
import { Toaster } from '@/components/ui/sonner';
import { Spinner } from '@/components/ui/spinner';
import { LeaveDialog, ProfileForm, ProfilePreview, PublishBar, useLeaveGuard } from '@/features/branding';
import type { Kind } from '@/features/identity';
import { useSettingsLeaveHold } from './settings-leave';
import { SettingsNotices } from './SettingsNotices';
import { useProfileDraft } from './use-profile-draft';

type ReadOnly = 'support' | 'salon' | null;

/**
 * The Profile tab (F04-S01): the form with "What customers see" beside it on wide screens and below it
 * on narrow ones, Save draft, and the publish bar. Loading, saved, field-error, conflict, unavailable and
 * published states; leaving with unsaved edits asks first. A support view and a read-only salon see the
 * draft and change nothing.
 */
export function ProfileSettingsScreen({
  kind,
  salonName,
  readOnly,
}: {
  kind: Kind;
  salonName: string;
  readOnly: ReadOnly;
}) {
  const t = useTranslations('settings.profile');
  const router = useRouter();
  const profile = useProfileDraft(kind, (event) =>
    toast.success(event === 'saved' ? t('saved') : event === 'published' ? t('published') : t('discarded')),
  );
  const guard = useLeaveGuard(profile.dirty, (href) => router.push(href));
  // A Settings tab is not a link: the Settings page asks this tab before changing to another (F04-S04).
  useSettingsLeaveHold(profile.dirty, guard.hold);
  const locked = readOnly !== null || profile.busy !== null;

  if (profile.loading && !profile.draft)
    return (
      <div
        className="flex flex-col gap-4"
        data-testid="profile-loading"
        role="status"
        aria-busy="true"
        aria-label={t('loading')}
      >
        <Skeleton className="h-96 w-full rounded-card" />
        <Skeleton className="h-24 w-full rounded-card" />
      </div>
    );

  const notices = (
    <SettingsNotices
      subject="profile"
      readOnly={readOnly}
      notice={profile.notice}
      refusal={profile.refusal}
      hasDraft={profile.draft !== null}
      onReload={() => void profile.reload()}
    />
  );

  if (!profile.draft || !profile.form) return <div className="flex flex-col gap-4">{notices}</div>;

  return (
    <div className="flex flex-col gap-6" data-testid="profile-tab">
      {notices}
      <div className="flex flex-col gap-6 rail:grid rail:grid-cols-[minmax(0,1fr)_var(--size-settings-preview-compact)] rail:items-start rail:gap-9 wide:grid-cols-[minmax(0,1fr)_var(--size-settings-preview)]">
        <div className="flex w-full min-w-0 flex-col gap-4 rail:max-w-(--size-form)">
          <ProfileForm form={profile.form} errors={profile.errors} disabled={locked} onChange={profile.edit} />
          <Button
            className="w-full rail:w-fit"
            disabled={locked || !profile.dirty}
            onClick={() => void profile.save()}
            data-testid="profile-save"
          >
            {profile.busy === 'saving' && <Spinner />}
            {profile.busy === 'saving' ? t('saving') : t('save')}
          </Button>
        </div>
        <div className="rail:sticky rail:top-24">
          <ProfilePreview salonName={salonName} form={profile.form} />
        </div>
      </div>
      <PublishBar
        subject="profile"
        unpublished={profile.unpublished}
        busy={profile.busy === 'publishing' || profile.busy === 'discarding' ? profile.busy : null}
        disabled={readOnly !== null || profile.busy !== null}
        onPublish={() => void profile.publish()}
        onDiscard={() => void profile.discard()}
      />
      <LeaveDialog open={guard.pending !== null} onStay={guard.stay} onLeave={guard.leave} />
      <Toaster position="bottom-center" />
    </div>
  );
}
