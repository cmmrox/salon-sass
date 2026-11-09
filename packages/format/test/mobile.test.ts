import { describe, expect, it } from 'vitest';
import { isMobileNumber } from '../src/mobile';

describe('isMobileNumber (decision 084)', () => {
  it('takes a Sri Lankan mobile in any common form', () => {
    for (const typed of [
      '0771234567',
      '077 123 4567',
      ' 0712345678 ',
      '+94771234567',
      '+94 77 123 4567',
      '077-123-4567',
    ])
      expect(isMobileNumber(typed), typed).toBe(true);
  });
  it('refuses a landline, a short or long number, letters and nothing', () => {
    for (const typed of ['0112345678', '0112 504 417', '07712345', '07712345678', 'call me', '', '   '])
      expect(isMobileNumber(typed), typed).toBe(false);
  });
  it('reads a national number by the region it is given', () => {
    expect(isMobileNumber('07400 123456', 'GB')).toBe(true);
    expect(isMobileNumber('07400 123456', 'LK')).toBe(false);
  });
});
