import { render } from '@testing-library/react';
import { describe, expect, it } from 'vitest';
import { Input } from '@/components/ui/input';
import { axe } from '@test/a11y';

/**
 * The accessibility check itself (F04-S01): it runs on the application's compiled Tailwind theme in a real
 * browser, so it fails on a contrast defect the theme would produce, and on an unlabelled field. Without
 * these, a check that never measured anything would pass every screen.
 */
describe('the accessibility check', () => {
  it('fails on muted small text on the warm canvas, which is 4.25:1 (ui-ux-standards.md)', async () => {
    const { container } = render(
      <main className="bg-background p-4">
        <p className="text-small text-muted-foreground">{'muted on canvas'}</p>
      </main>,
    );

    const results = await axe(container);

    expect(results.violations.map((violation) => violation.id)).toContain('color-contrast');
  });

  it('passes the same text on a white card, which is 4.83:1', async () => {
    const { container } = render(
      <main className="bg-card p-4">
        <p className="text-small text-muted-foreground">{'muted on white'}</p>
      </main>,
    );

    expect(await axe(container)).toHaveNoViolations();
  });

  it('fails on a field with no label', async () => {
    const { container } = render(
      <main>
        <Input />
      </main>,
    );

    const results = await axe(container);

    expect(results.violations.map((violation) => violation.id)).toContain('label');
  });
});
