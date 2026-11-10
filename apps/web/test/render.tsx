import { render, type RenderResult } from '@testing-library/react';
import { NextIntlClientProvider } from 'next-intl';
import type { ReactElement } from 'react';

import { DataLayer } from '@/lib/api-core/data-layer';

import messages from '../messages/en.json';

/**
 * Renders a component with the real English messages and its own empty data layer, the way the application's frame
 * does: nothing one test read is seen by the next. A suspended salon leaves through `onSuspended` (a test's spy; the
 * application reloads, which jsdom cannot) and shows the frame's `suspended` notice.
 */
export function renderWithMessages(
  ui: ReactElement,
  frame: { onSuspended?: () => void; suspended?: ReactElement } = {},
): RenderResult {
  return render(
    <NextIntlClientProvider locale="en" messages={messages}>
      <DataLayer scope="test" onSuspended={frame.onSuspended ?? (() => undefined)} suspended={frame.suspended}>
        {ui}
      </DataLayer>
    </NextIntlClientProvider>,
  );
}

export { messages };
