import { cleanup, render, screen } from '@testing-library/react';
import { afterEach, describe, expect, it } from 'vitest';
import { atWidth } from '@test/a11y';
import { Sheet, SheetContent, SheetTitle } from './sheet';

/**
 * The phone's handle on a bottom sheet (F06-S03 AC-14, decision 103), measured in real Chromium with the application's compiled
 * styles, since only a real layout shows it. The handle is the sheet's `::before`, an empty box in a flex column: while the
 * content overflows, the column shrinks its items, and an empty box's automatic minimum height is nothing, so it went to 0px.
 * These are the classes every delivered bottom sheet writes for its height, its scrolling and its handle.
 */
const BOTTOM_SHEET =
  'mx-auto max-h-(--size-sheet-max-height) w-full max-w-(--size-customer) gap-4 overflow-y-auto p-6 ' +
  'before:mx-auto before:mb-2 before:block before:h-1 before:w-(--size-sheet-handle) before:rounded-action before:bg-border before:content-[""] ' +
  'rail:top-0 rail:left-auto rail:m-0 rail:h-full rail:max-h-none rail:w-(--size-drawer) rail:max-w-full rail:before:hidden';

const HANDLE_HEIGHT = '4px';

function sheet(contentHeight: number) {
  return render(
    <Sheet open>
      <SheetContent side="bottom" className={BOTTOM_SHEET} aria-describedby={undefined} data-testid="a-sheet">
        <SheetTitle>A sheet</SheetTitle>
        {/* Real content: a flex item with content keeps its height, as a sheet's own sections do. */}
        <div>
          <div style={{ height: contentHeight }} />
        </div>
      </SheetContent>
    </Sheet>,
  );
}

const handleOf = (element: Element) => getComputedStyle(element, '::before');

afterEach(cleanup);

describe('the sheet’s handle', () => {
  it('is at its full height on a phone when the content overflows', async () => {
    await atWidth(360);
    sheet(3000);
    const content = screen.getByTestId('a-sheet');

    expect(content.scrollHeight, 'the content really overflows').toBeGreaterThan(content.clientHeight);
    expect(handleOf(content).height).toBe(HANDLE_HEIGHT);
  });

  it('is at the same height when the content fits', async () => {
    await atWidth(360);
    sheet(40);
    const content = screen.getByTestId('a-sheet');

    expect(content.scrollHeight, 'the content really fits').toBeLessThanOrEqual(content.clientHeight);
    expect(handleOf(content).height).toBe(HANDLE_HEIGHT);
  });

  it('is gone from the width where the sheet is a drawer', async () => {
    await atWidth(1280);
    sheet(3000);

    expect(handleOf(screen.getByTestId('a-sheet')).display).toBe('none');
  });
});
