'use client';
import { useRouter } from 'next/navigation';
import { useTranslations } from 'next-intl';
import { toast } from 'sonner';
import { Alert } from '@/components/ui/alert';
import { Button } from '@/components/ui/button';
import { Skeleton } from '@/components/ui/skeleton';
import { Toaster } from '@/components/ui/sonner';
import { Spinner } from '@/components/ui/spinner';
import { LeaveDialog, useLeaveGuard } from '@/features/branding';
import { TaxHistorySheet, TaxPreview, TaxSettingsForm } from '@/features/catalogue';
import type { Kind } from '@/features/identity';
import { useSettingsLeaveHold } from './settings-leave';
import { SettingsNotices } from './SettingsNotices';
import { useTaxSettings } from './use-tax-settings';

type ReadOnly = 'support' | 'salon' | null;

/**
 * The Tax & charges tab (F05-S03, the prototype's `setTax`): "What customers pay" beside the form from 900px and
 * above it on narrower screens, the banner of what these settings are, the form, Save tax and charges (saved at once
 * as a new version) and History. Loading, saved, field-error, conflict, refused and unavailable states; when the
 * configured VAT rate is not the current version's, it says so. Leaving with unsaved edits, by a link or another
 * Settings tab, asks first. A support view and a read-only salon read the settings and the history and change
 * nothing.
 */
export function TaxSettingsScreen({ kind, timeZone, readOnly }: { kind: Kind; timeZone: string; readOnly: ReadOnly }) {
  const t = useTranslations('settings.tax');
  const router = useRouter();
  const tax = useTaxSettings(kind, () => toast.success(t('saved')));
  const guard = useLeaveGuard(tax.dirty, (href) => router.push(href));
  useSettingsLeaveHold(tax.dirty, guard.hold);
  const locked = readOnly !== null || tax.saving;

  if (tax.loading && !tax.form)
    return (
      <div
        className="flex flex-col gap-4 rail:grid rail:grid-cols-[minmax(0,1fr)_var(--size-settings-preview-compact)] rail:gap-9 wide:grid-cols-[minmax(0,1fr)_var(--size-settings-preview)]"
        data-testid="tax-loading"
        role="status"
        aria-busy="true"
        aria-label={t('loading')}
      >
        <Skeleton className="h-96 w-full rounded-card" />
        <Skeleton className="h-48 w-full rounded-card" />
      </div>
    );

  const notices = (
    <>
      <SettingsNotices
        subject="tax"
        readOnly={readOnly}
        notice={tax.notice === 'unchanged' ? null : tax.notice}
        refusal={tax.refusal}
        hasDraft={tax.form !== null}
        onReload={() => void tax.reload()}
      />
      {tax.notice === 'unchanged' && (
        <Alert variant="notice" data-testid="tax-unchanged">
          {t('unchanged')}
        </Alert>
      )}
    </>
  );
  if (!tax.form || !tax.settings) return <div className="flex flex-col gap-4">{notices}</div>;
  const configured = tax.settings.configured;

  return (
    <div className="flex flex-col gap-6" data-testid="tax-tab">
      {notices}
      <div className="flex flex-col gap-6 rail:grid rail:grid-cols-[minmax(0,1fr)_var(--size-settings-preview-compact)] rail:items-start rail:gap-9 wide:grid-cols-[minmax(0,1fr)_var(--size-settings-preview)]">
        {/* First in reading order, as in the prototype: above the form on phones, beside it from 900px. */}
        <div className="flex flex-col gap-3 rail:sticky rail:top-24 rail:order-2">
          <TaxPreview lines={tax.preview} currency={tax.currency} configured={configured} />
        </div>
        <div className="flex w-full min-w-0 flex-col gap-4 rail:order-1 rail:max-w-(--size-form)">
          <Alert variant="info" className="text-small" data-testid="tax-banner-settings">
            {t('banner')}
          </Alert>
          {tax.newRate !== null && (
            <Alert variant="notice" data-testid="tax-new-rate">
              {t('newRate', { vat: configured.vatName, rate: tax.newRate })}
            </Alert>
          )}
          <TaxSettingsForm
            form={tax.form}
            problems={tax.problems}
            configured={configured}
            disabled={locked}
            onChange={tax.change}
          />
          <div className="mt-2 flex flex-col gap-3 rail:flex-row rail:items-center">
            <Button
              className="w-full rail:w-fit"
              disabled={locked || !tax.canSave}
              onClick={() => void tax.save()}
              data-testid="tax-save"
            >
              {tax.saving && <Spinner />}
              {tax.saving ? t('saving') : t('save')}
            </Button>
            <TaxHistorySheet
              open={tax.historyOpen}
              onOpenChange={tax.setHistoryOpen}
              history={tax.history}
              viewer={kind === 'support' ? null : tax.viewer}
              timeZone={timeZone}
              onPage={tax.historyPage}
            />
          </div>
        </div>
      </div>
      <LeaveDialog area="tax" open={guard.pending !== null} onStay={guard.stay} onLeave={guard.leave} />
      <Toaster position="bottom-center" />
    </div>
  );
}
