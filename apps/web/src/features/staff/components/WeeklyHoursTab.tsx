'use client';
import { formatClock } from '@salon/format';
import { useTranslations } from 'next-intl';
import { Alert, AlertDescription, AlertTitle } from '@/components/ui/alert';
import { Button } from '@/components/ui/button';
import { Spinner } from '@/components/ui/spinner';
import { Toggle } from '@/components/ui/toggle';
import type { WeekdayName } from '../client/hours-form';
import { outsideOpening, type WeekProblem, type WorkdayForm } from '../client/schedule-form';
import type { Weekday } from '../types';
import { HoursBreaks } from './HoursBreaks';
import { TimeSelect } from './TimeSelect';

const PROBLEMS_ID = 'schedule-problems';

type Props = {
  week: WorkdayForm[];
  salonWeek: Weekday[] | null;
  problems: WeekProblem[];
  /** Whether the form differs from what is saved: **Save hours** is disabled until it does. */
  edited: boolean;
  canChange: boolean;
  saving: boolean;
  onDay: (day: WeekdayName, change: Partial<WorkdayForm>) => void;
  onCopyMonday: () => void;
  onUseSalon: () => void;
  onSave: () => void;
};

/**
 * The Weekly hours tab (F06-S02 AC-2 to AC-5, the prototype's `schWeek`): the banner, the broken rules and the warning, the
 * seven days with their breaks, the two shortcuts and **Save hours**. Without `team.manage` it reads: the controls that
 * show a time are disabled, and there is nothing to add, remove or save (AC-13).
 */
export function WeeklyHoursTab(props: Props) {
  const { week, salonWeek, problems, canChange, saving, onDay } = props;
  const t = useTranslations('team.schedule');
  const hours = useTranslations('settings.hours');
  const outside = outsideOpening(week, salonWeek);
  const noSalonHours = salonWeek === null;
  return (
    <div className="flex flex-col gap-3" data-testid="schedule-week">
      <Alert variant="info" className="text-small" data-testid="schedule-week-banner">
        {t('weekBanner')}
      </Alert>
      {problems.length > 0 && (
        <Alert variant="destructive" role="alert" id={PROBLEMS_ID} data-testid="schedule-problems">
          <AlertTitle className="sr-only">{t('problemsTitle')}</AlertTitle>
          <AlertDescription>
            <ul className="flex flex-col gap-1">
              {problems.map(({ day, problem }) => (
                <li key={`${day}-${problem}`} data-testid="schedule-problem" data-day={day} data-problem={problem}>
                  {t(`problems.${problem}`, { day: hours(`short.${day}`) })}
                </li>
              ))}
            </ul>
          </AlertDescription>
        </Alert>
      )}
      {noSalonHours && (
        <Alert variant="caution" data-testid="schedule-salon-hours-unset">
          <AlertDescription>{t('salonHoursUnset')}</AlertDescription>
        </Alert>
      )}
      {outside.length > 0 && (
        <Alert variant="caution" data-testid="schedule-outside">
          <AlertDescription>
            <p className="m-0">
              <strong className="font-semibold">{t('outsideTitle')}</strong>{' '}
              {outside
                .map((each) =>
                  each.opens && each.closes
                    ? t('outsideOpen', {
                        day: hours(`short.${each.day}`),
                        opens: formatClock(each.opens),
                        closes: formatClock(each.closes),
                      })
                    : t('outsideClosed', { day: hours(`short.${each.day}`) }),
                )
                .join(' ')}{' '}
              {t('outsideTail')}
            </p>
          </AlertDescription>
        </Alert>
      )}
      <div className="flex flex-col divide-y divide-border border-t border-border">
        {week.map((day) => {
          const name = hours(`days.${day.day}`);
          const invalid = problems.some((each) => each.day === day.day);
          return (
            <div key={day.day} className="flex flex-col gap-2 py-2.5" data-testid={`schedule-day-${day.day}`}>
              <div className="flex flex-wrap items-center justify-between gap-2">
                <Toggle
                  variant="chip"
                  pressed={day.working}
                  disabled={!canChange || saving}
                  onPressedChange={(working) => onDay(day.day, { working })}
                  data-testid={`schedule-${day.day}-working`}
                >
                  {/* Named by its content, so the visible "Mon" is part of its name; pressed means working. */}
                  <span aria-hidden="true">{hours(`short.${day.day}`)}</span>
                  <span className="sr-only">{name}</span>
                </Toggle>
                {day.working ? (
                  <div className="flex flex-wrap items-center justify-end gap-1.5">
                    <TimeSelect
                      narrow
                      value={day.starts}
                      onChange={(starts) => onDay(day.day, { starts })}
                      label={t('starts', { day: name })}
                      testId={`schedule-${day.day}-starts`}
                      disabled={!canChange || saving}
                    />
                    <span className="text-small text-foreground">{t('to')}</span>
                    <TimeSelect
                      narrow
                      value={day.finishes}
                      onChange={(finishes) => onDay(day.day, { finishes })}
                      label={t('finishes', { day: name })}
                      testId={`schedule-${day.day}-finishes`}
                      disabled={!canChange || saving}
                      invalid={invalid}
                      describedBy={invalid ? PROBLEMS_ID : undefined}
                    />
                  </div>
                ) : (
                  <span className="text-small text-foreground" data-testid={`schedule-${day.day}-off`}>
                    {t('notWorking')}
                  </span>
                )}
              </div>
              {day.working && (
                <HoursBreaks
                  hours={day}
                  testId={`schedule-${day.day}`}
                  labels={{
                    starts: (number) => t('breakStarts', { day: name, number }),
                    ends: (number) => t('breakEnds', { day: name, number }),
                    remove: (number) => t('removeBreak', { day: name, number }),
                    add: t('addBreakLabel', { day: name }),
                  }}
                  canChange={canChange}
                  disabled={saving}
                  onChange={(breaks) => onDay(day.day, { breaks })}
                />
              )}
            </div>
          );
        })}
      </div>
      {canChange && (
        <>
          <div className="flex flex-wrap gap-2">
            <Button
              type="button"
              variant="outline"
              size="sm"
              disabled={saving || !week[0]?.working}
              onClick={props.onCopyMonday}
              data-testid="schedule-copy-monday"
            >
              {t('copyMonday')}
            </Button>
            <Button
              type="button"
              variant="outline"
              size="sm"
              disabled={saving || noSalonHours}
              aria-describedby={noSalonHours ? 'schedule-no-salon-hours' : undefined}
              onClick={props.onUseSalon}
              data-testid="schedule-use-salon"
            >
              {t('useSalon')}
            </Button>
          </div>
          {noSalonHours && (
            <p
              id="schedule-no-salon-hours"
              className="text-small text-foreground"
              data-testid="schedule-no-salon-hours"
            >
              {t('noSalonHours')}
            </p>
          )}
          <Button
            type="button"
            className="w-full"
            disabled={problems.length > 0 || saving || !props.edited}
            onClick={props.onSave}
            data-testid="schedule-save"
          >
            {saving && <Spinner />}
            {saving ? t('saving') : t('save')}
          </Button>
        </>
      )}
    </div>
  );
}
