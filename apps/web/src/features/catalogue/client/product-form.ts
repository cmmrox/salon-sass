import type { Product } from '../types';
import { whole } from './service-form';

/**
 * The product sheet's values (F05-S03 AC-8, the prototype's `pePaint`): the name and the price as typed, in whole
 * rupees, and the two settings. "Charge VAT" is kept while VAT is off, when the sheet does not show it.
 */
export type ProductValues = { name: string; price: string; chargesVat: boolean; soldAtCheckout: boolean };

/** A broken rule of the sheet, named for its message (the prototype's `peErr`). */
export type ProductProblem = 'name' | 'taken' | 'price';

const MINOR_PER_RUPEE = 100;
const length = (text: string) => Array.from(text).length;

/** A new product: no name or price yet, charging VAT and sold at checkout. */
export function newProduct(): ProductValues {
  return { name: '', price: '', chargesVat: true, soldAtCheckout: true };
}

export function productValuesOf(product: Product): ProductValues {
  return {
    name: product.name,
    price: String(Math.floor(product.priceMinor / MINOR_PER_RUPEE)),
    chargesVat: product.chargesVat,
    soldAtCheckout: product.soldAtCheckout,
  };
}

/**
 * What is wrong with the values, in the prototype's order: the name of 2 to 40 characters after trimming and no other
 * product's regardless of case, and the price of Rs. 1 to Rs. 500,000 in whole rupees. The API checks for real.
 */
export function productProblemsOf(values: ProductValues, otherNames: string[]): ProductProblem[] {
  const problems: ProductProblem[] = [];
  const name = values.name.trim();
  if (length(name) < 2 || length(name) > 40) problems.push('name');
  else if (otherNames.some((other) => other.toLowerCase() === name.toLowerCase())) problems.push('taken');
  const price = whole(values.price);
  if (price === null || price < 1 || price > 500_000) problems.push('price');
  return problems;
}

/** The request the sheet sends: the price in minor units, both settings always (the API has no default for them). */
export function productRequestOf(values: ProductValues) {
  return {
    name: values.name.trim(),
    priceMinor: (whole(values.price) ?? 0) * MINOR_PER_RUPEE,
    chargesVat: values.chargesVat,
    soldAtCheckout: values.soldAtCheckout,
  };
}

/** Whether the values would save the product as it is. */
export function sameProduct(values: ProductValues, product: Product): boolean {
  const request = productRequestOf(values);
  return (
    request.name === product.name &&
    request.priceMinor === product.priceMinor &&
    request.chargesVat === product.chargesVat &&
    request.soldAtCheckout === product.soldAtCheckout
  );
}

/** The API's field error, as the sheet's problem: a name it refuses that the sheet allowed is another product's. */
export function productProblemOfField(field: string, values: ProductValues): ProductProblem | null {
  if (field === 'name') {
    const name = values.name.trim();
    return length(name) >= 2 && length(name) <= 40 ? 'taken' : 'name';
  }
  return field === 'priceMinor' ? 'price' : null;
}
