'use client';
import { formatShortDay, formatShortPeriod } from '@salon/format';
import { useTranslations } from 'next-intl';
import { Alert, AlertDescription, AlertTitle } from '@/components/ui/alert';
import { Badge } from '@/components/ui/badge';
import { Button } from '@/components/ui/button';
import { Field, FieldLabel } from '@/components/ui/field';
import { Input } from '@/components/ui/input';
import { Item, ItemActions, ItemContent, ItemDescription, ItemGroup, ItemTitle } from '@/components/ui/item';
import { Select, SelectContent, SelectItem, SelectTrigger, SelectValue } from '@/components/ui/select';
import { Spinner } from '@/components/ui/spinner';
import {
  LEAVE_TYPES,
  NOTE_MAX,
  type ImpactState,
  type LeaveForm,
  type LeaveRefusal,
  type LeaveType,
  withFirstDay,
} from '../client/time-off-form';
import type { MemberLeave } from '../types';
import { DateChooser } from './DateChooser';
import { PeriodImpactNote } from './PeriodImpactNote';

const PROBLEM_ID = 'leave-problem';

type Props = {
  today: string;
  leave: MemberLeave[];
  form: LeaveForm | null;
  refusal: LeaveRefusal | null;
  impact: ImpactState;
  canChange: boolean;
  busy: boolean;
  onStart: () => void;
  onForm: (form: LeaveForm) => void;
  onCancel: () => void;
  onSave: () => void;
  onRemove: (leave: MemberLeave) => void;
};

/**
 * The Leave tab (F06-S03 AC-1 to AC-3, the prototype's `schLv`): the banner, + Add leave and its form, and the leave that has
 * not ended. A leave's type and note arrive only for a caller with `team.manage`, who alone can change it; others read its days.
 */
export function LeaveTab(props: Props) {
  const { form, canChange } = props;
  const t = useTranslations('team.schedule');
  return (
    <div className="flex flex-col gap-3" data-testid="schedule-leave">
      {form && canChange ? (
        <LeaveFormFields {...props} form={form} />
      ) : (
        <>
          <Alert variant="info" className="text-small" data-testid="leave-banner">
            {t('leave.banner')}
          </Alert>
          {canChange && (
            <Button size="sm" className="w-fit" onClick={props.onStart} data-testid="leave-add">
              {t('leave.add')}
            </Button>
          )}
          <LeaveList {...props} />
        </>
      )}
    </div>
  );
}

function LeaveList({ today, leave, canChange, busy, onRemove }: Props) {
  const t = useTranslations('team.schedule.leave');
  const days = useTranslations('team.leaveDays');
  const types = useTranslations('team.leaveTypes');
  if (leave.length === 0)
    return (
      <p className="text-small text-foreground" data-testid="leave-empty">
        {t('empty')}
      </p>
    );
  return (
    <ItemGroup className="rounded-card bg-card px-4" data-testid="leave-list">
      {leave.map((each) => {
        // The short day form of decision 107 (F06-S03 AC-16): no year within the salon's current year.
        const when =
          each.firstDay === each.lastDay
            ? formatShortDay(each.firstDay, today)
            : days('range', formatShortPeriod(each.firstDay, each.lastDay, today));
        return (
          <Item
            key={each.id}
            variant="divided"
            className="justify-between"
            data-testid="leave-row"
            data-first-day={each.firstDay}
          >
            <ItemContent>
              <ItemTitle className="flex-wrap" data-testid="leave-row-title">
                <span data-testid="leave-row-days">{when}</span>
                {each.type && (
                  <Badge variant="secondary" data-testid="leave-row-type">
                    {types(each.type)}
                  </Badge>
                )}
              </ItemTitle>
              {each.bookingsToResolve > 0 && (
                <Badge variant="caution" className="mt-1" data-testid="leave-row-resolve">
                  {t('toResolve', { count: each.bookingsToResolve })}
                </Badge>
              )}
              {each.note && (
                <ItemDescription className="wrap-anywhere text-foreground" data-testid="leave-row-note">
                  {each.note}
                </ItemDescription>
              )}
            </ItemContent>
            {canChange && (
              <ItemActions>
                <Button
                  variant="outline"
                  size="sm"
                  disabled={busy}
                  aria-label={t('removeLabel', { days: when })}
                  onClick={() => onRemove(each)}
                  data-testid="leave-remove"
                >
                  {t('remove')}
                </Button>
              </ItemActions>
            )}
          </Item>
        );
      })}
    </ItemGroup>
  );
}

function LeaveFormFields({
  today,
  form,
  refusal,
  impact,
  busy,
  onForm,
  onCancel,
  onSave,
}: Props & { form: LeaveForm }) {
  const t = useTranslations('team.schedule.leave');
  const types = useTranslations('team.leaveTypes');
  const set = (change: Partial<LeaveForm>) => onForm({ ...form, ...change });
  const daysRefused =
    refusal === 'daysMissing' || refusal === 'lastBeforeFirst' || refusal === 'daysRule' || refusal === 'overlap';
  return (
    <div className="flex flex-col gap-3" data-testid="leave-form">
      <h3 className="font-semibold">{t('title')}</h3>
      {refusal && (
        <Alert variant="destructive" role="alert" id={PROBLEM_ID} data-testid="leave-problem">
          <AlertTitle className="sr-only">{t('problemsTitle')}</AlertTitle>
          <AlertDescription>{t(`problems.${refusal}`)}</AlertDescription>
        </Alert>
      )}
      <div className="grid grid-cols-1 gap-2.5 min-[420px]:grid-cols-2">
        <Field data-invalid={daysRefused || undefined}>
          <FieldLabel htmlFor="leave-first">{t('firstDay')}</FieldLabel>
          <DateChooser
            id="leave-first"
            today={today}
            value={form.firstDay}
            placeholder={t('chooseDay')}
            calendarLabel={t('firstDayCalendar')}
            disabled={busy}
            describedBy={daysRefused ? PROBLEM_ID : undefined}
            testId="leave-first"
            onChange={(firstDay) => onForm(withFirstDay(form, firstDay))}
          />
        </Field>
        <Field data-invalid={daysRefused || undefined}>
          <FieldLabel htmlFor="leave-last">{t('lastDay')}</FieldLabel>
          <DateChooser
            id="leave-last"
            today={today}
            earliest={form.firstDay}
            value={form.lastDay}
            placeholder={t('chooseDay')}
            calendarLabel={t('lastDayCalendar')}
            disabled={busy}
            describedBy={daysRefused ? PROBLEM_ID : undefined}
            testId="leave-last"
            onChange={(lastDay) => set({ lastDay })}
          />
        </Field>
      </div>
      <Field>
        <FieldLabel htmlFor="leave-type">{t('type')}</FieldLabel>
        <Select value={form.type} onValueChange={(type) => set({ type: type as LeaveType })} disabled={busy}>
          <SelectTrigger id="leave-type" className="w-full" data-testid="leave-type">
            <SelectValue />
          </SelectTrigger>
          <SelectContent position="popper" sideOffset={4}>
            {LEAVE_TYPES.map((type) => (
              <SelectItem key={type} value={type} data-testid={`leave-type-option-${type}`}>
                {types(type)}
              </SelectItem>
            ))}
          </SelectContent>
        </Select>
      </Field>
      <Field data-invalid={refusal === 'note' || undefined}>
        <FieldLabel htmlFor="leave-note" className="flex-row flex-wrap gap-x-1 gap-y-0">
          {t('note')}
          <span className="font-normal text-foreground">{t('noteHint')}</span>
        </FieldLabel>
        <Input
          id="leave-note"
          value={form.note}
          maxLength={NOTE_MAX}
          disabled={busy}
          aria-invalid={refusal === 'note' || undefined}
          aria-describedby={refusal === 'note' ? PROBLEM_ID : undefined}
          onChange={(event) => set({ note: event.target.value })}
          data-testid="leave-note"
        />
      </Field>
      <PeriodImpactNote impact={impact} />
      <Button type="button" className="w-full" disabled={busy} onClick={onSave} data-testid="leave-save">
        {busy && <Spinner />}
        {t('save')}
      </Button>
      <Button
        type="button"
        variant="outline"
        className="w-full"
        disabled={busy}
        onClick={onCancel}
        data-testid="leave-cancel"
      >
        {t('cancel')}
      </Button>
    </div>
  );
}
