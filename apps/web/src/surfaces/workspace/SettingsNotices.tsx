'use client';
import { useTranslations } from 'next-intl';
import { Alert, AlertDescription, AlertTitle } from '@/components/ui/alert';
import { Button } from '@/components/ui/button';
import type { SettingsNotice } from './use-settings-draft';

type Props = {
  /** Which tab speaks: the profile (F04-S01) or the brand (F04-S02); it names the test ids and the words. */
  subject: 'profile' | 'brand';
  readOnly: 'support' | 'salon' | null;
  notice: SettingsNotice;
  refusal: string | null;
  hasDraft: boolean;
  onReload: () => void;
};

/**
 * What a settings tab tells the person above its form: that the view is read-only, that someone else changed
 * the draft (with Reload), that the API could not be reached, that fields need fixing, or why a change was
 * refused. The same on every settings tab.
 */
export function SettingsNotices({ subject, readOnly, notice, refusal, hasDraft, onReload }: Props) {
  const t = useTranslations('settings.notices');
  return (
    <>
      {readOnly && (
        <Alert variant="notice" data-testid={`${subject}-read-only`}>
          {readOnly === 'support' ? t('readOnlySupport', { subject }) : t('readOnlySalon', { subject })}
        </Alert>
      )}
      {notice === 'conflict' && (
        <Alert variant="caution" data-testid={`${subject}-conflict`}>
          <AlertTitle>{t('conflictTitle', { subject })}</AlertTitle>
          <AlertDescription>{t('conflictBody')}</AlertDescription>
          <Button
            variant="outline"
            size="sm"
            className="w-fit"
            onClick={onReload}
            data-testid={`${subject}-conflict-reload`}
          >
            {t('reload')}
          </Button>
        </Alert>
      )}
      {notice === 'unavailable' && (
        <Alert variant="caution" data-testid={`${subject}-unavailable`}>
          <AlertTitle>{t('unavailableTitle', { subject })}</AlertTitle>
          <AlertDescription>{hasDraft ? t('unavailableKept') : t('unavailableBody')}</AlertDescription>
          {!hasDraft && (
            <Button variant="outline" size="sm" className="w-fit" onClick={onReload} data-testid={`${subject}-retry`}>
              {t('retry')}
            </Button>
          )}
        </Alert>
      )}
      {notice === 'fix' && (
        <Alert variant="notice" data-testid={`${subject}-fix`}>
          {t('fixFields')}
        </Alert>
      )}
      {notice === 'refused' && (
        <Alert variant="notice" data-testid={`${subject}-refused`}>
          {t.has(`refused.${refusal}` as 'refused.permission_denied')
            ? t(`refused.${refusal}` as 'refused.permission_denied', { subject })
            : t('refused.permission_denied')}
        </Alert>
      )}
    </>
  );
}
