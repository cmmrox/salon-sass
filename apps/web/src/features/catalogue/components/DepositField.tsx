'use client';
import { currencyMark } from '@salon/format';
import { useTranslations } from 'next-intl';
import { FieldDescription, FieldLegend, FieldSet } from '@/components/ui/field';
import { InputGroup, InputGroupAddon, InputGroupInput, InputGroupText } from '@/components/ui/input-group';
import { Select, SelectContent, SelectItem, SelectTrigger, SelectValue } from '@/components/ui/select';
import { ToggleGroup, ToggleGroupItem } from '@/components/ui/toggle-group';
import type { DepositValues, ServiceValues } from '../client/service-form';

type Props = {
  deposit: DepositValues;
  currency: string;
  /** The salon's own rule in words ("20%, up to Rs. 2,000"), as its booking rules say it now; null when unknown. */
  salonRule: string | null;
  invalid: boolean;
  problemsId: string;
  onValues: (change: (values: ServiceValues) => ServiceValues) => void;
};

const CHOICES = ['salon_rule', 'custom', 'none'] as const;

/**
 * A service's deposit (F05-S02 AC-7, BKG-09; the F05 block's additions to `sePaint`): the prototype's segmented
 * Salon rule, Custom and No deposit. Salon rule says the salon's rule in words; Custom is a percentage or a fixed
 * amount, with its suffix; No deposit says customers pay none.
 */
export function DepositField({ deposit, currency, salonRule, invalid, problemsId, onValues }: Props) {
  const t = useTranslations('services.sheet.deposit');
  const set = (change: Partial<DepositValues>) =>
    onValues((current) => ({ ...current, deposit: { ...current.deposit, ...change } }));
  const amount = deposit.customKind === 'amount';
  return (
    <FieldSet className="gap-2" data-testid="service-deposit">
      <FieldLegend variant="label" id="service-deposit-label" className="mb-0">
        {t('label')}
      </FieldLegend>
      <ToggleGroup
        type="single"
        value={deposit.choice}
        onValueChange={(value) => value && set({ choice: value as DepositValues['choice'] })}
        aria-labelledby="service-deposit-label"
        className="flex-nowrap gap-0 rounded-action border border-border bg-background p-0.5"
        data-testid="service-deposit-choice"
      >
        {CHOICES.map((choice) => (
          <ToggleGroupItem key={choice} value={choice} variant="segment" data-testid={`service-deposit-${choice}`}>
            {t(choice)}
          </ToggleGroupItem>
        ))}
      </ToggleGroup>
      {deposit.choice === 'custom' ? (
        <div className="flex flex-wrap items-center gap-2.5">
          <Select
            value={deposit.customKind}
            onValueChange={(value) => set({ customKind: value as DepositValues['customKind'] })}
          >
            <SelectTrigger className="w-auto" aria-label={t('type')} data-testid="service-deposit-type">
              <SelectValue />
            </SelectTrigger>
            <SelectContent position="popper" sideOffset={4}>
              <SelectItem value="percentage" data-testid="service-deposit-type-percentage">
                {t('percentage')}
              </SelectItem>
              <SelectItem value="amount" data-testid="service-deposit-type-amount">
                {t('amount')}
              </SelectItem>
            </SelectContent>
          </Select>
          <InputGroup className="w-36">
            {amount && (
              <InputGroupAddon>
                <InputGroupText>{currencyMark(currency)}</InputGroupText>
              </InputGroupAddon>
            )}
            <InputGroupInput
              inputMode="numeric"
              value={amount ? deposit.amount : deposit.percent}
              onChange={(event) => set(amount ? { amount: event.target.value } : { percent: event.target.value })}
              aria-label={amount ? t('amountLabel') : t('percentLabel')}
              aria-invalid={invalid || undefined}
              aria-describedby={invalid ? problemsId : undefined}
              data-testid={amount ? 'service-deposit-amount' : 'service-deposit-percent'}
            />
            {!amount && (
              <InputGroupAddon align="inline-end">
                <InputGroupText>{t('percentSuffix')}</InputGroupText>
              </InputGroupAddon>
            )}
          </InputGroup>
        </div>
      ) : (
        <FieldDescription className="text-foreground" data-testid="service-deposit-says">
          {deposit.choice === 'none'
            ? t('noneSays')
            : salonRule === null
              ? t('ruleUnknown')
              : t('ruleSays', { rule: salonRule })}
        </FieldDescription>
      )}
    </FieldSet>
  );
}
