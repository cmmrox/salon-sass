import { fireEvent, screen, waitFor } from '@testing-library/react';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { renderWithMessages } from '@test/render';
import { ExportDownload } from './ExportDownload';

const TOKEN = 'AbCdEfGhIjKlMnOpQrStUvWxYz0123456789_-abcde';
let bodies: unknown[];

function stub(answer: (url: string) => Response) {
  vi.stubGlobal('fetch', async (url: string, init?: RequestInit) => {
    bodies.push(JSON.parse(String(init?.body)));
    return answer(url);
  });
}

beforeEach(() => {
  bodies = [];
  window.history.replaceState(null, '', `/exports#${TOKEN}`);
});
afterEach(() => {
  vi.unstubAllGlobals();
  window.history.replaceState(null, '', '/');
});

describe('the Owner export download page (F03-S03)', () => {
  it('takes the token from the fragment, removes it from the address and shows the archive facts', async () => {
    stub(() =>
      Response.json({
        readyAt: '2026-01-02T00:01:00Z',
        deleteAt: '2026-02-01T00:00:00Z',
        sizeBytes: 2048,
        sha256: 'cd'.repeat(32),
      }),
    );
    renderWithMessages(<ExportDownload />);
    expect(screen.getByTestId('exports-loading')).toBeInTheDocument();
    expect(await screen.findByTestId('exports-sha256')).toHaveTextContent('cd'.repeat(32));
    expect(window.location.hash).toBe('');
    expect(window.location.href).not.toContain(TOKEN);
    expect(bodies).toEqual([{ token: TOKEN }]);
    expect(screen.getByTestId('exports-delete-notice')).toHaveTextContent('deleted for good');
    expect(screen.getByTestId('exports-download')).toHaveAttribute('data-slot', 'button');
    expect(screen.getByTestId('exports-card')).toHaveAttribute('data-slot', 'card');
  });

  it('says an unknown or expired link is not valid, without naming a salon', async () => {
    stub(() => Response.json({ code: 'not_found' }, { status: 404 }));
    renderWithMessages(<ExportDownload />);
    expect(await screen.findByTestId('exports-unknown')).toHaveTextContent('not valid or has expired');
    expect(screen.queryByTestId('exports-download')).not.toBeInTheDocument();
  });

  it('offers a retry when the service cannot be reached', async () => {
    stub(() => Response.json({ code: 'service_unavailable' }, { status: 503 }));
    renderWithMessages(<ExportDownload />);
    fireEvent.click(await screen.findByTestId('exports-retry'));
    expect(bodies).toHaveLength(2);
  });

  it('without a token asks for the link from the email and calls nothing', async () => {
    window.history.replaceState(null, '', '/exports');
    stub(() => Response.json({}));
    renderWithMessages(<ExportDownload />);
    expect(await screen.findByTestId('exports-no-link')).toBeInTheDocument();
    expect(bodies).toEqual([]);
  });

  it('reads a new link opened in the same tab, where only the fragment changes', async () => {
    stub(() => Response.json({ code: 'not_found' }, { status: 404 }));
    renderWithMessages(<ExportDownload />);
    await screen.findByTestId('exports-unknown');
    window.location.hash = `#${'B'.repeat(43)}`;
    window.dispatchEvent(new HashChangeEvent('hashchange'));
    await waitFor(() => expect(bodies).toEqual([{ token: TOKEN }, { token: 'B'.repeat(43) }]));
    expect(window.location.hash).toBe('');
  });
});
