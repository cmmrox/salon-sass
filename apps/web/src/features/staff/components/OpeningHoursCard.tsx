'use client';
import { useTranslations } from 'next-intl';
import { Alert, AlertDescription, AlertTitle } from '@/components/ui/alert';
import { Badge } from '@/components/ui/badge';
import { Button } from '@/components/ui/button';
import { Card, CardDescription, CardTitle } from '@/components/ui/card';
import { FieldError } from '@/components/ui/field';
import { Item, ItemActions, ItemContent, ItemDescription, ItemGroup } from '@/components/ui/item';
import { Spinner } from '@/components/ui/spinner';
import { Toggle } from '@/components/ui/toggle';
import type { DayForm, WeekdayName } from '../client/hours-form';
import type { AffectedBooking } from '../types';
import { TimeSelect } from './TimeSelect';

type Props = {
  week: DayForm[];
  saved: boolean;
  bad: WeekdayName[];
  outside: AffectedBooking[];
  disabled: boolean;
  saving: boolean;
  dirty: boolean;
  onChange: (day: WeekdayName, change: Partial<DayForm>) => void;
  onSave: () => void;
};

/**
 * The Opening hours card (F04-S03, the prototype's `setHours`): each weekday a Toggle (open or closed) and,
 * when open, two time Selects; the bookings the saved hours leave outside, kept; and Save hours, which saves
 * at once. Before the first save it shows the suggested week, marked "Not saved yet".
 */
export function OpeningHoursCard({ week, saved, bad, outside, disabled, saving, dirty, onChange, onSave }: Props) {
  const t = useTranslations('settings.hours');
  return (
    <Card className="gap-3" data-testid="hours-card">
      <div className="flex flex-wrap items-center justify-between gap-2">
        <CardTitle className="text-large">{t('title')}</CardTitle>
        {!saved && (
          <Badge variant="secondary" data-testid="hours-not-saved">
            {t('notSaved')}
          </Badge>
        )}
      </div>
      <CardDescription>{t('help')}</CardDescription>
      <ItemGroup data-testid="hours-week">
        {week.map((day) => {
          const name = t(`days.${day.day}`);
          const error = bad.includes(day.day);
          return (
            <Item variant="divided" key={day.day} className="justify-between" data-testid={`hours-day-${day.day}`}>
              <ItemContent className="flex-none">
                <Toggle
                  variant="chip"
                  pressed={day.open}
                  onPressedChange={(open) => onChange(day.day, { open })}
                  disabled={disabled}
                  data-testid={`hours-${day.day}-open`}
                >
                  {/* Named by its content, so the visible "Mon" is part of its name; pressed means open. */}
                  <span aria-hidden="true">{t(`short.${day.day}`)}</span>
                  <span className="sr-only">{name}</span>
                </Toggle>
              </ItemContent>
              {day.open ? (
                <ItemActions className="gap-1.5">
                  <TimeSelect
                    value={day.opens}
                    onChange={(opens) => onChange(day.day, { opens })}
                    label={t('opens', { day: name })}
                    testId={`hours-${day.day}-opens`}
                    disabled={disabled}
                  />
                  <span className="text-small">{t('to')}</span>
                  <TimeSelect
                    value={day.closes}
                    onChange={(closes) => onChange(day.day, { closes })}
                    label={t('closes', { day: name })}
                    testId={`hours-${day.day}-closes`}
                    disabled={disabled}
                    invalid={error}
                    describedBy={error ? `hours-${day.day}-error` : undefined}
                  />
                  {error && (
                    <FieldError
                      id={`hours-${day.day}-error`}
                      className="basis-full text-right"
                      data-testid={`hours-${day.day}-error`}
                    >
                      {t('closesAfterOpens')}
                    </FieldError>
                  )}
                </ItemActions>
              ) : (
                <ItemDescription data-testid={`hours-${day.day}-closed`}>{t('closed')}</ItemDescription>
              )}
            </Item>
          );
        })}
      </ItemGroup>
      {outside.length > 0 && (
        <Alert variant="caution" data-testid="hours-outside">
          <AlertTitle>{t('outsideTitle', { count: outside.length })}</AlertTitle>
          <AlertDescription>{t('outsideBody')}</AlertDescription>
        </Alert>
      )}
      <Button
        className="w-full"
        onClick={onSave}
        disabled={disabled || saving || (saved && !dirty)}
        data-testid="hours-save"
      >
        {saving && <Spinner />}
        {saving ? t('saving') : t('save')}
      </Button>
    </Card>
  );
}
