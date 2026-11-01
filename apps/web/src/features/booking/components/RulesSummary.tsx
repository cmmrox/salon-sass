'use client';
import { useTranslations } from 'next-intl';
import type { ReactNode } from 'react';
import { Card } from '@/components/ui/card';
import type { RulesValues } from '../client/rules-form';
import { summaryOf, type Keep, type Sentence } from '../client/rules-summary';

/**
 * "What customers see before paying" (F04-S04 AC-4, the prototype's `ruText`): the sentences for the rules on
 * the form, updated as the person changes them, and the note that existing bookings keep their policy.
 */
export function RulesSummary({ values, currency }: { values: RulesValues; currency: string }) {
  const t = useTranslations('settings.rules');
  const bold = (chunks: ReactNode) => <b className="font-semibold">{chunks}</b>;
  const keep = (what: Keep) =>
    what.key === 'share' ? t('summary.keep.share', { percent: what.percent }) : t(`summary.keep.${what.key}`);
  const sentence = (each: Sentence) => {
    switch (each.key) {
      case 'depositNone':
        return t.rich('summary.depositNone', { b: bold });
      case 'depositPercentage':
        return t.rich('summary.depositPercentage', { b: bold, percent: each.percent, cap: each.cap });
      case 'depositAmount':
        return t.rich('summary.depositAmount', { b: bold, amount: each.amount });
      case 'freeCancellation':
        return t.rich('summary.freeCancellation', { b: bold, hours: each.hours });
      case 'afterThat':
        return t('summary.afterThat', { late: keep(each.late), noShow: keep(each.noShow) });
      case 'reschedule':
        return t('summary.reschedule', { times: each.times, hours: each.hours });
      default:
        return t('summary.rescheduleContact');
    }
  };
  return (
    <section className="flex flex-col gap-3" aria-labelledby="rules-summary-title">
      <h2 id="rules-summary-title" className="m-0 text-large font-semibold">
        {t('summaryTitle')}
      </h2>
      <Card className="gap-2 text-small" data-testid="rules-summary">
        {summaryOf(values, currency).map((each) => (
          <p key={each.key} className="m-0" data-testid="rules-summary-sentence" data-sentence={each.key}>
            {sentence(each)}
          </p>
        ))}
      </Card>
      {/* Ink, not muted: small muted text on the warm canvas is under 4.5:1 (ui-ux-standards.md). */}
      <p className="m-0 text-small text-foreground" data-testid="rules-summary-note">
        {t('summaryNote')}
      </p>
    </section>
  );
}
