// @vitest-environment node
import { readFileSync } from 'node:fs';

import { expect, it } from 'vitest';

import { PLAIN_PAGE_CSS, plainPageCss } from '../../../scripts/plain-page-css.mjs';

// docs/engineering/web.md, "Styling", rule 9 (CR-017): the plain pages' CSS is Tailwind's output for
// classes.ts and globals.css, committed and never edited. A change to either, or a hand edit of the
// generated file, fails here until `pnpm --filter @salon/web plain-page:css` is run and committed.
it('the committed plain-page CSS is what Tailwind generates now', async () => {
  expect(readFileSync(PLAIN_PAGE_CSS, 'utf8')).toBe(await plainPageCss());
}, 30_000);
