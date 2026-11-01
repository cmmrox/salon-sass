import type { BookingRules, BookingRulesInput } from '../types';

/**
 * The Booking rules form (F04-S04, the prototype's `setRules` and `ruNum`): the choices each rule offers, the
 * rules as the form holds them, and the checks the prototype makes as the person types. Amounts are typed as
 * whole rupees and kept in minor units (a rupee is 100, as `formatMoney` counts it); percentages are whole
 * numbers. The API checks everything again; this is for the person's convenience.
 */

export const SLOT_INTERVALS = [10, 15, 20, 30] as const;
export const NOTICES = [0, 30, 60, 120, 240, 1440] as const;
/** Two weeks, and one, two, three and six months, as days. */
export const HORIZONS = [14, 30, 60, 90, 180] as const;
export const FREE_CANCELLATION = [2, 6, 12, 24, 48] as const;
export const RESCHEDULE_UNTIL = [2, 6, 12, 24] as const;
/** Not online, once, twice, any number of times (`null` to the API). */
export const RESCHEDULE_LIMITS = ['0', '1', '2', 'any'] as const;

export type DepositKind = 'none' | 'percentage' | 'amount';
export type LateKind = 'nothing' | 'deposit' | 'share';
export type NoShowKind = 'deposit' | 'share';
export type RescheduleLimit = (typeof RESCHEDULE_LIMITS)[number];

/** A typed number field of the form. */
export type NumberField = 'percent' | 'cap' | 'amount' | 'latePercent' | 'noShowPercent';

/** What a typed number field's check found: nothing, or the prototype's message for it. */
export type FieldError = 'percentRange' | 'capMinimum' | 'capMaximum' | 'amountRange';

/**
 * The rules as the form holds them. The numbers are always valid (the last valid value the person typed), so
 * the summary always says something true; `texts` holds what is in each number field, valid or not.
 */
export type RulesValues = {
  slotIntervalMinutes: number;
  minimumNoticeMinutes: number;
  bookingHorizonDays: number;
  deposit: DepositKind;
  /** Whole percent. */
  percent: number;
  /** Minor units. */
  capMinor: number;
  /** Minor units. */
  amountMinor: number;
  freeCancellationHours: number;
  late: LateKind;
  latePercent: number;
  noShow: NoShowKind;
  noShowPercent: number;
  reschedule: RescheduleLimit;
  rescheduleUntilHours: number;
};

export type RulesForm = {
  values: RulesValues;
  texts: Record<NumberField, string>;
  errors: Partial<Record<NumberField, FieldError>>;
};

const MINOR_PER_RUPEE = 100;
const MIN_CAP_MINOR = 500 * MINOR_PER_RUPEE;
const MIN_AMOUNT_MINOR = 100 * MINOR_PER_RUPEE;
const MAX_AMOUNT_MINOR = 100_000 * MINOR_PER_RUPEE;
/** The most any client holds exactly in minor units (2^53 - 1), the API's bound for the cap. */
const MAX_SAFE_MINOR = Number.MAX_SAFE_INTEGER;

/** The prototype's suggested rules, shown until the salon first saves (AC-1). */
export const SUGGESTED: RulesValues = {
  slotIntervalMinutes: 15,
  minimumNoticeMinutes: 60,
  bookingHorizonDays: 60,
  deposit: 'percentage',
  percent: 20,
  capMinor: 2_000 * MINOR_PER_RUPEE,
  amountMinor: 1_000 * MINOR_PER_RUPEE,
  freeCancellationHours: 24,
  late: 'deposit',
  latePercent: 50,
  noShow: 'deposit',
  noShowPercent: 100,
  reschedule: '2',
  rescheduleUntilHours: 12,
};

/** Whole rupees from minor units, as a number field shows them. */
const rupees = (minor: number) => String(Math.floor(minor / MINOR_PER_RUPEE));

function textsOf(values: RulesValues): Record<NumberField, string> {
  return {
    percent: String(values.percent),
    cap: rupees(values.capMinor),
    amount: rupees(values.amountMinor),
    latePercent: String(values.latePercent),
    noShowPercent: String(values.noShowPercent),
  };
}

/** The values of saved rules, or the suggested ones before the first save; fields the rules leave out suggested. */
export function valuesOf(rules: BookingRules | null): RulesValues {
  if (!rules) return { ...SUGGESTED };
  const { deposit, lateCancellation, noShow, reschedule } = rules;
  return {
    slotIntervalMinutes: rules.slotIntervalMinutes,
    minimumNoticeMinutes: rules.minimumNoticeMinutes,
    bookingHorizonDays: rules.bookingHorizonDays,
    deposit: deposit.kind,
    percent: deposit.percent ?? SUGGESTED.percent,
    capMinor: deposit.capMinor ?? SUGGESTED.capMinor,
    amountMinor: deposit.amountMinor ?? SUGGESTED.amountMinor,
    freeCancellationHours: rules.freeCancellationHours,
    late: lateCancellation.kind,
    latePercent: lateCancellation.percent ?? SUGGESTED.latePercent,
    noShow: noShow.kind === 'share' ? 'share' : 'deposit',
    noShowPercent: noShow.percent ?? SUGGESTED.noShowPercent,
    reschedule: reschedule.limit === null ? 'any' : (String(reschedule.limit) as RescheduleLimit),
    rescheduleUntilHours: reschedule.untilHours ?? SUGGESTED.rescheduleUntilHours,
  };
}

export function formOf(rules: BookingRules | null): RulesForm {
  const values = valuesOf(rules);
  return { values, texts: textsOf(values), errors: {} };
}

/** A whole number typed in a field (`2,000` included), or null when it is not one. */
function whole(text: string): number | null {
  const value = text.trim().replace(/,/g, '');
  return /^\d{1,16}$/.test(value) ? Number(value) : null;
}

/** The prototype's check of one typed number (`ruNum`): the value it gives, or the reason it is refused. */
export function checkNumber(field: NumberField, text: string): { value: number } | { error: FieldError } {
  const typed = whole(text);
  switch (field) {
    case 'cap': {
      const minor = typed === null ? null : typed * MINOR_PER_RUPEE;
      if (minor === null || minor < MIN_CAP_MINOR) return { error: 'capMinimum' };
      if (minor > MAX_SAFE_MINOR) return { error: 'capMaximum' };
      return { value: minor };
    }
    case 'amount': {
      const minor = typed === null ? null : typed * MINOR_PER_RUPEE;
      if (minor === null || minor < MIN_AMOUNT_MINOR || minor > MAX_AMOUNT_MINOR) return { error: 'amountRange' };
      return { value: minor };
    }
    default:
      if (typed === null || typed < 1 || typed > 100) return { error: 'percentRange' };
      return { value: typed };
  }
}

const VALUE_OF: Record<NumberField, keyof RulesValues> = {
  percent: 'percent',
  cap: 'capMinor',
  amount: 'amountMinor',
  latePercent: 'latePercent',
  noShowPercent: 'noShowPercent',
};

/** The form after the person types in a number field: its value moves only when the text is valid. */
export function typeNumber(form: RulesForm, field: NumberField, text: string): RulesForm {
  const checked = checkNumber(field, text);
  const errors = { ...form.errors };
  delete errors[field];
  if ('error' in checked) errors[field] = checked.error;
  return {
    values: 'value' in checked ? { ...form.values, [VALUE_OF[field]]: checked.value } : form.values,
    texts: { ...form.texts, [field]: text },
    errors,
  };
}

/** The form after the person chooses something from a list or the deposit kind. */
export function choose<K extends keyof RulesValues>(form: RulesForm, key: K, value: RulesValues[K]): RulesForm {
  return { ...form, values: { ...form.values, [key]: value } };
}

/** The number fields the current choices show: a hidden field's error does not hold the save back. */
function shownFields(values: RulesValues): NumberField[] {
  const shown: NumberField[] = [];
  if (values.deposit === 'percentage') shown.push('percent', 'cap');
  if (values.deposit === 'amount') shown.push('amount');
  if (values.late === 'share') shown.push('latePercent');
  if (values.noShow === 'share') shown.push('noShowPercent');
  return shown;
}

/** The errors of the fields on screen; Save booking rules is disabled while there is any. */
export function shownErrors(form: RulesForm): Partial<Record<NumberField, FieldError>> {
  return Object.fromEntries(
    shownFields(form.values).flatMap((field) => {
      const error = form.errors[field];
      return error ? [[field, error]] : [];
    }),
  );
}

/** The rules as the API takes them: only the fields of each kind. */
export function saveOf(values: RulesValues): BookingRulesInput {
  return {
    slotIntervalMinutes: values.slotIntervalMinutes,
    minimumNoticeMinutes: values.minimumNoticeMinutes,
    bookingHorizonDays: values.bookingHorizonDays,
    deposit:
      values.deposit === 'percentage'
        ? { kind: 'percentage', percent: values.percent, capMinor: values.capMinor }
        : values.deposit === 'amount'
          ? { kind: 'amount', amountMinor: values.amountMinor }
          : { kind: 'none' },
    freeCancellationHours: values.freeCancellationHours,
    lateCancellation: values.late === 'share' ? { kind: 'share', percent: values.latePercent } : { kind: values.late },
    noShow: values.noShow === 'share' ? { kind: 'share', percent: values.noShowPercent } : { kind: 'deposit' },
    reschedule:
      values.reschedule === '0'
        ? { limit: 0, untilHours: null }
        : {
            limit: values.reschedule === 'any' ? null : Number(values.reschedule),
            untilHours: values.rescheduleUntilHours,
          },
  };
}

/** Whether two sets of values save the same rules (what a hidden field holds does not count). */
export function sameRules(a: RulesValues, b: RulesValues): boolean {
  return JSON.stringify(saveOf(a)) === JSON.stringify(saveOf(b));
}

/** The number field an API field error names, or null for a field the form has no input for. */
export function fieldOfError(field: string): NumberField | null {
  const fields: Record<string, NumberField> = {
    'rules.deposit.percent': 'percent',
    'rules.deposit.capMinor': 'cap',
    'rules.deposit.amountMinor': 'amount',
    'rules.lateCancellation.percent': 'latePercent',
    'rules.noShow.percent': 'noShowPercent',
  };
  return fields[field] ?? null;
}

/** The prototype's message for an API refusal of a number field. */
export function errorOfField(field: NumberField): FieldError {
  return field === 'cap' ? 'capMinimum' : field === 'amount' ? 'amountRange' : 'percentRange';
}
