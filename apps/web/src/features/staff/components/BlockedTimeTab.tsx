'use client';
import { formatClock, formatDay } from '@salon/format';
import { useTranslations } from 'next-intl';
import { Alert, AlertDescription, AlertTitle } from '@/components/ui/alert';
import { Button } from '@/components/ui/button';
import { Field, FieldLabel } from '@/components/ui/field';
import { Input } from '@/components/ui/input';
import { Item, ItemActions, ItemContent, ItemDescription, ItemGroup, ItemTitle } from '@/components/ui/item';
import { Spinner } from '@/components/ui/spinner';
import { LABEL_MAX, type BlockForm, type BlockRefusal, type ImpactState } from '../client/time-off-form';
import type { MemberBlock } from '../types';
import { DateChooser } from './DateChooser';
import { PeriodImpactNote } from './PeriodImpactNote';
import { TimeSelect } from './TimeSelect';

const PROBLEM_ID = 'block-problem';

type Props = {
  today: string;
  blocks: MemberBlock[];
  form: BlockForm | null;
  refusal: BlockRefusal | null;
  impact: ImpactState;
  canChange: boolean;
  busy: boolean;
  onStart: () => void;
  onForm: (form: BlockForm) => void;
  onCancel: () => void;
  onSave: () => void;
  onRemove: (block: MemberBlock) => void;
};

/**
 * The Blocked time tab (F06-S03 AC-4 to AC-6, the prototype's `schBl`): the banner, + Block time and its form, and the blocks
 * from today on. Everyone who reads the team reads a block's label; `team.block` adds and removes them.
 */
export function BlockedTimeTab(props: Props) {
  const { form, canChange } = props;
  const t = useTranslations('team.schedule.block');
  return (
    <div className="flex flex-col gap-3" data-testid="schedule-blocks">
      {form && canChange ? (
        <BlockFormFields {...props} form={form} />
      ) : (
        <>
          <Alert variant="info" className="text-small" data-testid="block-banner">
            {t('banner')}
          </Alert>
          {canChange && (
            <Button size="sm" className="w-fit" onClick={props.onStart} data-testid="block-add">
              {t('add')}
            </Button>
          )}
          <BlockList {...props} />
        </>
      )}
    </div>
  );
}

function BlockList({ blocks, canChange, busy, onRemove }: Props) {
  const t = useTranslations('team.schedule.block');
  if (blocks.length === 0)
    return (
      <p className="text-small text-foreground" data-testid="block-empty">
        {t('empty')}
      </p>
    );
  return (
    <ItemGroup className="rounded-card bg-card px-4" data-testid="block-list">
      {blocks.map((block) => {
        const when = t('when', {
          date: formatDay(block.date),
          starts: formatClock(block.starts),
          finishes: formatClock(block.finishes),
        });
        return (
          <Item
            key={block.id}
            variant="divided"
            className="justify-between"
            data-testid="block-row"
            data-date={block.date}
          >
            <ItemContent>
              <ItemTitle data-testid="block-row-when">{when}</ItemTitle>
              <ItemDescription className="wrap-anywhere text-foreground" data-testid="block-row-label">
                {block.label}
              </ItemDescription>
            </ItemContent>
            {canChange && (
              <ItemActions>
                <Button
                  variant="outline"
                  size="sm"
                  disabled={busy}
                  aria-label={t('removeLabel', { when })}
                  onClick={() => onRemove(block)}
                  data-testid="block-remove"
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

function BlockFormFields({
  today,
  form,
  refusal,
  impact,
  busy,
  onForm,
  onCancel,
  onSave,
}: Props & { form: BlockForm }) {
  const t = useTranslations('team.schedule.block');
  const set = (change: Partial<BlockForm>) => onForm({ ...form, ...change });
  const dateRefused = refusal === 'dateMissing' || refusal === 'dateRule';
  const timesRefused = refusal === 'endAfterStart' || refusal === 'overlap';
  return (
    <div className="flex flex-col gap-3" data-testid="block-form">
      <h3 className="font-semibold">{t('title')}</h3>
      {refusal && (
        <Alert variant="destructive" role="alert" id={PROBLEM_ID} data-testid="block-problem">
          <AlertTitle className="sr-only">{t('problemsTitle')}</AlertTitle>
          <AlertDescription>{t(`problems.${refusal}`)}</AlertDescription>
        </Alert>
      )}
      <Field data-invalid={dateRefused || undefined}>
        <FieldLabel htmlFor="block-date">{t('date')}</FieldLabel>
        <DateChooser
          id="block-date"
          today={today}
          value={form.date}
          placeholder={t('chooseDate')}
          calendarLabel={t('calendarLabel')}
          disabled={busy}
          describedBy={dateRefused ? PROBLEM_ID : undefined}
          testId="block-date"
          onChange={(date) => set({ date })}
        />
      </Field>
      <div className="flex flex-wrap items-center gap-1.5">
        <TimeSelect
          narrow
          value={form.starts}
          onChange={(starts) => set({ starts })}
          label={t('from')}
          testId="block-starts"
          disabled={busy}
        />
        <span className="text-small text-foreground">{t('to')}</span>
        <TimeSelect
          narrow
          value={form.finishes}
          onChange={(finishes) => set({ finishes })}
          label={t('until')}
          testId="block-finishes"
          disabled={busy}
          invalid={timesRefused || undefined}
          describedBy={timesRefused ? PROBLEM_ID : undefined}
        />
      </div>
      <Field data-invalid={refusal === 'label' || undefined}>
        <FieldLabel htmlFor="block-label">{t('label')}</FieldLabel>
        <Input
          id="block-label"
          value={form.label}
          maxLength={LABEL_MAX}
          placeholder={t('labelPlaceholder')}
          disabled={busy}
          aria-invalid={refusal === 'label' || undefined}
          aria-describedby={refusal === 'label' ? PROBLEM_ID : undefined}
          onChange={(event) => set({ label: event.target.value })}
          data-testid="block-label"
        />
      </Field>
      <PeriodImpactNote impact={impact} />
      <Button type="button" className="w-full" disabled={busy} onClick={onSave} data-testid="block-save">
        {busy && <Spinner />}
        {t('save')}
      </Button>
      <Button
        type="button"
        variant="outline"
        className="w-full"
        disabled={busy}
        onClick={onCancel}
        data-testid="block-cancel"
      >
        {t('cancel')}
      </Button>
    </div>
  );
}
