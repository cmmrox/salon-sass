import { describe, expect, it, vi } from 'vitest';
import { atWidth, axe, WIDTHS } from '@test/a11y';
import { renderWithMessages } from '@test/render';
import { ErrorNotice } from './ErrorNotice';

// next/link reads Node's `process`, which the browser run has not; a plain link is what it renders.
vi.mock('next/link', () => ({
  __esModule: true,
  default: (props: React.AnchorHTMLAttributes<HTMLAnchorElement> & { href: string }) => <a {...props} />,
}));

/** The error page (web M3) has no WCAG 2.2 AA violation at any documented width, in the platform's neutral theme. */
describe('the error page’s accessibility', () => {
  it.each(WIDTHS)('passes at %ipx', async (width) => {
    await atWidth(width);
    const { container } = renderWithMessages(<ErrorNotice retry={() => undefined} />);
    expect(await axe(container)).toHaveNoViolations();
  });
});
