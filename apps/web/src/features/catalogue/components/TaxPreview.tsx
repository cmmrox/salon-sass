'use client';
import { formatMoney } from '@salon/format';
import { useTranslations } from 'next-intl';
import { Card } from '@/components/ui/card';
import { cn } from '@/lib/utils';
import type { PreviewLine } from '../client/tax-form';
import type { TaxSettings } from '../types';

/**
 * "What customers pay" (F05-S03 AC-5, the prototype's `setTax`): the salon's first service bookable online and first
 * product sold at checkout (or the prototype's examples), each with the parts its price includes under the form as it
 * is now, and the note that prices are always shown inclusive. Each part is written with the currency's cents.
 */
export function TaxPreview({
  lines,
  currency,
  configured,
}: {
  lines: PreviewLine[];
  currency: string;
  configured: TaxSettings['configured'];
}) {
  const t = useTranslations('settings.tax');
  const label = (line: PreviewLine) => {
    switch (line.kind) {
      case 'service':
        return line.name ?? t('exampleService');
      case 'product':
        return line.name ?? t('exampleProduct');
      case 'serviceCharge':
        return t('includesServiceCharge', {
          serviceCharge: configured.serviceChargeName.toLowerCase(),
          percent: line.percent,
        });
      default:
        return t('includesVat', { vat: configured.vatName, percent: line.percent });
    }
  };
  return (
    <section className="flex flex-col gap-3" aria-labelledby="tax-preview-title">
      <h2 id="tax-preview-title" className="m-0 text-large font-semibold">
        {t('previewTitle')}
      </h2>
      <Card className="gap-0 py-2 text-small" data-testid="tax-preview">
        {lines.map((line, index) => {
          const item = 'priceMinor' in line;
          return (
            <p
              key={`${line.kind}-${index}`}
              className={cn(
                'm-0 flex justify-between gap-3 border-b border-border py-2.5 last:border-b-0',
                item && index > 0 && 'mt-2',
              )}
              data-testid="tax-preview-line"
              data-kind={line.kind}
            >
              <span className="min-w-0 wrap-anywhere text-muted-foreground">{label(line)}</span>
              {'priceMinor' in line ? (
                <b className="shrink-0 font-semibold">{formatMoney(line.priceMinor, currency)}</b>
              ) : (
                <span className="shrink-0">
                  {formatMoney(line.amountMinor, currency, 'en-LK', { cents: 'always' })}
                </span>
              )}
            </p>
          );
        })}
      </Card>
      {/* Ink, not muted: small muted text on the warm canvas is under 4.5:1 (ui-ux-standards.md). */}
      <p className="m-0 text-small text-foreground" data-testid="tax-preview-note">
        {t('previewNote')}
      </p>
    </section>
  );
}
