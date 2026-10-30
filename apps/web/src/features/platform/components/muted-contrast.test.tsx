import { screen } from '@testing-library/react';
import { describe, expect, it, vi } from 'vitest';
import { DirectorySearch } from './DirectoryControls';
import { Empty, EmptyDescription, EmptyHeader, EmptyTitle } from '@/components/ui/empty';
import { Field, FieldDescription, FieldLabel } from '@/components/ui/field';
import { Input } from '@/components/ui/input';
import { Item, ItemContent, ItemDescription, ItemGroup, ItemTitle } from '@/components/ui/item';
import { Sheet, SheetContent, SheetDescription, SheetHeader, SheetTitle } from '@/components/ui/sheet';
import { renderWithMessages } from '@/test/render';
import { themeValue, utilityColour } from '@/test/theme';
import { contrastRatio } from '@/styles/brand';

vi.mock('next/navigation', () => ({ useRouter: () => ({ replace: vi.fn(), refresh: vi.fn() }) }));

const SIZE = /^text-(small|body|large|title|label|field|nav|rail|login-|balance|center|left|right)/;

/** The text colour a class list draws: the canvas variant inside a canvas surface, otherwise the base colour. */
function drawnText(element: HTMLElement): string {
  const classes = [...element.classList];
  const onCanvas = element.parentElement?.closest('[data-surface="canvas"]');
  const canvasText = classes.find((name) => name.startsWith('in-data-[surface=canvas]:text-'));
  if (onCanvas && canvasText) return utilityColour(canvasText.slice('in-data-[surface=canvas]:'.length), 'text');
  const text = classes.find((name) => name.startsWith('text-') && !SIZE.test(name));
  return text ? utilityColour(text, 'text') : themeValue('--foreground');
}

/** The background behind an element: the nearest colour fill, or the page canvas. */
function drawnBackground(element: HTMLElement): string {
  for (let node: HTMLElement | null = element; node; node = node.parentElement) {
    const fill = [...node.classList].find((name) => /^bg-(background|card|popover|muted|secondary)$/.test(name));
    if (fill) return utilityColour(fill, 'bg');
  }
  return themeValue('--background');
}

function expectReadable(element: HTMLElement) {
  expect(contrastRatio(drawnText(element), drawnBackground(element)), element.textContent ?? '').toBeGreaterThanOrEqual(4.5);
}

// BUG-F03-004: muted text is 4.83:1 on white but 4.25:1 on the warm canvas, so descriptions on the
// canvas (sheets, canvas panels, the console's search help) use ink (ui-ux-standards.md, BUG-F01-003).
describe('muted descriptions', () => {
  it('are readable on a sheet, which is the canvas', () => {
    renderWithMessages(
      <Sheet open>
        <SheetContent>
          <SheetHeader><SheetTitle>Title</SheetTitle><SheetDescription data-testid="sheet-description">What happens</SheetDescription></SheetHeader>
          <Field><FieldLabel htmlFor="f">Field</FieldLabel><Input id="f" /><FieldDescription data-testid="field-description">Help</FieldDescription></Field>
        </SheetContent>
      </Sheet>,
    );
    expectReadable(screen.getByTestId('sheet-description'));
    expectReadable(screen.getByTestId('field-description'));
  });

  it('are readable in an empty state on the canvas, and stay muted on white', () => {
    renderWithMessages(
      <>
        <Empty surface="canvas" data-testid="canvas-empty"><EmptyHeader><EmptyTitle>None</EmptyTitle><EmptyDescription data-testid="canvas-empty-description">Nothing yet</EmptyDescription></EmptyHeader></Empty>
        <Empty surface="card" data-testid="card-empty"><EmptyHeader><EmptyTitle>None</EmptyTitle><EmptyDescription data-testid="card-empty-description">Nothing yet</EmptyDescription></EmptyHeader></Empty>
      </>,
    );
    expectReadable(screen.getByTestId('canvas-empty-description'));
    expectReadable(screen.getByTestId('card-empty-description'));
    expect(drawnText(screen.getByTestId('card-empty-description'))).toBe(themeValue('--muted-foreground'));
  });

  it('are readable under the console search, which sits on the canvas', () => {
    renderWithMessages(<DirectorySearch area="salons" help="Search by name" busy={false} onSearch={() => undefined} />);
    expectReadable(screen.getByText('Search by name'));
  });

  it('stay muted on a white list row', () => {
    renderWithMessages(<ItemGroup><Item variant="outline" className="bg-card"><ItemContent><ItemTitle>Row</ItemTitle><ItemDescription data-testid="row-description">Detail</ItemDescription></ItemContent></Item></ItemGroup>);
    expect(drawnText(screen.getByTestId('row-description'))).toBe(themeValue('--muted-foreground'));
    expectReadable(screen.getByTestId('row-description'));
  });
});
