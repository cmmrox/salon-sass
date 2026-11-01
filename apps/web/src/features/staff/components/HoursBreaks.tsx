'use client';
import { X } from 'lucide-react';
import { useTranslations } from 'next-intl';
import { Button } from '@/components/ui/button';
import { MAX_BREAKS, newBreak, type BreakForm, type DayHours } from '../client/schedule-form';
import { TimeSelect } from './TimeSelect';

type Labels = {
  starts: (number: number) => string;
  ends: (number: number) => string;
  remove: (number: number) => string;
  add: string;
};

type Props = {
  hours: DayHours;
  /** Where the rows' test ids start: `schedule-monday` or `change`. */
  testId: string;
  /** What each control's name says: which weekday and which break it belongs to (AC-16). */
  labels: Labels;
  /** Whether this person may change them; without it there is no + Add break and no remove control (AC-13). */
  canChange: boolean;
  disabled: boolean;
  onChange: (breaks: BreakForm[]) => void;
};

/** A day's breaks, on a week's day or on a date: two times and a remove control each, and + Add break, up to five (AC-3). */
export function HoursBreaks({ hours, testId, labels, canChange, disabled, onChange }: Props) {
  const t = useTranslations('team.schedule');
  const set = (index: number, change: Partial<BreakForm>) =>
    onChange(hours.breaks.map((each, at) => (at === index ? { ...each, ...change } : each)));
  const full = hours.breaks.length >= MAX_BREAKS;
  return (
    <div className="flex flex-col gap-2" data-testid={`${testId}-breaks`}>
      {hours.breaks.map((each, index) => {
        const number = index + 1;
        return (
          <div
            key={index}
            className="flex flex-wrap items-center justify-end gap-1.5"
            data-testid={`${testId}-break-${index}`}
          >
            <span className="text-small text-foreground">{t('break')}</span>
            <TimeSelect
              narrow
              value={each.starts}
              onChange={(starts) => set(index, { starts })}
              label={labels.starts(number)}
              testId={`${testId}-break-${index}-starts`}
              disabled={disabled || !canChange}
            />
            <span className="text-small text-foreground">{t('to')}</span>
            <TimeSelect
              narrow
              value={each.finishes}
              onChange={(finishes) => set(index, { finishes })}
              label={labels.ends(number)}
              testId={`${testId}-break-${index}-finishes`}
              disabled={disabled || !canChange}
            />
            {canChange && (
              <Button
                type="button"
                variant="outline"
                size="sm"
                aria-label={labels.remove(number)}
                disabled={disabled}
                onClick={() => onChange(hours.breaks.filter((_, at) => at !== index))}
                data-testid={`${testId}-break-${index}-remove`}
              >
                <X aria-hidden="true" />
              </Button>
            )}
          </div>
        );
      })}
      {canChange && (
        <div className="flex flex-wrap items-center justify-end gap-2">
          {full && (
            <span className="text-small text-foreground" data-testid={`${testId}-breaks-full`}>
              {t('maxBreaks')}
            </span>
          )}
          <Button
            type="button"
            variant="outline"
            size="sm"
            aria-label={labels.add}
            disabled={disabled || full}
            onClick={() => onChange([...hours.breaks, newBreak(hours)])}
            data-testid={`${testId}-add-break`}
          >
            {t('addBreak')}
          </Button>
        </div>
      )}
    </div>
  );
}
