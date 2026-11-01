'use client';
import { useTranslations } from 'next-intl';
import type { PublicTax } from '../types';

/**
 * The sentence about tax in the Services tab's one banner (F05-S01 AC-17, F05-S03 AC-10; the F05 block's `taxNote`):
 * which parts the prices include, at the current version's rates, or that tax and service charge are off. Nothing is
 * said while the salon's tax could not be read.
 */
export function TaxSentence({ tax }: { tax: PublicTax | null | undefined }) {
  const t = useTranslations('services.taxBanner');
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
  const key = vat && charge ? 'both' : vat ? 'vat' : charge ? 'serviceCharge' : 'off';
  return (
    <span data-testid="tax-banner" data-tax={key}>
      {t(key, values)}
    </span>
  );
}
