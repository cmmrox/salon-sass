import { readFileSync } from 'node:fs';
import { join } from 'node:path';

import { expect, it } from 'vitest';

import { MOBILE_BREAKPOINT } from './use-mobile';

// The Sidebar's JavaScript breakpoint (its sheet below, its rail from) is the theme's `rail`
// breakpoint, which the `rail:` classes use, so the two can never disagree (CR-017).
it("switches the Sidebar at the theme's rail breakpoint, 900px", () => {
  const globals = readFileSync(join(__dirname, '..', 'app', 'globals.css'), 'utf8');
  expect(globals).toContain(`--breakpoint-rail: ${MOBILE_BREAKPOINT}px;`);
  expect(MOBILE_BREAKPOINT).toBe(900);
});
