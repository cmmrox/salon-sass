import { afterEach, describe, expect, it, vi } from 'vitest';
import { laterAnswerWins } from '@test/late-answers';
import {
  useCategories,
  usePopularSetting,
  useProductList,
  usePublicTax,
  useServices,
  useTaxHistory,
  useTaxSettings,
} from './catalogue-data';

afterEach(() => {
  vi.unstubAllGlobals();
});

// Decision 121: an answer to a question that has been asked again, or replaced, is never shown.
describe('a later answer wins over an earlier one that arrives after it', () => {
  it.each([
    ['useCategories', () => useCategories('staff'), 'catalogue-categories'],
    ['useServices', () => useServices('staff'), 'catalogue-services'],
    ['usePopularSetting', () => usePopularSetting('staff'), 'catalogue-popular-setting'],
    ['useProductList', () => useProductList('staff'), 'catalogue-products'],
    ['useTaxSettings', () => useTaxSettings('staff'), 'catalogue-tax-settings'],
    ['usePublicTax', () => usePublicTax('staff'), 'catalogue-tax-public'],
  ])('%s, read again', async (_name, use, action) => {
    const bff = await laterAnswerWins(use, 0, 0, (result) => void result.refetch?.());
    expect(new Set(bff.held.map((each) => each.url))).toEqual(new Set([`/identity-web/staff/${action}`]));
  });

  it('useTaxHistory, its page changed', async () => {
    const bff = await laterAnswerWins((page: number) => useTaxHistory('staff', page, true), 0, 1);
    expect(bff.held.map((each) => each.url)).toEqual([
      '/identity-web/staff/catalogue-tax-history?page=0',
      '/identity-web/staff/catalogue-tax-history?page=1',
    ]);
  });
});
