import { screen } from '@testing-library/react';
import { describe, expect, it } from 'vitest';

import { messages, renderWithMessages } from '@/test/render';

import { AppShell } from './AppShell';

describe('AppShell', () => {
  it("shows the product's name and puts the page's content in the main landmark", () => {
    renderWithMessages(
      <AppShell>
        <p>content</p>
      </AppShell>,
    );

    expect(screen.getByTestId('app-name')).toHaveTextContent(messages.app.name);
    expect(screen.getByRole('main')).toHaveTextContent('content');
    expect(screen.getByRole('banner')).toContainElement(screen.getByTestId('app-name'));
  });
});
