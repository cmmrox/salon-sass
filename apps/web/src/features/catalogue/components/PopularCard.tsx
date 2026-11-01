'use client';
import { useTranslations } from 'next-intl';
import type { ReactNode } from 'react';
import { Card } from '@/components/ui/card';
import { ToggleGroup, ToggleGroupItem } from '@/components/ui/toggle-group';
import type { PopularMode } from '../types';

type Props = {
  mode: PopularMode;
  /** The services the salon chose, in category then name order. */
  chosen: string[];
  /** The services the Popular list shows now, as the API worked it out. */
  now: string[];
  canChange: boolean;
  busy: boolean;
  onMode: (mode: PopularMode) => void;
};

const MODES: PopularMode[] = ['chosen', 'most_booked'];

/**
 * The Services tab's **Popular on your home screen** card (F05-S02 AC-6; the F05 block's `popCard`): the prototype's
 * segmented "Chosen by you" and "Most booked", saved at once, and the sentence that says the list now.
 */
export function PopularCard({ mode, chosen, now, canChange, busy, onMode }: Props) {
  const t = useTranslations('services.popular');
  const bold = (chunks: ReactNode) => <b className="font-medium">{chunks}</b>;
  return (
    <Card className="gap-2.5 p-5" data-testid="popular-card">
      <div className="flex flex-wrap items-start justify-between gap-2">
        <div className="flex min-w-0 flex-1 basis-60 flex-col gap-1">
          <h3 className="m-0 text-body font-semibold">{t('title')}</h3>
          <p className="m-0 text-small text-muted-foreground">{t('description')}</p>
        </div>
        <ToggleGroup
          type="single"
          value={mode}
          // Ignored while a save runs rather than disabled, so that keyboard focus stays on the choice.
          onValueChange={(value) => value && value !== mode && !busy && onMode(value as PopularMode)}
          disabled={!canChange}
          aria-label={t('modesLabel')}
          className="flex-nowrap gap-0 rounded-action bg-secondary p-0.5"
          data-testid="popular-mode"
        >
          {MODES.map((each) => (
            <ToggleGroupItem key={each} value={each} variant="segment" data-testid={`popular-mode-${each}`}>
              {t(each)}
            </ToggleGroupItem>
          ))}
        </ToggleGroup>
      </div>
      <p className="m-0 text-small" data-testid="popular-summary" data-mode={mode}>
        {mode === 'most_booked'
          ? t.rich('mostBookedNow', { b: bold, names: now.join(', ') || t('nobody') })
          : chosen.length > 0
            ? t.rich('chosenSome', { b: bold, count: chosen.length, names: chosen.join(', ') })
            : t('chosenNone')}
      </p>
    </Card>
  );
}
