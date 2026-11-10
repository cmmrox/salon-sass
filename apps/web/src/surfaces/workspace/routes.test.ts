import { readdirSync, statSync } from 'node:fs';
import { join } from 'node:path';
import { describe, expect, it } from 'vitest';
import { WORKSPACE_ROUTES } from './routes';

/** Every workspace page file's path: `src/app/(workspace)/workspace/<path>/page.tsx`. */
function pagesUnder(directory: string, path: string): string[] {
  const here = readdirSync(directory).includes('page.tsx') ? [path] : [];
  return here.concat(
    readdirSync(directory)
      .filter((entry) => statSync(join(directory, entry)).isDirectory())
      .flatMap((entry) => pagesUnder(join(directory, entry), `${path}/${entry}`)),
  );
}

// Web M5: a workspace page is declared once, in its module's routes; the proxy, page, frame and menu read the table.
describe('the workspace route table', () => {
  it('has every protected workspace page, and no page that does not exist', () => {
    const files = pagesUnder(join(__dirname, '..', '..', 'app', '(workspace)', 'workspace'), '/workspace');
    // Entry, sign-in and the support entry are the session's own, decided before a page has a permission.
    const open = ['/workspace', '/workspace/sign-in', '/workspace/support'];
    expect(WORKSPACE_ROUTES.pages.map((page) => page.path).sort()).toEqual(
      files.filter((path) => !open.includes(path)).sort(),
    );
  });

  it('names a permission and a title for every page', () => {
    for (const page of WORKSPACE_ROUTES.pages) {
      expect(page.permission, page.path).toMatch(/^[a-z]+(\.[a-z]+)+$/);
      expect(page.title, page.path).not.toBe('');
    }
  });
});
