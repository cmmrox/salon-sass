import { formatMoney } from '@salon/format';
import type { RulesValues } from './rules-form';

/** What a late cancellation or a no-show keeps, as a customer is told it. */
export type Keep = { key: 'nothing' } | { key: 'deposit' } | { key: 'share'; percent: number };

/**
 * One sentence of "What customers see before paying", as a message key and its values. The sentences are
 * composed here from the structured rules, not by the API, so that the customer app later says the same.
 */
export type Sentence =
  | { key: 'depositNone' }
  | { key: 'depositPercentage'; percent: number; cap: string }
  | { key: 'depositAmount'; amount: string }
  | { key: 'freeCancellation'; hours: number }
  | { key: 'afterThat'; late: Keep; noShow: Keep }
  | { key: 'reschedule'; times: 'once' | 'twice' | 'any'; hours: number }
  | { key: 'rescheduleContact' };

/**
 * The prototype's `ruText`: the deposit, the free-cancellation window, what is kept after it and on a no-show
 * (nothing when there is no deposit to keep), and rescheduling. Amounts through `formatMoney`.
 */
export function summaryOf(values: RulesValues, currency: string): Sentence[] {
  const keep = (kind: 'nothing' | 'deposit' | 'share', percent: number): Keep =>
    kind === 'share'
      ? { key: 'share', percent }
      : kind === 'deposit' && values.deposit !== 'none'
        ? { key: 'deposit' }
        : { key: 'nothing' };
  const sentences: Sentence[] = [
    values.deposit === 'percentage'
      ? { key: 'depositPercentage', percent: values.percent, cap: formatMoney(values.capMinor, currency) }
      : values.deposit === 'amount'
        ? { key: 'depositAmount', amount: formatMoney(values.amountMinor, currency) }
        : { key: 'depositNone' },
    { key: 'freeCancellation', hours: values.freeCancellationHours },
    {
      key: 'afterThat',
      late: keep(values.late, values.latePercent),
      noShow: keep(values.noShow, values.noShowPercent),
    },
  ];
  sentences.push(
    values.reschedule === '0'
      ? { key: 'rescheduleContact' }
      : {
          key: 'reschedule',
          times: values.reschedule === '1' ? 'once' : values.reschedule === '2' ? 'twice' : 'any',
          hours: values.rescheduleUntilHours,
        },
  );
  return sentences;
}
