import { readFileSync } from 'node:fs';
import { join } from 'node:path';

import { describe, expect, it } from 'vitest';

import { BRAND_TEXT_SURFACE, brandRootRule, brandTextColour, contrastColour, contrastRatio, DEFAULT_BRAND_COLOUR, isHexColour, ON_BRAND_COLOURS, relativeLuminance, salonBrandRule } from './brand';

describe('relativeLuminance and contrastRatio', () => {
  it('match WCAG for black and white', () => {
    expect(relativeLuminance('#000000')).toBe(0);
    expect(relativeLuminance('#FFFFFF')).toBe(1);
    expect(contrastRatio('#000000', '#ffffff')).toBe(21);
    expect(contrastRatio('#777777', '#777777')).toBe(1);
  });

  it('is the same whichever colour comes first', () => {
    expect(contrastRatio('#D6086A', '#ffffff')).toBeCloseTo(contrastRatio('#ffffff', '#D6086A'), 10);
  });

  it('refuses anything that is not #RRGGBB', () => {
    for (const value of ['', 'D6086A', '#D6086', '#D6086AFF', '#GGGGGG', 'red', 'rgb(0,0,0)']) {
      expect(() => relativeLuminance(value), value).toThrow(/#RRGGBB/);
    }
  });
});

describe('contrastColour', () => {
  it('a dark colour gets light text', () => {
    expect(contrastColour('#1F2937')).toBe(ON_BRAND_COLOURS.light);
    expect(contrastColour('#000000')).toBe(ON_BRAND_COLOURS.light);
  });

  it('a light colour gets dark text', () => {
    expect(contrastColour('#FDE68A')).toBe(ON_BRAND_COLOURS.dark);
    expect(contrastColour('#ffffff')).toBe(ON_BRAND_COLOURS.dark);
  });

  it('a mid colour gets whichever contrasts more', () => {
    // The prototype's default pink: white is 5.1 to 1 on it, the dark text 3.4 to 1.
    expect(contrastColour('#D6086A')).toBe(ON_BRAND_COLOURS.light);
    // A mid grey that is lighter than the point where the two cross over.
    expect(contrastColour('#808080')).toBe(ON_BRAND_COLOURS.dark);
    // A mid green that is darker.
    expect(contrastColour('#2E7D32')).toBe(ON_BRAND_COLOURS.light);
  });

  it('is the same in either case', () => {
    expect(contrastColour('#fde68a')).toBe(contrastColour('#FDE68A'));
  });

  // The three synthetic salons of docs/qa/test-plan.md: the default pink, a dark and a light colour.
  it.each(['#D6086A', '#1F2937', '#FDE68A'])('text on %s has a contrast of at least 4.5 to 1', (colour) => {
    expect(contrastRatio(colour, contrastColour(colour))).toBeGreaterThanOrEqual(4.5);
  });

  it('the dark choice is the platform text colour of tokens.css', () => {
    const tokens = readFileSync(join(__dirname, 'tokens.css'), 'utf8');

    expect(tokens).toContain(`--color-text: ${ON_BRAND_COLOURS.dark};`);
  });
});

describe('brandRootRule', () => {
  it('sets the brand colour, exactly as given, and its contrast colour on the root', () => {
    expect(brandRootRule('#D6086A')).toBe(':root{--color-brand:#D6086A;--color-on-brand:#ffffff;--color-brand-soft:#D6086A22;--color-brand-text:#D6086A}');
    expect(brandRootRule('#1F2937')).toBe(':root{--color-brand:#1F2937;--color-on-brand:#ffffff;--color-brand-soft:#1F293722;--color-brand-text:#1F2937}');
    expect(brandRootRule('#FDE68A')).toBe(':root{--color-brand:#FDE68A;--color-on-brand:#111827;--color-brand-soft:#FDE68A22;--color-brand-text:#111827}');
  });

  it('refuses anything that is not #RRGGBB, so nothing else reaches the style sheet', () => {
    for (const value of ['red', '#fff;}body{display:none', '</style><script>', '#D6086A ']) {
      expect(() => brandRootRule(value), value).toThrow(/#RRGGBB/);
    }
  });
});

describe('brandTextColour', () => {
  // The three synthetic salons: the default pink, a dark and a light colour.
  it.each(['#D6086A', '#1F2937', '#FDE68A', '#FFFFFF', '#808080'])('text in %s brand is readable on the white card', (colour) => {
    expect(contrastRatio(brandTextColour(colour), BRAND_TEXT_SURFACE)).toBeGreaterThanOrEqual(4.5);
  });

  it('keeps a readable brand and replaces only one that is too light', () => {
    expect(brandTextColour('#D6086A')).toBe('#D6086A');
    expect(brandTextColour('#1F2937')).toBe('#1F2937');
    expect(brandTextColour('#FDE68A')).toBe(ON_BRAND_COLOURS.dark);
  });

  it('measures against the white surface of tokens.css', () => {
    const tokens = readFileSync(join(__dirname, 'tokens.css'), 'utf8');

    expect(tokens).toContain(`--color-surface: ${BRAND_TEXT_SURFACE};`);
  });
});

describe('salonBrandRule', () => {
  it('uses the salon\'s own valid colour', () => {
    expect(salonBrandRule('#1F2937')).toBe(brandRootRule('#1F2937'));
    expect(salonBrandRule('#fde68a')).toBe(brandRootRule('#fde68a'));
  });

  it('gives a salon without a valid colour the prototype default', () => {
    expect(DEFAULT_BRAND_COLOUR).toBe('#D6086A');
    for (const value of [undefined, null, '', 'red', '#fff', '#D6086A ', '</style><script>', 0xd6086a]) {
      expect(salonBrandRule(value), String(value)).toBe(brandRootRule('#D6086A'));
    }
  });

  it('is the prototype\'s final default brand rule', () => {
    const prototype = readFileSync(join(__dirname, '..', '..', '..', '..', 'docs', 'design', 'prototype', 'index.html'), 'utf8');

    expect(prototype).toContain(`:root{--ac:${DEFAULT_BRAND_COLOUR};`);
  });
});

describe('isHexColour', () => {
  it('accepts #RRGGBB in either case and nothing else', () => {
    expect(isHexColour('#D6086A')).toBe(true);
    expect(isHexColour('#d6086a')).toBe(true);
    expect(isHexColour('#D6086')).toBe(false);
    expect(isHexColour(undefined)).toBe(false);
    expect(isHexColour(0xd6086a)).toBe(false);
  });
});
