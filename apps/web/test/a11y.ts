import axeCore from 'axe-core';
import { page } from 'vitest/browser';

/**
 * axe-core at WCAG 2.2 level AA (with the 2.0 and 2.1 A and AA rules it builds on), colour contrast
 * included, over a screen rendered in the browser (docs/engineering/web.md, "Tests"; decision 068).
 * axe-core runs directly in the page: vitest-axe's own runner reads axe-core's source from disk for jsdom
 * and cannot run in a browser, so only its matcher is used (`toHaveNoViolations`).
 */
export function axe(root: Element) {
  return axeCore.run(root, {
    runOnly: { type: 'tag', values: ['wcag2a', 'wcag2aa', 'wcag21a', 'wcag21aa', 'wcag22aa'] },
  });
}

/** The widths of docs/design/ui-ux-standards.md, "Width matrix". */
export const WIDTHS = [360, 390, 768, 1024, 1280, 1440] as const;

export async function atWidth(width: number) {
  await page.viewport(width, 900);
}

/** Two salons' brands, a dark and a light one, as ui-ux-standards.md asks of every salon screen. */
export const BRANDS = ['#1F2937', '#FDE68A'] as const;
