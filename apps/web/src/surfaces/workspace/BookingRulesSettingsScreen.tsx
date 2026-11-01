'use client';
import { useRouter } from 'next/navigation';
import { useTranslations } from 'next-intl';
import { toast } from 'sonner';
import { Alert } from '@/components/ui/alert';
import { Badge } from '@/components/ui/badge';
import { Button } from '@/components/ui/button';
import { Skeleton } from '@/components/ui/skeleton';
import { Toaster } from '@/components/ui/sonner';
import { Spinner } from '@/components/ui/spinner';
import { BookingRulesForm, RulesHistorySheet, RulesSummary } from '@/features/booking';
import { LeaveDialog, useLeaveGuard } from '@/features/branding';
import type { Kind } from '@/features/identity';
import { useSettingsLeaveHold } from './settings-leave';
import { SettingsNotices } from './SettingsNotices';
import { useBookingRules } from './use-booking-rules';

type ReadOnly = 'support' | 'salon' | null;

/**
 * The Booking rules tab (F04-S04, the prototype's `setRules`): "What customers see before paying" beside the
 * form from 900px and above it on narrower screens, Save booking rules (saved at once as a new version) and
 * History. Loading, saved, field-error, conflict, unavailable and "Not saved yet" states; leaving with unsaved
 * edits, by a link or another Settings tab, asks first. A support view and a read-only salon read the rules and
 * the history and change nothing.
 */
export function BookingRulesSettingsScreen({
  kind,
  timeZone,
  readOnly,
}: {
  kind: Kind;
  timeZone: string;
  readOnly: ReadOnly;
}) {
  const t = useTranslations('settings.rules');
  const router = useRouter();
  const rules = useBookingRules(kind, () => toast.success(t('saved')));
  const guard = useLeaveGuard(rules.dirty, (href) => router.push(href));
  useSettingsLeaveHold(rules.dirty, guard.hold);
  const locked = readOnly !== null || rules.saving;

  if (rules.loading && !rules.form)
    return (
      <div
        className="flex flex-col gap-4 rail:grid rail:grid-cols-[minmax(0,1fr)_var(--size-settings-preview-compact)] rail:gap-9 wide:grid-cols-[minmax(0,1fr)_var(--size-settings-preview)]"
        data-testid="rules-loading"
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
        subject="rules"
        readOnly={readOnly}
        notice={rules.notice === 'unchanged' ? null : rules.notice}
        refusal={rules.refusal}
        hasDraft={rules.form !== null}
        onReload={() => void rules.reload()}
      />
      {rules.notice === 'unchanged' && (
        <Alert variant="notice" data-testid="rules-unchanged">
          {t('unchanged')}
        </Alert>
      )}
    </>
  );
  if (!rules.form || !rules.settings) return <div className="flex flex-col gap-4">{notices}</div>;

  return (
    <div className="flex flex-col gap-6" data-testid="rules-tab">
      {notices}
      <div className="flex flex-col gap-6 rail:grid rail:grid-cols-[minmax(0,1fr)_var(--size-settings-preview-compact)] rail:items-start rail:gap-9 wide:grid-cols-[minmax(0,1fr)_var(--size-settings-preview)]">
        {/* First in reading order, as in the prototype: above the form on phones, beside it from 900px. */}
        <div className="flex flex-col gap-3 rail:sticky rail:top-24 rail:order-2">
          {rules.settings.rules === null && (
            <div className="flex flex-wrap items-center gap-2" data-testid="rules-not-saved">
              <Badge variant="secondary">{t('notSaved')}</Badge>
              <span className="text-small text-foreground">{t('notSavedHelp')}</span>
            </div>
          )}
          <RulesSummary values={rules.form.values} currency={rules.settings.currency} />
        </div>
        <div className="flex w-full min-w-0 flex-col gap-4 rail:order-1 rail:max-w-(--size-form)">
          <BookingRulesForm
            form={rules.form}
            errors={rules.errors}
            currency={rules.settings.currency}
            disabled={locked}
            onChoose={rules.choose}
            onType={rules.type}
          />
          <div className="mt-2 flex flex-col gap-3 rail:flex-row rail:items-center">
            <Button
              className="w-full rail:w-fit"
              disabled={locked || !rules.canSave}
              onClick={() => void rules.save()}
              data-testid="rules-save"
            >
              {rules.saving && <Spinner />}
              {rules.saving ? t('saving') : t('save')}
            </Button>
            <RulesHistorySheet
              open={rules.historyOpen}
              onOpenChange={rules.setHistoryOpen}
              history={rules.history}
              viewer={kind === 'support' ? null : rules.viewer}
              timeZone={timeZone}
              onPage={rules.historyPage}
            />
          </div>
        </div>
      </div>
      <LeaveDialog area="rules" open={guard.pending !== null} onStay={guard.stay} onLeave={guard.leave} />
      <Toaster position="bottom-center" />
    </div>
  );
}
