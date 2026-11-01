import type { CatalogueService } from '../types';

/**
 * The service sheet's form (F05-S01 AC-3, the prototype's `seErr`): what the person typed, checked for their
 * convenience as they type; the API checks for real. Prices are typed in whole rupees and sent in minor units (a
 * rupee is 100, as `formatMoney` counts it); time and buffer in minutes.
 */
export type ServiceValues = {
  name: string;
  categoryId: string;
  description: string;
  price: string;
  minutes: string;
  buffer: string;
  bookableOnline: boolean;
};

/** A broken rule, named for its message, in the prototype's order. */
export type ServiceProblem = 'nameLength' | 'nameTaken' | 'category' | 'description' | 'buffer' | 'price' | 'time';

/** The field a problem belongs to, for its `aria-invalid`. */
export const FIELD_OF: Record<ServiceProblem, keyof ServiceValues> = {
  nameLength: 'name',
  nameTaken: 'name',
  category: 'categoryId',
  description: 'description',
  buffer: 'buffer',
  price: 'price',
  time: 'minutes',
};

const MINOR_PER_RUPEE = 100;
const STEP = 5;

/** A whole number typed with optional thousands commas, or null for anything else. */
function whole(text: string): number | null {
  const value = text.trim().replace(/,/g, '');
  return /^\d{1,9}$/.test(value) ? Number(value) : null;
}

const length = (text: string) => Array.from(text).length;

/** A new service's starting values: the first category, Rs. 2,500 for 60 minutes, as the prototype opens it. */
export function newService(categoryId: string): ServiceValues {
  return { name: '', categoryId, description: '', price: '2500', minutes: '60', buffer: '0', bookableOnline: true };
}

/** A service's values as the sheet edits them. */
export function valuesOf(service: CatalogueService): ServiceValues {
  return {
    name: service.name,
    categoryId: service.categoryId,
    description: service.description ?? '',
    price: String(Math.floor(service.terms.priceMinor / MINOR_PER_RUPEE)),
    minutes: String(service.terms.durationMinutes),
    buffer: String(service.terms.bufferMinutes),
    bookableOnline: service.bookableOnline,
  };
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
  const price = whole(values.price);
  if (price === null || price < 100 || price > 500_000) problems.push('price');
  const minutes = whole(values.minutes);
  if (minutes === null || minutes < 5 || minutes > 480 || minutes % STEP !== 0) problems.push('time');
  return problems;
}

/**
 * The problems the sheet shows: a new service's name is not called too short before anything is typed in it, as in
 * the prototype; the Create button stays disabled all the same.
 */
export function shownProblems(values: ServiceValues, problems: ServiceProblem[], isNew: boolean): ServiceProblem[] {
  return problems.filter((problem) => !(isNew && problem === 'nameLength' && values.name.trim() === ''));
}

/** What the API's field errors mean on the sheet. */
export function problemsOfFields(fields: string[], values: ServiceValues): ServiceProblem[] {
  const named: Record<string, ServiceProblem> = {
    categoryId: 'category',
    description: 'description',
    bufferMinutes: 'buffer',
    priceMinor: 'price',
    durationMinutes: 'time',
  };
  return fields.flatMap((field): ServiceProblem[] => {
    if (field === 'name') {
      const name = values.name.trim();
      return [length(name) >= 2 && length(name) <= 40 ? 'nameTaken' : 'nameLength'];
    }
    return named[field] ? [named[field]] : [];
  });
}

/** The values as the API takes them. Only called once `problemsOf` finds nothing. */
export function requestOf(values: ServiceValues) {
  return {
    name: values.name.trim(),
    categoryId: values.categoryId,
    description: values.description.trim() === '' ? null : values.description.trim(),
    priceMinor: (whole(values.price) ?? 0) * MINOR_PER_RUPEE,
    durationMinutes: whole(values.minutes) ?? 0,
    bufferMinutes: whole(values.buffer) ?? 0,
    bookableOnline: values.bookableOnline,
  };
}

/** Whether two sets of values say the same service, as the API would read them. */
export function sameService(a: ServiceValues, b: ServiceValues): boolean {
  return JSON.stringify(requestOf(a)) === JSON.stringify(requestOf(b));
}
