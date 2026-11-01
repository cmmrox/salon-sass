'use client';
import { currencyMark, formatMoney } from '@salon/format';
import { useTranslations } from 'next-intl';
import { FieldGroup } from '@/components/ui/field';
import { ToggleGroup, ToggleGroupItem } from '@/components/ui/toggle-group';
import {
  FREE_CANCELLATION,
  HORIZONS,
  NOTICES,
  RESCHEDULE_LIMITS,
  RESCHEDULE_UNTIL,
  SLOT_INTERVALS,
  type DepositKind,
  type FieldError,
  type LateKind,
  type NoShowKind,
  type NumberField,
  type RescheduleLimit,
  type RulesForm,
  type RulesValues,
} from '../client/rules-form';
import { RuleNumber } from './RuleNumber';
import { RuleSelect } from './RuleSelect';

type Props = {
  form: RulesForm;
  errors: Partial<Record<NumberField, FieldError>>;
  currency: string;
  disabled: boolean;
  onChoose: <K extends keyof RulesValues>(key: K, value: RulesValues[K]) => void;
  onType: (field: NumberField, text: string) => void;
};

const DEPOSIT_KINDS: DepositKind[] = ['none', 'percentage', 'amount'];
const LATE_KINDS: LateKind[] = ['nothing', 'deposit', 'share'];
const NO_SHOW_KINDS: NoShowKind[] = ['deposit', 'share'];
const heading = 'm-0 mt-2 text-large font-semibold first:mt-0';

/**
 * The Booking rules form (F04-S04, the prototype's `setRules`): Booking times, Deposit (a Toggle Group of No
 * deposit, Percentage and Fixed amount, with its amounts), Cancelling and no-shows, and Rescheduling. Every
 * list is a Select with the prototype's labels; every typed number shows its rule's message beside it.
 */
export function BookingRulesForm({ form, errors, currency, disabled, onChoose, onType }: Props) {
  const t = useTranslations('settings.rules');
  const { values, texts } = form;
  const mark = currencyMark(currency);
  const message = (error: FieldError | undefined) => {
    if (!error) return null;
    if (error === 'capMinimum') return t('errors.capMinimum', { min: formatMoney(50_000, currency) });
    if (error === 'capMaximum')
      return t('errors.capMaximum', { max: formatMoney(Math.floor(Number.MAX_SAFE_INTEGER / 100) * 100, currency) });
    if (error === 'amountRange')
      return t('errors.amountRange', { min: formatMoney(10_000, currency), max: formatMoney(10_000_000, currency) });
    return t('errors.percentRange');
  };
  const number = (field: NumberField, id: string, label: string, unit: string, context?: string) => (
    <RuleNumber
      id={id}
      label={label}
      context={context}
      unit={unit}
      value={texts[field]}
      error={message(errors[field])}
      onChange={(text) => onType(field, text)}
      disabled={disabled}
      testId={id}
    />
  );
  const hours = (list: readonly number[]) =>
    list.map((each) => ({ value: String(each), label: t('hoursBefore', { hours: each }) }));

  return (
    <div className="flex flex-col gap-4" data-testid="rules-form">
      <h2 className={heading}>{t('times')}</h2>
      <FieldGroup className="gap-4">
        <RuleSelect
          id="rules-interval"
          label={t('interval')}
          value={String(values.slotIntervalMinutes)}
          options={SLOT_INTERVALS.map((each) => ({
            value: String(each),
            label: t('intervalOption', { minutes: each }),
          }))}
          onChange={(value) => onChoose('slotIntervalMinutes', Number(value))}
          disabled={disabled}
          testId="rules-interval"
        />
        <RuleSelect
          id="rules-notice"
          label={t('notice')}
          value={String(values.minimumNoticeMinutes)}
          options={NOTICES.map((each) => ({ value: String(each), label: t(`noticeOption.${each}`) }))}
          onChange={(value) => onChoose('minimumNoticeMinutes', Number(value))}
          disabled={disabled}
          testId="rules-notice"
        />
        <RuleSelect
          id="rules-horizon"
          label={t('horizon')}
          value={String(values.bookingHorizonDays)}
          options={HORIZONS.map((each) => ({ value: String(each), label: t(`horizonOption.${each}`) }))}
          onChange={(value) => onChoose('bookingHorizonDays', Number(value))}
          disabled={disabled}
          testId="rules-horizon"
        />
      </FieldGroup>

      <h2 className={heading} id="rules-deposit-heading">
        {t('deposit')}
      </h2>
      <ToggleGroup
        type="single"
        value={values.deposit}
        onValueChange={(value) => value && onChoose('deposit', value as DepositKind)}
        disabled={disabled}
        aria-labelledby="rules-deposit-heading"
        data-testid="rules-deposit"
      >
        {DEPOSIT_KINDS.map((kind) => (
          <ToggleGroupItem key={kind} value={kind} variant="chip" data-testid={`rules-deposit-${kind}`}>
            {t(`depositKind.${kind}`)}
          </ToggleGroupItem>
        ))}
      </ToggleGroup>
      {values.deposit === 'percentage' && (
        <FieldGroup className="gap-4">
          {number('percent', 'rules-percent', t('depositPercent'), t('percentOfPrice'))}
          {number('cap', 'rules-cap', t('cap'), mark)}
        </FieldGroup>
      )}
      {values.deposit === 'amount' && number('amount', 'rules-amount', t('amount'), t('perBooking', { mark }))}
      {values.deposit !== 'none' && (
        <p className="m-0 text-small text-foreground" data-testid="rules-service-deposit">
          {t('serviceDeposit')}
        </p>
      )}

      <h2 className={heading}>{t('cancelling')}</h2>
      <FieldGroup className="gap-4">
        <RuleSelect
          id="rules-free"
          label={t('free')}
          value={String(values.freeCancellationHours)}
          options={hours(FREE_CANCELLATION)}
          onChange={(value) => onChoose('freeCancellationHours', Number(value))}
          disabled={disabled}
          testId="rules-free"
        />
        <RuleSelect
          id="rules-late"
          label={t('late')}
          value={values.late}
          options={LATE_KINDS.map((kind) => ({ value: kind, label: t(`lateOption.${kind}`) }))}
          onChange={(value) => onChoose('late', value as LateKind)}
          disabled={disabled}
          testId="rules-late"
        />
        {values.late === 'share' &&
          number('latePercent', 'rules-late-percent', t('shareKept'), t('percentOfPrice'), t('shareKeptLate'))}
        <RuleSelect
          id="rules-noshow"
          label={t('noShow')}
          value={values.noShow}
          options={NO_SHOW_KINDS.map((kind) => ({ value: kind, label: t(`noShowOption.${kind}`) }))}
          onChange={(value) => onChoose('noShow', value as NoShowKind)}
          disabled={disabled}
          testId="rules-noshow"
        />
        {values.noShow === 'share' &&
          number('noShowPercent', 'rules-noshow-percent', t('shareKept'), t('percentOfPrice'), t('shareKeptNoShow'))}
      </FieldGroup>

      <h2 className={heading}>{t('rescheduling')}</h2>
      <FieldGroup className="gap-4">
        <RuleSelect
          id="rules-reschedule"
          label={t('reschedule')}
          value={values.reschedule}
          options={RESCHEDULE_LIMITS.map((limit) => ({ value: limit, label: t(`rescheduleOption.${limit}`) }))}
          onChange={(value) => onChoose('reschedule', value as RescheduleLimit)}
          disabled={disabled}
          testId="rules-reschedule"
        />
        {values.reschedule !== '0' && (
          <RuleSelect
            id="rules-until"
            label={t('until')}
            value={String(values.rescheduleUntilHours)}
            options={hours(RESCHEDULE_UNTIL)}
            onChange={(value) => onChoose('rescheduleUntilHours', Number(value))}
            disabled={disabled}
            testId="rules-until"
          />
        )}
      </FieldGroup>
    </div>
  );
}
