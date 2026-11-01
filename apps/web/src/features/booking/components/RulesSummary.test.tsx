import { screen } from '@testing-library/react';
import { describe, expect, it } from 'vitest';
import { renderWithMessages } from '@test/render';
import { SUGGESTED, type RulesValues } from '../client/rules-form';
import { RulesSummary } from './RulesSummary';

const sentences = (values: Partial<RulesValues>, currency = 'LKR') => {
  const view = renderWithMessages(<RulesSummary values={{ ...SUGGESTED, ...values }} currency={currency} />);
  const text = screen.getAllByTestId('rules-summary-sentence').map((each) => each.textContent);
  view.unmount();
  return text;
};

/** "What customers see before paying" (F04-S04 AC-4): the prototype's `ruText` for every option. */
describe('what customers see before paying', () => {
  it('says the suggested rules in the prototype’s words', () => {
    expect(sentences({})).toEqual([
      'Deposit: 20% of the price, up to Rs. 2,000, taken when you book.',
      'Free cancellation until 24 hours before your appointment.',
      'After that, the deposit is kept. If you don’t come, the deposit is kept.',
      'You can reschedule twice, up to 12 hours before.',
    ]);
  });

  it('says each deposit: none, a percentage with its cap, and a fixed amount, through formatMoney', () => {
    expect(sentences({ deposit: 'none' })[0]).toBe('No deposit. You pay at the salon.');
    expect(sentences({ deposit: 'percentage', percent: 35, capMinor: 250_050 })[0]).toBe(
      'Deposit: 35% of the price, up to Rs. 2,500.50, taken when you book.',
    );
    expect(sentences({ deposit: 'amount', amountMinor: 150_000 })[0]).toBe('Deposit: Rs. 1,500, taken when you book.');
  });

  it.each([2, 6, 12, 24, 48])('says the free-cancellation window of %i hours', (hours) => {
    expect(sentences({ freeCancellationHours: hours })[1]).toBe(
      `Free cancellation until ${hours} hours before your appointment.`,
    );
  });

  it('says what each later cancellation and no-show keeps', () => {
    expect(sentences({ late: 'nothing' })[2]).toBe(
      'After that, nothing is kept. If you don’t come, the deposit is kept.',
    );
    expect(sentences({ late: 'share', latePercent: 50, noShow: 'share', noShowPercent: 100 })[2]).toBe(
      'After that, 50% of the price is kept. If you don’t come, 100% of the price is kept.',
    );
  });

  it('says nothing is kept for “the deposit” when there is no deposit to keep', () => {
    expect(sentences({ deposit: 'none', late: 'deposit', noShow: 'deposit' })[2]).toBe(
      'After that, nothing is kept. If you don’t come, nothing is kept.',
    );
    expect(sentences({ deposit: 'none', late: 'share', latePercent: 30 })[2]).toBe(
      'After that, 30% of the price is kept. If you don’t come, nothing is kept.',
    );
  });

  it('says each rescheduling choice, and to contact the salon when it is not online', () => {
    expect(sentences({ reschedule: '0' })[3]).toBe('To reschedule, contact the salon.');
    expect(sentences({ reschedule: '1', rescheduleUntilHours: 2 })[3]).toBe(
      'You can reschedule once, up to 2 hours before.',
    );
    expect(sentences({ reschedule: '2', rescheduleUntilHours: 6 })[3]).toBe(
      'You can reschedule twice, up to 6 hours before.',
    );
    expect(sentences({ reschedule: 'any', rescheduleUntilHours: 24 })[3]).toBe(
      'You can reschedule as often as you need, up to 24 hours before.',
    );
  });

  it('notes that existing bookings keep their policy', () => {
    renderWithMessages(<RulesSummary values={SUGGESTED} currency="LKR" />);
    expect(screen.getByTestId('rules-summary-note')).toHaveTextContent(
      'Changes apply to new bookings. Existing bookings keep the policy they were made under.',
    );
  });
});
