'use client';
import { formatDate, formatDay } from '@salon/format';
import { useTranslations } from 'next-intl';
import { Badge } from '@/components/ui/badge';
import { Button } from '@/components/ui/button';
import { Card, CardDescription, CardTitle } from '@/components/ui/card';
import { Select, SelectContent, SelectItem, SelectTrigger, SelectValue } from '@/components/ui/select';
import { Spinner } from '@/components/ui/spinner';
import { comingDays, type ChoiceForm } from '../client/hours-form';
import type { HolidayChoiceKind, HolidayYear } from '../types';
import { TimeSelect } from './TimeSelect';

type Props = {
  year: HolidayYear | null;
  timeZone: string;
  choices: Record<string, ChoiceForm>;
  disabled: boolean;
  busy: boolean;
  onChoose: (date: string, change: Partial<ChoiceForm>) => void;
  onAccept: () => void;
};

const KINDS: HolidayChoiceKind[] = ['closed', 'open', 'special'];

/**
 * The Public holidays card (F04-S03): the operator's published year, "To review" or "Reviewed", each coming
 * day with its kind and a Select (Closed, Open as usual, Special hours with two time Selects). Before the
 * salon accepts, the choices are a proposal and Accept these choices applies them; after it, each change
 * saves at once. With no published year it says the operator has not published this year's holidays yet.
 */
export function HolidaysCard({ year, timeZone, choices, disabled, busy, onChoose, onAccept }: Props) {
  const t = useTranslations('settings.holidays');
  if (!year)
    return (
      <Card className="gap-3" data-testid="holidays-card">
        <CardTitle className="text-large">{t('titleNone')}</CardTitle>
        <CardDescription data-testid="holidays-none">{t('none')}</CardDescription>
      </Card>
    );
  const accepted = year.version > 0;
  const days = comingDays(year);
  return (
    <Card className="gap-3" data-testid="holidays-card" data-year={year.year}>
      <div className="flex flex-wrap items-center justify-between gap-2">
        <CardTitle className="text-large">{t('title', { year: year.year })}</CardTitle>
        <Badge variant={year.reviewed ? 'positive' : 'secondary'} data-testid="holidays-status">
          {year.reviewed ? t('reviewed') : t('toReview')}
        </Badge>
      </div>
      <CardDescription>{t('published', { date: formatDate(year.publishedAt, timeZone) })}</CardDescription>
      {days.length === 0 ? (
        <p className="text-small" data-testid="holidays-all-past">
          {t('allPast')}
        </p>
      ) : (
        <ul className="m-0 flex list-none flex-col p-0" data-testid="holidays-days">
          {days.map((day) => {
            const choice = choices[day.date]!;
            const dayLabel = formatDay(day.date);
            return (
              <li
                key={day.date}
                className="flex flex-wrap items-center justify-between gap-2 border-b border-border py-2.5 last:border-b-0"
                data-testid={`holiday-${day.date}`}
              >
                <span className="flex min-w-0 flex-col gap-0.5">
                  <span className="flex flex-wrap items-center gap-1.5">
                    <span className="font-medium">{dayLabel}</span>
                    <Badge variant="secondary">{t(`kinds.${day.kind}`)}</Badge>
                    {day.toReview && accepted && (
                      <Badge variant="outline" data-testid={`holiday-${day.date}-to-review`}>
                        {t('dayToReview')}
                      </Badge>
                    )}
                  </span>
                  <span className="text-small text-muted-foreground">{day.name}</span>
                </span>
                <span className="flex flex-wrap items-center justify-end gap-1.5">
                  <Select
                    value={choice.choice}
                    onValueChange={(value) => onChoose(day.date, { choice: value as HolidayChoiceKind })}
                    disabled={disabled}
                  >
                    <SelectTrigger
                      size="sm"
                      className="w-auto min-w-36"
                      aria-label={t('choiceFor', { name: day.name, date: dayLabel })}
                      data-testid={`holiday-${day.date}-choice`}
                    >
                      <SelectValue />
                    </SelectTrigger>
                    <SelectContent position="popper" sideOffset={4}>
                      {KINDS.map((kind) => (
                        <SelectItem key={kind} value={kind} data-testid={`holiday-${day.date}-choice-${kind}`}>
                          {t(`choices.${kind}`)}
                        </SelectItem>
                      ))}
                    </SelectContent>
                  </Select>
                  {choice.choice === 'special' && (
                    <span className="flex basis-full flex-wrap items-center justify-end gap-1.5">
                      <TimeSelect
                        value={choice.opens}
                        onChange={(opens) => onChoose(day.date, { opens })}
                        label={t('opens', { name: day.name })}
                        testId={`holiday-${day.date}-opens`}
                        disabled={disabled}
                      />
                      <span className="text-small">{t('to')}</span>
                      <TimeSelect
                        value={choice.closes}
                        onChange={(closes) => onChoose(day.date, { closes })}
                        label={t('closes', { name: day.name })}
                        testId={`holiday-${day.date}-closes`}
                        disabled={disabled}
                        invalid={choice.closes <= choice.opens}
                      />
                    </span>
                  )}
                </span>
              </li>
            );
          })}
        </ul>
      )}
      {(!accepted || !year.reviewed) && (
        <Button
          variant="outline"
          className="w-full"
          onClick={onAccept}
          disabled={disabled || busy}
          data-testid="holidays-accept"
        >
          {busy && <Spinner />}
          {t('accept')}
        </Button>
      )}
    </Card>
  );
}
