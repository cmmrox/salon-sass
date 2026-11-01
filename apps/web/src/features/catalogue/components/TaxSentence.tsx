'use client';
import { useTranslations } from 'next-intl';
import type { PublicTax } from '../types';

type Props = {
  tax: PublicTax | null | undefined;
  /** Whose banner it is in: the Products tab names only what applies to products. */
  of: 'services' | 'products';
};

/**
 * The sentence about tax in a tab's one banner (F05-S01 AC-17, F05-S03 AC-10 and AC-14; the F05 block's `taxNote`):
 * which parts the prices include, at the current version's rates, or that tax and service charge are off. Nothing is
 * said while the salon's tax could not be read.
 */
export function TaxSentence({ tax, of }: Props) {
  const t = useTranslations('services.taxBanner');
  const onProducts = useTranslations('services.taxBanner.products');
  if (tax === undefined) return null;
  const vat = tax?.vat ?? null;
  const charge = tax?.serviceCharge ?? null;
  const values = {
    vat: vat?.name ?? '',
    vatPercent: vat?.ratePercent ?? 0,
    // The prototype writes the service charge's name in lower case inside the sentence.
    serviceCharge: charge?.name.toLowerCase() ?? '',
    serviceChargePercent: charge?.percent ?? 0,
  };
  if (of === 'products') {
    const applies = charge?.onProducts === true;
    const key = vat && applies ? 'both' : vat ? 'vat' : applies ? 'serviceCharge' : charge ? 'none' : 'off';
    return (
      <span data-testid="tax-banner" data-tax={key}>
        {onProducts(key, values)}
      </span>
    );
  }
  const key = vat && charge ? 'both' : vat ? 'vat' : charge ? 'serviceCharge' : 'off';
  return (
    <span data-testid="tax-banner" data-tax={key}>
      {t(key, values)}
    </span>
  );
}
