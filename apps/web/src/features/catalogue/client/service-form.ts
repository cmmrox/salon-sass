import { formatMoney } from '@salon/format';
import type { CatalogueService } from '../types';

/**
 * The service sheet's form (F05-S01 AC-3, F05-S02 AC-1, AC-5, AC-7; the prototype's `seErr`): what the person typed,
 * checked for their convenience as they type; the API checks for real. Prices are typed in whole rupees and sent in
 * minor units (a rupee is 100, as `formatMoney` counts it); time and buffer in minutes.
 */
export type ServiceValues = {
  name: string;
  categoryId: string;
  description: string;
  /** The service's own price and time, used while it has no variants. */
  price: string;
  minutes: string;
  buffer: string;
  bookableOnline: boolean;
  /** Whether the service has its one set of variants (F05-S02). */
  hasVariants: boolean;
  /** The set's name, such as "Hair length". */
  setName: string;
  variants: VariantValues[];
  deposit: DepositValues;
  /** Chosen for Popular; saved by its own call, not part of the service's terms. */
  popular: boolean;
};

/**
 * One variant as typed.
 *
 * @property key the row's own key in the sheet, for React
 * @property id the variant's id, null for one added in the sheet
 */
export type VariantValues = { key: string; id: string | null; name: string; price: string; minutes: string };

/** The deposit as chosen: the salon's rule, a custom percentage or amount, or none. */
export type DepositValues = {
  choice: 'salon_rule' | 'custom' | 'none';
  customKind: 'percentage' | 'amount';
  percent: string;
  amount: string;
};

/** A broken rule of the service itself, named for its message, in the prototype's order. */
type ServiceRule =
  | 'nameLength'
  | 'nameTaken'
  | 'category'
  | 'description'
  | 'buffer'
  | 'price'
  | 'time'
  | 'variantCount'
  | 'setName'
  | 'depositPercent'
  | 'depositAmount'
  | 'depositAboveLowest';

/** A broken rule of one variant: named for its message and the variant's place, as `variantPrice:2`. */
export type VariantRule = 'variantName' | 'variantTwice' | 'variantPrice' | 'variantTime';

export type ServiceProblem = ServiceRule | `${VariantRule}:${number}`;

/** A problem's message key and, for a variant's, its place in the set. */
export function problemParts(problem: ServiceProblem): { key: ServiceRule | VariantRule; index: number | null } {
  const [key, index] = problem.split(':');
  return { key: key as ServiceRule | VariantRule, index: index === undefined ? null : Number(index) };
}

/** The field a problem belongs to, for its `aria-invalid`. */
export const FIELD_OF: Record<ServiceRule, keyof ServiceValues> = {
  nameLength: 'name',
  nameTaken: 'name',
  category: 'categoryId',
  description: 'description',
  buffer: 'buffer',
  price: 'price',
  time: 'minutes',
  variantCount: 'variants',
  setName: 'setName',
  depositPercent: 'deposit',
  depositAmount: 'deposit',
  depositAboveLowest: 'deposit',
};

/** The variant field a variant's problem belongs to. */
export const VARIANT_FIELD_OF: Record<VariantRule, 'name' | 'price' | 'minutes'> = {
  variantName: 'name',
  variantTwice: 'name',
  variantPrice: 'price',
  variantTime: 'minutes',
};

const MINOR_PER_RUPEE = 100;
const STEP = 5;
export const VARIANTS_MAX = 10;
const VARIANTS_MIN = 2;

/** A whole number typed with optional thousands commas, or null for anything else. */
export function whole(text: string): number | null {
  const value = text.trim().replace(/,/g, '');
  return /^\d{1,9}$/.test(value) ? Number(value) : null;
}

const length = (text: string) => Array.from(text).length;
const PRICE_MIN = 100;
const PRICE_MAX = 500_000;
const DEPOSIT_MIN = 100;
const DEPOSIT_MAX = 100_000;
const priceOk = (price: number | null): price is number => price !== null && price >= PRICE_MIN && price <= PRICE_MAX;
const minutesOk = (minutes: number | null): minutes is number =>
  minutes !== null && minutes >= 5 && minutes <= 480 && minutes % STEP === 0;

/**
 * The sheet's money limits as its messages say them, in the salon's currency (F05-S01 AC-19): the same whole units in
 * every currency, as the API applies them (decision 091).
 */
export function moneyLimits(currency: string) {
  const money = (units: number) => formatMoney(units * MINOR_PER_RUPEE, currency);
  return {
    priceMin: money(PRICE_MIN),
    priceMax: money(PRICE_MAX),
    depositMin: money(DEPOSIT_MIN),
    depositMax: money(DEPOSIT_MAX),
  };
}

/** The prototype's suggestion for a custom deposit: 20%, or Rs. 1,000. */
const DEPOSIT: DepositValues = { choice: 'salon_rule', customKind: 'percentage', percent: '20', amount: '1000' };

let rows = 0;
/** A new key for a variant row. */
export function rowKey(): string {
  rows += 1;
  return `variant-${rows}`;
}

/** A new service's starting values: the first category, Rs. 2,500 for 60 minutes, as the prototype opens it. */
export function newService(categoryId: string): ServiceValues {
  return {
    name: '',
    categoryId,
    description: '',
    price: '2500',
    minutes: '60',
    buffer: '0',
    bookableOnline: true,
    hasVariants: false,
    setName: '',
    variants: [],
    deposit: DEPOSIT,
    popular: false,
  };
}

/** A service's values as the sheet edits them. With variants, its own price and time are the lowest and shortest. */
export function valuesOf(service: CatalogueService): ServiceValues {
  const deposit = service.deposit;
  return {
    name: service.name,
    categoryId: service.categoryId,
    description: service.description ?? '',
    price: String(Math.floor(service.terms.priceMinor / MINOR_PER_RUPEE)),
    minutes: String(service.terms.durationMinutes),
    buffer: String(service.terms.bufferMinutes),
    bookableOnline: service.bookableOnline,
    hasVariants: service.terms.variants.length > 0,
    setName: service.terms.variantSetName ?? '',
    variants: service.terms.variants.map((variant) => ({
      key: variant.id,
      id: variant.id,
      name: variant.name,
      price: String(Math.floor(variant.priceMinor / MINOR_PER_RUPEE)),
      minutes: String(variant.durationMinutes),
    })),
    deposit:
      deposit.kind === 'percentage'
        ? { ...DEPOSIT, choice: 'custom', customKind: 'percentage', percent: String(deposit.percent ?? '') }
        : deposit.kind === 'amount'
          ? {
              ...DEPOSIT,
              choice: 'custom',
              customKind: 'amount',
              amount: String(Math.floor((deposit.amountMinor ?? 0) / MINOR_PER_RUPEE)),
            }
          : { ...DEPOSIT, choice: deposit.kind },
    popular: service.popular,
  };
}

/** The values with variants turned on: two to start with, from the service's own price and time. */
export function withVariantsOn(values: ServiceValues, names: [string, string]): ServiceValues {
  const variants =
    values.variants.length > 0
      ? values.variants
      : names.map((name) => ({ key: rowKey(), id: null, name, price: values.price, minutes: values.minutes }));
  return { ...values, hasVariants: true, variants };
}

/** The lowest price the values give, in whole rupees, or null while it cannot be told. */
export function lowestPrice(values: ServiceValues): number | null {
  if (!values.hasVariants) {
    const price = whole(values.price);
    return priceOk(price) ? price : null;
  }
  const prices = values.variants.map((variant) => whole(variant.price));
  return prices.length > 0 && prices.every(priceOk) ? Math.min(...(prices as number[])) : null;
}

/** The highest price of the variants, in whole rupees, or null while it cannot be told. */
export function highestPrice(values: ServiceValues): number | null {
  const prices = values.variants.map((variant) => whole(variant.price));
  return prices.length > 0 && prices.every(priceOk) ? Math.max(...(prices as number[])) : null;
}

/**
 * The rules the values break, in the prototype's order.
 *
 * @param otherNames the salon's other services' names, to say a name is taken before the API does
 */
export function problemsOf(values: ServiceValues, otherNames: string[]): ServiceProblem[] {
  const problems: ServiceProblem[] = [];
  const name = values.name.trim();
  if (length(name) < 2 || length(name) > 40) problems.push('nameLength');
  else if (otherNames.some((other) => other.toLowerCase() === name.toLowerCase())) problems.push('nameTaken');
  if (!values.categoryId) problems.push('category');
  if (length(values.description.trim()) > 90) problems.push('description');
  const buffer = whole(values.buffer);
  if (buffer === null || buffer > 60 || buffer % STEP !== 0) problems.push('buffer');
  if (!values.hasVariants) {
    if (!priceOk(whole(values.price))) problems.push('price');
    if (!minutesOk(whole(values.minutes))) problems.push('time');
  } else {
    problems.push(...variantProblems(values));
  }
  problems.push(...depositProblems(values));
  return problems;
}

function variantProblems(values: ServiceValues): ServiceProblem[] {
  const problems: ServiceProblem[] = [];
  const setName = values.setName.trim();
  if (length(setName) < 2 || length(setName) > 30) problems.push('setName');
  if (values.variants.length < VARIANTS_MIN || values.variants.length > VARIANTS_MAX) problems.push('variantCount');
  const seen = new Set<string>();
  values.variants.forEach((variant, index) => {
    const name = variant.name.trim();
    if (length(name) < 1 || length(name) > 30) problems.push(`variantName:${index}`);
    else if (seen.has(name.toLowerCase())) problems.push(`variantTwice:${index}`);
    seen.add(name.toLowerCase());
    if (!priceOk(whole(variant.price))) problems.push(`variantPrice:${index}`);
    if (!minutesOk(whole(variant.minutes))) problems.push(`variantTime:${index}`);
  });
  return problems;
}

function depositProblems(values: ServiceValues): ServiceProblem[] {
  const deposit = values.deposit;
  if (deposit.choice !== 'custom') return [];
  if (deposit.customKind === 'percentage') {
    const percent = whole(deposit.percent);
    return percent === null || percent < 1 || percent > 100 ? ['depositPercent'] : [];
  }
  const amount = whole(deposit.amount);
  if (amount === null || amount < DEPOSIT_MIN || amount > DEPOSIT_MAX) return ['depositAmount'];
  const lowest = lowestPrice(values);
  return lowest !== null && amount > lowest ? ['depositAboveLowest'] : [];
}

/**
 * The problems the sheet shows: a new service's name is not called too short before anything is typed in it, as in
 * the prototype; the Create button stays disabled all the same.
 */
export function shownProblems(values: ServiceValues, problems: ServiceProblem[], isNew: boolean): ServiceProblem[] {
  return problems.filter((problem) => !(isNew && problem === 'nameLength' && values.name.trim() === ''));
}

const VARIANT_FIELD = /^variants\.items\[(\d+)\]\.(name|priceMinor|durationMinutes)$/;

/** What the API's field errors mean on the sheet. A variant's id it does not know is a conflict, not a field. */
export function problemsOfFields(fields: string[], values: ServiceValues): ServiceProblem[] {
  const named: Record<string, ServiceProblem> = {
    categoryId: 'category',
    description: 'description',
    bufferMinutes: 'buffer',
    priceMinor: 'price',
    durationMinutes: 'time',
    'variants.name': 'setName',
    'variants.items': 'variantCount',
    'deposit.percent': 'depositPercent',
  };
  return fields.flatMap((field): ServiceProblem[] => {
    if (field === 'name') {
      const name = values.name.trim();
      return [length(name) >= 2 && length(name) <= 40 ? 'nameTaken' : 'nameLength'];
    }
    if (field === 'deposit.amountMinor') {
      const amount = whole(values.deposit.amount);
      return [
        amount !== null && amount >= DEPOSIT_MIN && amount <= DEPOSIT_MAX ? 'depositAboveLowest' : 'depositAmount',
      ];
    }
    const variant = VARIANT_FIELD.exec(field);
    if (variant) {
      const index = Number(variant[1]);
      const rule: VariantRule =
        variant[2] === 'priceMinor'
          ? 'variantPrice'
          : variant[2] === 'durationMinutes'
            ? 'variantTime'
            : length(values.variants[index]?.name.trim() ?? '') >= 1
              ? 'variantTwice'
              : 'variantName';
      return [`${rule}:${index}`];
    }
    return named[field] ? [named[field]] : [];
  });
}

/** Whether the API's field errors name a variant id the service no longer has: someone else changed it. */
export function variantIdRefused(fields: string[]): boolean {
  return fields.some((field) => /^variants\.items\[\d+\]\.id$/.test(field));
}

function depositOf(deposit: DepositValues) {
  if (deposit.choice !== 'custom') return { kind: deposit.choice };
  return deposit.customKind === 'percentage'
    ? { kind: 'percentage' as const, percent: whole(deposit.percent) ?? 0 }
    : { kind: 'amount' as const, amountMinor: (whole(deposit.amount) ?? 0) * MINOR_PER_RUPEE };
}

/** The values as the API takes them, Popular aside. Only called once `problemsOf` finds nothing. */
export function requestOf(values: ServiceValues) {
  const own = values.hasVariants
    ? {
        variants: {
          name: values.setName.trim(),
          items: values.variants.map((variant) => ({
            ...(variant.id ? { id: variant.id } : {}),
            name: variant.name.trim(),
            priceMinor: (whole(variant.price) ?? 0) * MINOR_PER_RUPEE,
            durationMinutes: whole(variant.minutes) ?? 0,
          })),
        },
      }
    : { priceMinor: (whole(values.price) ?? 0) * MINOR_PER_RUPEE, durationMinutes: whole(values.minutes) ?? 0 };
  return {
    name: values.name.trim(),
    categoryId: values.categoryId,
    description: values.description.trim() === '' ? null : values.description.trim(),
    ...own,
    bufferMinutes: whole(values.buffer) ?? 0,
    bookableOnline: values.bookableOnline,
    deposit: depositOf(values.deposit),
  };
}

/** Whether two sets of values say the same service, as the API would read them. */
function sameService(a: ServiceValues, b: ServiceValues): boolean {
  return JSON.stringify(requestOf(a)) === JSON.stringify(requestOf(b));
}

/** Whether two sets of values say the same service and the same Popular choice: nothing to save or lose. */
export function sameValues(a: ServiceValues, b: ServiceValues): boolean {
  return sameService(a, b) && a.popular === b.popular;
}
