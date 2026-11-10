import { describe, expect, it } from 'vitest';
import { atWidth, axe, WIDTHS } from '@test/a11y';
import { renderWithMessages } from '@test/render';
import { ErrorNotice } from './ErrorNotice';

/** The error page (web M3) has no WCAG 2.2 AA violation at any documented width, in the platform's neutral theme. */
describe('the error page’s accessibility', () => {
  it.each(WIDTHS)('passes at %ipx', async (width) => {
    await atWidth(width);
    const { container } = renderWithMessages(<ErrorNotice retry={() => undefined} />);
    expect(await axe(container)).toHaveNoViolations();
  });
});
