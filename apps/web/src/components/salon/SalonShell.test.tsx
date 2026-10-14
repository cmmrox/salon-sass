import { screen } from '@testing-library/react';
import { describe, expect, it } from 'vitest';

import { messages, renderWithMessages } from '@/test/render';

import { SalonShell, SalonShellLoading } from './SalonShell';

const beta = { slug: 'beta', name: 'Beta Hair, Beauty & Wellness Lounge of Colombo Seven', brandColour: '#1F2937', timeZone: 'Asia/Colombo' };
const gamma = { slug: 'gamma', name: 'Gamma', brandColour: '#FDE68A', timeZone: 'Europe/London' };

/** The custom properties the page puts on the root. */
function rootProperties(container: HTMLElement): Record<string, string> {
  const rule = container.querySelector('style')?.textContent ?? '';
  const body = /^:root\{(.*)\}$/.exec(rule)?.[1] ?? '';
  return Object.fromEntries(body.split(';').map((declaration) => declaration.split(':') as [string, string]));
}

describe('SalonShell', () => {
  it("filled: shows the salon's name as the page's main heading, on its brand", () => {
    const { container } = renderWithMessages(<SalonShell salon={beta} />);

    expect(screen.getByRole('heading', { level: 1 })).toHaveTextContent(beta.name);
    expect(screen.getByTestId('salon-name')).toHaveTextContent(beta.name);
    expect(screen.getByRole('banner')).toContainElement(screen.getByTestId('salon-name'));
    expect(screen.getByRole('main')).toBeInTheDocument();
    expect(rootProperties(container)).toEqual({ '--color-brand': '#1F2937', '--color-on-brand': '#ffffff', '--color-brand-soft': '#1F293722' });
  });

  it('a light brand gets dark text, never an assumed white', () => {
    const { container } = renderWithMessages(<SalonShell salon={gamma} />);

    expect(rootProperties(container)).toEqual({ '--color-brand': '#FDE68A', '--color-on-brand': '#111827', '--color-brand-soft': '#FDE68A22' });
  });

  it('shows nothing of any other salon and no loading state', () => {
    const { container } = renderWithMessages(<SalonShell salon={gamma} />);

    expect(container).not.toHaveTextContent(beta.name);
    expect(container.innerHTML).not.toContain(beta.brandColour);
    expect(screen.queryByTestId('salon-loading')).not.toBeInTheDocument();
  });
});

describe('SalonShellLoading', () => {
  it("loading: announces that it is loading, in the header's shape, with no salon's name or colour", () => {
    const { container } = renderWithMessages(<SalonShellLoading />);

    expect(screen.getByRole('status')).toHaveTextContent(messages.salon.loading);
    expect(screen.getByTestId('salon-loading')).toBeInTheDocument();
    expect(container.querySelector('style')).toBeNull();
    expect(screen.queryByRole('heading')).not.toBeInTheDocument();
    expect(screen.queryByTestId('salon-name')).not.toBeInTheDocument();
  });
});
