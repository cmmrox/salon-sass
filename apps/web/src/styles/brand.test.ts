import { readFileSync } from 'node:fs';
import { join } from 'node:path';

import { describe, expect, it } from 'vitest';

import {
  BRAND_TEXT_SURFACE,
  brandRootRule,
  brandTextColour,
  contrastColour,
  contrastRatio,
  DEFAULT_BRAND_COLOUR,
  isHexColour,
  ON_BRAND_COLOURS,
  relativeLuminance,
  salonBrandRule,
} from './brand';

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
    // A grey lighter than the point where the two cross over, where ink is readable.
    expect(contrastColour('#A0A0A0')).toBe(ON_BRAND_COLOURS.dark);
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

  it('the dark choice is the platform text colour of theme.css', () => {
    const theme = readFileSync(join(__dirname, 'theme.css'), 'utf8');

    expect(theme).toContain(`--foreground: ${ON_BRAND_COLOURS.dark};`);
  });
});

// BUG-F02-013: text on any valid brand fill reaches 4.5:1, not only on the seeded salons' colours.
describe('contrastColour on every brand colour', () => {
  const hex = (n: number) => n.toString(16).padStart(2, '0').toUpperCase();
  // The lead's 5-step sample of the cube: 52 values per channel, 0 to 255.
  const steps = Array.from({ length: 52 }, (_, i) => i * 5);
  const sample = [
    ...new Set(steps.flatMap((r) => steps.flatMap((g) => steps.map((b) => `#${hex(r)}${hex(g)}${hex(b)}`)))),
  ];
  const previous = (colour: string) =>
    contrastRatio(colour, '#ffffff') >= contrastRatio(colour, '#111827') ? '#ffffff' : '#111827';

  it.each(['#808080', '#C34BB4'])('gives the reported mid-tone %s a foreground of at least 4.5 to 1', (colour) => {
    expect(contrastRatio(colour, contrastColour(colour))).toBeGreaterThanOrEqual(4.5);
  });

  it('reaches 4.5 to 1 across the whole RGB cube', () => {
    const failing = sample.filter((colour) => contrastRatio(colour, contrastColour(colour)) < 4.5);
    expect(sample.length).toBe(52 ** 3);
    expect(failing).toEqual([]);
  });

  it('keeps white or ink wherever one of them already reaches 4.5 to 1, so existing salons do not change', () => {
    for (const colour of sample) {
      if (Math.max(contrastRatio(colour, '#ffffff'), contrastRatio(colour, '#111827')) >= 4.5) {
        expect(contrastColour(colour), colour).toBe(previous(colour));
      }
    }
    expect(contrastColour('#D6086A')).toBe('#ffffff');
    expect(contrastColour('#1F2937')).toBe('#ffffff');
    expect(contrastColour('#FDE68A')).toBe('#111827');
  });

  it('uses pure black only where neither white nor ink is readable', () => {
    expect(contrastColour('#808080')).toBe('#000000');
    expect(contrastColour('#C34BB4')).toBe('#000000');
    expect(brandRootRule('#808080')).toContain('--color-on-brand:#000000');
  });
});

describe('brandRootRule', () => {
  it('sets the brand colour, exactly as given, and its contrast colour on the root', () => {
    expect(brandRootRule('#D6086A')).toBe(
      ':root{--color-brand:#D6086A;--color-on-brand:#ffffff;--color-brand-soft:#D6086A22;--color-brand-text:#D6086A}',
    );
    expect(brandRootRule('#1F2937')).toBe(
      ':root{--color-brand:#1F2937;--color-on-brand:#ffffff;--color-brand-soft:#1F293722;--color-brand-text:#1F2937}',
    );
    expect(brandRootRule('#FDE68A')).toBe(
      ':root{--color-brand:#FDE68A;--color-on-brand:#111827;--color-brand-soft:#FDE68A22;--color-brand-text:#111827}',
    );
  });

  it('refuses anything that is not #RRGGBB, so nothing else reaches the style sheet', () => {
    for (const value of ['red', '#fff;}body{display:none', '</style><script>', '#D6086A ']) {
      expect(() => brandRootRule(value), value).toThrow(/#RRGGBB/);
    }
  });
});

describe('brandTextColour', () => {
  // The three synthetic salons: the default pink, a dark and a light colour.
  it.each(['#D6086A', '#1F2937', '#FDE68A', '#FFFFFF', '#808080'])(
    'text in %s brand is readable on the white card',
    (colour) => {
      expect(contrastRatio(brandTextColour(colour), BRAND_TEXT_SURFACE)).toBeGreaterThanOrEqual(4.5);
    },
  );

  it('keeps a readable brand and replaces only one that is too light', () => {
    expect(brandTextColour('#D6086A')).toBe('#D6086A');
    expect(brandTextColour('#1F2937')).toBe('#1F2937');
    expect(brandTextColour('#FDE68A')).toBe(ON_BRAND_COLOURS.dark);
  });

  it('measures against the white surface of theme.css', () => {
    const theme = readFileSync(join(__dirname, 'theme.css'), 'utf8');

    expect(theme).toContain(`--card: ${BRAND_TEXT_SURFACE};`);
  });
});

describe('salonBrandRule', () => {
  it("uses the salon's own valid colour", () => {
    expect(salonBrandRule('#1F2937')).toBe(brandRootRule('#1F2937'));
    expect(salonBrandRule('#fde68a')).toBe(brandRootRule('#fde68a'));
  });

  it('gives a salon without a valid colour the prototype default', () => {
    expect(DEFAULT_BRAND_COLOUR).toBe('#D6086A');
    for (const value of [undefined, null, '', 'red', '#fff', '#D6086A ', '</style><script>', 0xd6086a]) {
      expect(salonBrandRule(value), String(value)).toBe(brandRootRule('#D6086A'));
    }
  });

  it("is the prototype's final default brand rule", () => {
    const prototype = readFileSync(
      join(__dirname, '..', '..', '..', '..', 'docs', 'design', 'prototype', 'index.html'),
      'utf8',
    );

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
