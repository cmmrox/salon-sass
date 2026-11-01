'use client';
import { formatDate, formatDay } from '@salon/format';
import { useTranslations } from 'next-intl';
import { useId } from 'react';
import { Badge } from '@/components/ui/badge';
import { Button } from '@/components/ui/button';
import { Card, CardDescription, CardTitle } from '@/components/ui/card';
import { Item, ItemActions, ItemContent, ItemDescription, ItemGroup, ItemTitle } from '@/components/ui/item';
import { Select, SelectContent, SelectItem, SelectTrigger, SelectValue } from '@/components/ui/select';
import { Spinner } from '@/components/ui/spinner';
import { comingDays, type ChoiceForm } from '../client/hours-form';
import type { HolidayChoiceKind, HolidayYear } from '../types';
import { TimeSelect } from './TimeSelect';

type Props = {
  year: HolidayYear | null;
  /**
   * Whether this is the first year card of the tab. Its test ids are the plain ones (`holidays-card`,
   * `holidays-status`, `holidays-days`, `holidays-accept`, `holidays-all-past`); a later year's carry its
   * year (`holidays-card-2027`), so each stays unique.
   */
  first?: boolean;
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
 * The tab shows one card for each published year with days still to come (`shownYears`), each accepted on
 * its own.
 */
export function HolidaysCard({ year, first = true, timeZone, choices, disabled, busy, onChoose, onAccept }: Props) {
  const t = useTranslations('settings.holidays');
  const titleId = useId();
  if (!year)
    return (
      <Card className="gap-3" data-testid="holidays-card">
        <CardTitle className="text-large">{t('titleNone')}</CardTitle>
        <CardDescription data-testid="holidays-none">{t('none')}</CardDescription>
      </Card>
    );
  const accepted = year.version > 0;
  const days = comingDays(year);
  const testId = (name: string) => (first ? `holidays-${name}` : `holidays-${name}-${year.year}`);
  return (
    <Card className="gap-3" data-testid={testId('card')} data-year={year.year}>
      <div className="flex flex-wrap items-center justify-between gap-2">
        <CardTitle id={titleId} className="text-large">
          {t('title', { year: year.year })}
        </CardTitle>
        <Badge variant={year.reviewed ? 'positive' : 'secondary'} data-testid={testId('status')}>
          {year.reviewed ? t('reviewed') : t('toReview')}
        </Badge>
      </div>
      <CardDescription>{t('published', { date: formatDate(year.publishedAt, timeZone) })}</CardDescription>
      {days.length === 0 ? (
        <p className="text-small" data-testid={testId('all-past')}>
          {t('allPast')}
        </p>
      ) : (
        <ItemGroup data-testid={testId('days')}>
          {days.map((day) => {
            const choice = choices[day.date]!;
            const dayLabel = formatDay(day.date);
            return (
              <Item variant="divided" key={day.date} className="justify-between" data-testid={`holiday-${day.date}`}>
                <ItemContent className="flex-auto gap-0.5">
                  <ItemTitle className="gap-1.5">
                    <span>{dayLabel}</span>
                    <Badge variant="secondary">{t(`kinds.${day.kind}`)}</Badge>
                    {day.toReview && accepted && (
                      <Badge variant="outline" data-testid={`holiday-${day.date}-to-review`}>
                        {t('dayToReview')}
                      </Badge>
                    )}
                  </ItemTitle>
                  <ItemDescription>{day.name}</ItemDescription>
                </ItemContent>
                <ItemActions className="justify-end gap-1.5">
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
                </ItemActions>
              </Item>
            );
          })}
        </ItemGroup>
      )}
      {(!accepted || !year.reviewed) && (
        <Button
          variant="outline"
          className="w-full"
          onClick={onAccept}
          disabled={disabled || busy}
          aria-describedby={titleId}
          data-testid={testId('accept')}
        >
          {busy && <Spinner />}
          {t('accept')}
        </Button>
      )}
    </Card>
  );
}
