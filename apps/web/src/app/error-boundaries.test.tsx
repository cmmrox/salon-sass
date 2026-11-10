import { existsSync } from 'node:fs';
import { join } from 'node:path';
import { screen } from '@testing-library/react';
import { describe, expect, it } from 'vitest';
import { renderWithMessages } from '@test/render';
import ConsoleError from './(console)/error';
import PlatformError from './(platform)/error';
import WorkspaceError from './(workspace)/error';

// Web M3 (docs/engineering/web.md, "Structure"): every route group has an error boundary, and the application a
// global one, so that one value that fails to render does not blank the page.
describe('error boundaries', () => {
  it('exist for every route group and for the root layout', () => {
    const groups = ['(workspace)', '(console)', '(platform)'];
    for (const group of groups) expect(existsSync(join(__dirname, group, 'error.tsx')), group).toBe(true);
    expect(existsSync(join(__dirname, 'global-error.tsx'))).toBe(true);
  });

  it.each([
    ['workspace', WorkspaceError],
    ['console', ConsoleError],
    ['platform', PlatformError],
  ])('the %s group says it in plain words, with nothing of the error’s message', (_group, Boundary) => {
    renderWithMessages(<Boundary error={new Error('private detail of the failure')} retry={() => undefined} />);
    expect(screen.getByTestId('error-page')).toBeInTheDocument();
    expect(screen.getByTestId('error-page-retry')).toBeInTheDocument();
    expect(document.body.textContent).not.toContain('private detail');
  });
});
