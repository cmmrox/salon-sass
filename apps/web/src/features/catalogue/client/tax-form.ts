import type { PublicTax, TaxSettings, TaxValues } from '../types';
import { taxParts } from './tax-parts';

/**
 * The Tax & charges tab's form (F05-S03 AC-2, the prototype's `setTax`): what the person typed and chose, kept as
 * typed. Turning VAT or the service charge off keeps what was typed for it, and the save leaves it out, so turning
 * the part on again before saving shows it again.
 */
export type TaxForm = {
  vatRegistered: boolean;
  registrationNumber: string;
  serviceChargeOn: boolean;
  percent: string;
  onProducts: boolean;
};

/** A broken rule of the form, named for its field. */
export type TaxProblem = 'registrationNumber' | 'percent';

/** The prototype's suggested rate for a service charge the salon turns on: 10%. */
const SUGGESTED_PERCENT = '10';
const REGISTRATION_NUMBER = /^[A-Za-z0-9-]{1,20}$/;

/** The form for the current settings, or both parts off before the first save. */
export function taxFormOf(values: TaxValues | null): TaxForm {
  return {
    vatRegistered: values?.vat.registered ?? false,
    registrationNumber: values?.vat.registrationNumber ?? '',
    serviceChargeOn: values?.serviceCharge.on ?? false,
    percent: values?.serviceCharge.percent == null ? SUGGESTED_PERCENT : String(values.serviceCharge.percent),
    onProducts: values?.serviceCharge.onProducts ?? false,
  };
}

/** The whole percentage typed, or null for anything else. */
function wholePercent(text: string): number | null {
  const value = text.trim();
  return /^\d{1,3}$/.test(value) ? Number(value) : null;
}

/** What is wrong with the form for the parts that are on: a registration number, a rate of 1 to the maximum. */
export function taxProblemsOf(form: TaxForm, maxPercent: number): TaxProblem[] {
  const problems: TaxProblem[] = [];
  if (form.vatRegistered && !REGISTRATION_NUMBER.test(form.registrationNumber.trim())) {
    problems.push('registrationNumber');
  }
  const percent = wholePercent(form.percent);
  if (form.serviceChargeOn && (percent === null || percent < 1 || percent > maxPercent)) problems.push('percent');
  return problems;
}

/** The settings a save sends: a part's number or rate only while it is on, and products only with a service charge. */
function taxValuesOf(form: TaxForm): TaxValues {
  return {
    vat: {
      registered: form.vatRegistered,
      registrationNumber: form.vatRegistered ? form.registrationNumber.trim() : null,
    },
    serviceCharge: {
      on: form.serviceChargeOn,
      percent: form.serviceChargeOn ? (wholePercent(form.percent) ?? 0) : null,
      onProducts: form.serviceChargeOn && form.onProducts,
    },
  };
}

/** Whether the form would save what is saved now. */
export function sameTax(form: TaxForm, saved: TaxValues | null): boolean {
  if (!saved) return false;
  const next = taxValuesOf(form);
  return (
    next.vat.registered === saved.vat.registered &&
    next.vat.registrationNumber === saved.vat.registrationNumber &&
    next.serviceCharge.on === saved.serviceCharge.on &&
    next.serviceCharge.percent === saved.serviceCharge.percent &&
    next.serviceCharge.onProducts === saved.serviceCharge.onProducts
  );
}

/** The request a save sends, from the version read: a part that is off sends no number or rate. */
export function taxRequestOf(form: TaxForm, version: number) {
  const values = taxValuesOf(form);
  return {
    version,
    vat: values.vat.registered
      ? { registered: true, registrationNumber: values.vat.registrationNumber }
      : { registered: false },
    serviceCharge: values.serviceCharge.on
      ? { on: true, percent: values.serviceCharge.percent, onProducts: values.serviceCharge.onProducts }
      : { on: false, onProducts: false },
  };
}

/** The API's field errors, as the form's problems; null for a field the form does not show. */
export function taxProblemOfField(field: string): TaxProblem | null {
  if (field === 'vat.registrationNumber') return 'registrationNumber';
  if (field === 'serviceCharge.percent') return 'percent';
  return null;
}

/**
 * The VAT rate configuration gives now, when it differs from the rate the current version copied: a save applies it
 * (F05-S03 AC-3). Null when they agree, or while the current version charges no VAT.
 */
export function newVatRate(settings: TaxSettings, current: PublicTax | null): number | null {
  if (!current?.vat) return null;
  return current.vat.ratePercent === settings.configured.vatRatePercent ? null : settings.configured.vatRatePercent;
}

/** One item the preview prices: its name, or null for the prototype's example, and its tax-inclusive price. */
export type PreviewItem = { name: string | null; priceMinor: number };

/** One line of the preview: an item's price, then each part it includes. */
export type PreviewLine =
  | { kind: 'service' | 'product'; name: string | null; priceMinor: number }
  | { kind: 'serviceCharge'; percent: number; amountMinor: number }
  | { kind: 'vat'; percent: number; amountMinor: number };

/** The prototype's examples when the salon has no service or product to show: Rs. 2,500 and Rs. 3,200. */
export const EXAMPLE_SERVICE: PreviewItem = { name: null, priceMinor: 250_000 };
export const EXAMPLE_PRODUCT: PreviewItem & { chargesVat: boolean } = {
  name: null,
  priceMinor: 320_000,
  chargesVat: true,
};

/**
 * "What customers pay" (F05-S03 AC-5, the prototype's `setTax`): a service and a product with the parts each
 * includes under the form as it is, at the configured VAT rate a save would copy. The service carries the service
 * charge and VAT; the product VAT when it charges it, and the service charge only when it is on products too.
 */
export function previewOf(
  form: TaxForm,
  vatRatePercent: number,
  service: PreviewItem,
  product: PreviewItem & { chargesVat: boolean },
): PreviewLine[] {
  const percent = form.serviceChargeOn ? (wholePercent(form.percent) ?? 0) : 0;
  const vat = form.vatRegistered ? vatRatePercent : 0;
  const lines = (kind: 'service' | 'product', item: PreviewItem, itemVat: number, itemCharge: number) => {
    const parts = taxParts(item.priceMinor, itemVat, itemCharge);
    const shown: PreviewLine[] = [{ kind, name: item.name, priceMinor: item.priceMinor }];
    if (itemCharge > 0)
      shown.push({ kind: 'serviceCharge', percent: itemCharge, amountMinor: parts.serviceChargeMinor });
    if (itemVat > 0) shown.push({ kind: 'vat', percent: itemVat, amountMinor: parts.vatMinor });
    return shown;
  };
  return [
    ...lines('service', service, vat, percent),
    ...lines('product', product, product.chargesVat ? vat : 0, form.onProducts ? percent : 0),
  ];
}
