'use client';
import { formatClock, formatDay, formatShortDay } from '@salon/format';
import { CalendarIcon } from 'lucide-react';
import { useTranslations } from 'next-intl';
import { useState } from 'react';
import { Alert, AlertDescription, AlertTitle } from '@/components/ui/alert';
import { Button } from '@/components/ui/button';
import { Calendar } from '@/components/ui/calendar';
import { Field, FieldLabel } from '@/components/ui/field';
import { Input } from '@/components/ui/input';
import { Item, ItemActions, ItemContent, ItemDescription, ItemGroup, ItemTitle } from '@/components/ui/item';
import { Popover, PopoverContent, PopoverTrigger } from '@/components/ui/popover';
import { Spinner } from '@/components/ui/spinner';
import { ToggleGroup, ToggleGroupItem } from '@/components/ui/toggle-group';
import { dayButtonLabel } from '@/lib/dates/calendar-labels';
import { dateOfText, lastClosableDate, textOfDate } from '../client/hours-form';
import { breakSummaryOf, hoursProblems, type ChangeForm } from '../client/schedule-form';
import type { MemberDateChange } from '../types';
import { HoursBreaks } from './HoursBreaks';
import { TimeSelect } from './TimeSelect';

/** Why a change was refused beside its form: a rule of the date or the note, as the API named it. */
export type ChangeRefusal = 'dateMissing' | 'dateRule' | 'dateTaken' | 'limit' | 'note' | null;

const PROBLEMS_ID = 'change-problems';
const NOTE_MAX = 60;

type Props = {
  today: string;
  changes: MemberDateChange[];
  form: ChangeForm | null;
  refusal: ChangeRefusal;
  /** The rules of the hours the API named, when the form's own checks did not. */
  apiProblems: ReturnType<typeof hoursProblems>;
  canChange: boolean;
  busy: boolean;
  onStart: () => void;
  onForm: (form: ChangeForm) => void;
  onCancel: () => void;
  onSave: () => void;
  onRemove: (change: MemberDateChange) => void;
};

/**
 * The Date changes tab (F06-S02 AC-7, the prototype's `schOv`): the banner, + Change a date and its form, and the changes
 * from today on, each with its Remove. A note arrives only for a caller with `team.manage`, who alone can change them.
 */
export function DateChangesTab(props: Props) {
  const { form, canChange } = props;
  const t = useTranslations('team.schedule');
  return (
    <div className="flex flex-col gap-3" data-testid="schedule-changes">
      {form && canChange ? (
        <ChangeFormFields {...props} form={form} />
      ) : (
        <>
          <Alert variant="info" className="text-small" data-testid="change-banner">
            {t('changesBanner')}
          </Alert>
          {canChange && (
            <Button size="sm" className="w-fit" onClick={props.onStart} data-testid="change-add">
              {t('change.add')}
            </Button>
          )}
          <ChangeList {...props} />
        </>
      )}
    </div>
  );
}

function ChangeList({ today, changes, canChange, busy, onRemove }: Props) {
  const t = useTranslations('team.schedule');
  const card = useTranslations('team.card');
  if (changes.length === 0)
    return (
      <p className="text-small text-foreground" data-testid="change-empty">
        {t('change.empty')}
      </p>
    );
  return (
    <ItemGroup className="rounded-card bg-card px-4" data-testid="change-list">
      {changes.map((change) => {
        const breaks = breakSummaryOf(change.breaks);
        // The short day form of decision 107 (F06-S02 AC-17); the form's date button keeps the year.
        const day = formatShortDay(change.date, today);
        const parts = [
          change.working && change.starts && change.finishes
            ? t('change.hours', { starts: formatClock(change.starts), finishes: formatClock(change.finishes) }) +
              ('at' in breaks
                ? card('oneBreak', { at: formatClock(breaks.at) })
                : breaks.count > 1
                  ? card('manyBreaks', { count: breaks.count })
                  : '')
            : t('change.off'),
          change.note,
        ].filter(Boolean);
        return (
          <Item
            key={change.id}
            variant="divided"
            className="justify-between"
            data-testid="change-row"
            data-date={change.date}
          >
            <ItemContent>
              <ItemTitle data-testid="change-row-date">{day}</ItemTitle>
              <ItemDescription className="wrap-anywhere text-foreground" data-testid="change-row-hours">
                {parts.join(' · ')}
              </ItemDescription>
            </ItemContent>
            {canChange && (
              <ItemActions>
                <Button
                  variant="outline"
                  size="sm"
                  disabled={busy}
                  aria-label={t('change.removeLabel', { date: day })}
                  onClick={() => onRemove(change)}
                  data-testid="change-remove"
                >
                  {t('change.remove')}
                </Button>
              </ItemActions>
            )}
          </Item>
        );
      })}
    </ItemGroup>
  );
}

function ChangeFormFields({
  today,
  form,
  refusal,
  apiProblems,
  busy,
  onForm,
  onCancel,
  onSave,
}: Props & { form: ChangeForm }) {
  const t = useTranslations('team.schedule');
  const [picking, setPicking] = useState(false);
  const last = lastClosableDate(today);
  const rules = [...new Set([...hoursProblems(form), ...apiProblems])];
  const dateRefused = refusal === 'dateMissing' || refusal === 'dateRule' || refusal === 'dateTaken';
  const said = [
    ...rules.map((rule) => t(`change.problems.${rule}`)),
    ...(refusal ? [t(`change.problems.${refusal}`)] : []),
  ];
  const set = (change: Partial<ChangeForm>) => onForm({ ...form, ...change });
  return (
    <div className="flex flex-col gap-3" data-testid="change-form">
      <h3 className="font-semibold">{t('change.title')}</h3>
      {said.length > 0 && (
        <Alert variant="destructive" role="alert" id={PROBLEMS_ID} data-testid="change-problems">
          <AlertTitle className="sr-only">{t('problemsTitle')}</AlertTitle>
          <AlertDescription>
            <ul className="flex flex-col gap-1">
              {said.map((text) => (
                <li key={text} data-testid="change-problem">
                  {text}
                </li>
              ))}
            </ul>
          </AlertDescription>
        </Alert>
      )}
      <Field data-invalid={dateRefused || undefined}>
        <FieldLabel htmlFor="change-date">{t('change.date')}</FieldLabel>
        <Popover open={picking} onOpenChange={setPicking}>
          <PopoverTrigger asChild>
            <Button
              id="change-date"
              variant="outline"
              className="w-full justify-between font-normal"
              disabled={busy}
              aria-describedby={dateRefused ? PROBLEMS_ID : undefined}
              data-testid="change-date"
            >
              {form.date ? formatDay(form.date) : t('change.chooseDate')}
              <CalendarIcon aria-hidden="true" />
            </Button>
          </PopoverTrigger>
          <PopoverContent
            className="w-auto p-0"
            align="start"
            aria-label={t('change.calendarLabel')}
            data-testid="change-calendar"
          >
            <Calendar
              labels={{ labelDayButton: dayButtonLabel }}
              mode="single"
              selected={form.date ? dateOfText(form.date) : undefined}
              defaultMonth={form.date ? dateOfText(form.date) : dateOfText(today)}
              startMonth={dateOfText(today)}
              endMonth={dateOfText(last)}
              disabled={[{ before: dateOfText(today) }, { after: dateOfText(last) }]}
              onSelect={(picked) => {
                if (!picked) return;
                set({ date: textOfDate(picked) });
                setPicking(false);
              }}
            />
          </PopoverContent>
        </Popover>
      </Field>
      <ToggleGroup
        type="single"
        value={form.working ? 'hours' : 'off'}
        onValueChange={(value) => value && set({ working: value === 'hours' })}
        aria-label={t('change.kind')}
        className="flex-nowrap gap-0 rounded-action border border-border bg-background p-0.5"
        data-testid="change-kind"
      >
        <ToggleGroupItem value="hours" variant="segment" disabled={busy} data-testid="change-kind-hours">
          {t('change.different')}
        </ToggleGroupItem>
        <ToggleGroupItem value="off" variant="segment" disabled={busy} data-testid="change-kind-off">
          {t('change.off')}
        </ToggleGroupItem>
      </ToggleGroup>
      {form.working && (
        <>
          <div className="flex flex-wrap items-center gap-1.5">
            <TimeSelect
              narrow
              value={form.starts}
              onChange={(starts) => set({ starts })}
              label={t('change.starts')}
              testId="change-starts"
              disabled={busy}
            />
            <span className="text-small text-foreground">{t('to')}</span>
            <TimeSelect
              narrow
              value={form.finishes}
              onChange={(finishes) => set({ finishes })}
              label={t('change.finishes')}
              testId="change-finishes"
              disabled={busy}
              invalid={rules.includes('finishAfterStart')}
              describedBy={rules.includes('finishAfterStart') ? PROBLEMS_ID : undefined}
            />
          </div>
          <p className="text-small text-foreground">{t('change.breaksHelp')}</p>
          <HoursBreaks
            hours={form}
            testId="change"
            labels={{
              starts: (number) => t('change.breakStarts', { number }),
              ends: (number) => t('change.breakEnds', { number }),
              remove: (number) => t('change.removeBreak', { number }),
              add: t('change.addBreakLabel'),
            }}
            canChange
            disabled={busy}
            onChange={(breaks) => set({ breaks })}
          />
        </>
      )}
      <Field data-invalid={refusal === 'note' || undefined}>
        <FieldLabel htmlFor="change-note" className="flex-row flex-wrap gap-x-1 gap-y-0">
          {t('change.note')}
          <span className="font-normal text-foreground">{t('change.noteHint')}</span>
        </FieldLabel>
        <Input
          id="change-note"
          value={form.note}
          maxLength={NOTE_MAX}
          disabled={busy}
          aria-invalid={refusal === 'note' || undefined}
          aria-describedby={refusal === 'note' ? PROBLEMS_ID : undefined}
          onChange={(event) => set({ note: event.target.value })}
          data-testid="change-note"
        />
      </Field>
      <Button
        type="button"
        className="w-full"
        disabled={rules.length > 0 || busy}
        onClick={onSave}
        data-testid="change-save"
      >
        {busy && <Spinner />}
        {t('change.save')}
      </Button>
      <Button
        type="button"
        variant="outline"
        className="w-full"
        disabled={busy}
        onClick={onCancel}
        data-testid="change-cancel"
      >
        {t('change.cancel')}
      </Button>
    </div>
  );
}
