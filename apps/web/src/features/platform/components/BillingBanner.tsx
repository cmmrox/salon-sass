import { useTranslations } from 'next-intl';
import type { components } from '@salon/api-contracts';
import { Alert, AlertDescription, AlertTitle } from '@/components/ui/alert';
import { formatDate } from '@/lib/format';

export type Entitlements = components['schemas']['Entitlements'];

/**
 * The workspace's billing banner for every staff member (F03-S02): a trial ending within three days,
 * an overdue invoice with its due date, or the read-only state with how to pay. The API decides which
 * one with its own clock; this only shows it.
 */
export function BillingBanner({ entitlements, timeZone }: { entitlements: Entitlements | null; timeZone: string }) {
  const t = useTranslations('billing');
  const subscription = entitlements?.subscription;
  if (!subscription?.notice) return null;
  const date = (value: string | null | undefined) => (value ? formatDate(value, timeZone) : '');
  if (subscription.notice === 'trial_ending') {
    return (
      <Alert variant="default" role="status" data-testid="workspace-billing-banner" data-notice="trial_ending">
        <AlertTitle>{t('bannerTrialTitle')}</AlertTitle>
        <AlertDescription>{t('bannerTrial', { date: date(subscription.trialEndsAt) })}</AlertDescription>
      </Alert>
    );
  }
  if (subscription.notice === 'past_due') {
    return (
      <Alert variant="caution" role="status" data-testid="workspace-billing-banner" data-notice="past_due">
        <AlertTitle>{t('bannerPastDueTitle')}</AlertTitle>
        <AlertDescription>{t('bannerPastDue', { due: date(subscription.pastDueSince), readOnly: date(subscription.readOnlyAt) })}</AlertDescription>
      </Alert>
    );
  }
  return (
    <Alert variant="destructive" role="alert" data-testid="workspace-billing-banner" data-notice="read_only">
      <AlertTitle>{t('bannerReadOnlyTitle')}</AlertTitle>
      <AlertDescription>{t('bannerReadOnly', { due: date(subscription.pastDueSince) })}</AlertDescription>
    </Alert>
  );
}
