import { fireEvent, screen } from '@testing-library/react';
import { describe, expect, it, vi } from 'vitest';
import { renderWithMessages } from '@/test/render';
import { ConsoleUnavailable } from './ConsolePending';

const refresh = vi.fn();
vi.mock('next/navigation', () => ({ useRouter: () => ({ replace: vi.fn(), refresh }) }));

describe('the console unavailable state (BUG-F03-002)', () => {
  it('reports the outage without calling the API again, and retries by rendering the page again', () => {
    const fetch = vi.fn();
    vi.stubGlobal('fetch', fetch);
    renderWithMessages(<ConsoleUnavailable />);
    expect(screen.getByTestId('console-error')).toHaveAttribute('data-code', 'service_unavailable');
    expect(screen.getByRole('alert')).toBe(screen.getByTestId('console-error'));
    fireEvent.click(screen.getByTestId('console-retry'));
    expect(refresh).toHaveBeenCalledTimes(1);
    expect(fetch).not.toHaveBeenCalled();
    vi.unstubAllGlobals();
  });
});
