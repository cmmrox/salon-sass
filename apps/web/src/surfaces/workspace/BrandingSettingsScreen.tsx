'use client';
import { useRouter } from 'next/navigation';
import { useTranslations } from 'next-intl';
import { toast } from 'sonner';
import { Button } from '@/components/ui/button';
import { Skeleton } from '@/components/ui/skeleton';
import { Toaster } from '@/components/ui/sonner';
import { Spinner } from '@/components/ui/spinner';
import { BrandForm, BrandPreview, LeaveDialog, PublishBar, typedColour, useLeaveGuard } from '@/features/branding';
import type { Kind } from '@/features/identity';
import { SettingsNotices } from './SettingsNotices';
import { useBrandDraft } from './use-brand-draft';

type ReadOnly = 'support' | 'salon' | null;

/**
 * The Branding tab (F04-S02), on the Profile tab's pattern: the preview above the form under 900px and beside
 * it from there, as the prototype places it; Save draft; and the brand's own publish bar. The preview follows
 * every keystroke and choice before anything is saved; an uploaded image not yet saved counts as an unsaved
 * edit for the leave guard.
 */
export function BrandingSettingsScreen({ kind, readOnly }: { kind: Kind; readOnly: ReadOnly }) {
  const t = useTranslations('settings.brand');
  const router = useRouter();
  const brand = useBrandDraft(kind, (event) =>
    toast.success(
      event === 'saved'
        ? t('saved')
        : event === 'published'
          ? t('published')
          : event === 'reset'
            ? t('resetDone')
            : t('discarded'),
    ),
  );
  const uploading = brand.uploads.logo.state === 'uploading' || brand.uploads.cover.state === 'uploading';
  const guard = useLeaveGuard(brand.dirty || uploading, (href) => router.push(href));
  const locked = readOnly !== null || brand.busy !== null;

  if (brand.loading && !brand.draft)
    return (
      <div
        className="flex flex-col gap-4"
        data-testid="brand-loading"
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
      subject="brand"
      readOnly={readOnly}
      notice={brand.notice}
      refusal={brand.refusal}
      hasDraft={brand.draft !== null}
      onReload={() => void brand.reload()}
    />
  );
  if (!brand.draft || !brand.form) return <div className="flex flex-col gap-4">{notices}</div>;
  const form = brand.form;
  const colour = typedColour(form.colour) ?? brand.draft.brand.colour;

  return (
    <div className="flex flex-col gap-6" data-testid="brand-tab">
      {notices}
      <div className="flex flex-col gap-6 rail:grid rail:grid-cols-[minmax(0,1fr)_var(--size-settings-preview-compact)] rail:items-start rail:gap-9 wide:grid-cols-[minmax(0,1fr)_var(--size-settings-preview)]">
        <section
          aria-labelledby="brand-preview-title"
          className="flex min-w-0 flex-col gap-3 rail:sticky rail:top-24 rail:col-start-2 rail:row-start-1"
        >
          <h2 id="brand-preview-title" className="text-large font-semibold">
            {t('previewHeading')}
          </h2>
          <BrandPreview name={form.name} tagline={form.tagline} colour={colour} logo={form.logo} cover={form.cover} />
          <p className="text-small text-foreground" data-testid="brand-preview-note">
            {brand.unpublished ? t('previewDraftNote') : t('previewPublishedNote')}
          </p>
        </section>
        <div className="flex w-full min-w-0 flex-col gap-4 rail:col-start-1 rail:row-start-1 rail:max-w-(--size-form)">
          <BrandForm
            form={form}
            errors={brand.errors}
            uploads={brand.uploads}
            disabled={locked}
            onEdit={brand.edit}
            onChoose={(image, file) => void brand.upload(image, file)}
            onRemove={brand.removeImage}
            onReset={() => void brand.reset()}
          />
          <Button
            className="w-full rail:w-fit"
            disabled={locked || uploading || !brand.dirty}
            onClick={() => void brand.save()}
            data-testid="brand-save"
          >
            {brand.busy === 'saving' && <Spinner />}
            {brand.busy === 'saving' ? t('saving') : t('save')}
          </Button>
        </div>
      </div>
      <PublishBar
        subject="brand"
        unpublished={brand.unpublished}
        busy={brand.busy === 'publishing' || brand.busy === 'discarding' ? brand.busy : null}
        disabled={readOnly !== null || brand.busy !== null || uploading}
        onPublish={() => void brand.publish()}
        onDiscard={() => void brand.discard()}
      />
      <LeaveDialog open={guard.pending !== null} onStay={guard.stay} onLeave={guard.leave} />
      <Toaster position="bottom-center" />
    </div>
  );
}
