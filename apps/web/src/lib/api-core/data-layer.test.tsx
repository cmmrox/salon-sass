import { render, screen, waitFor } from '@testing-library/react';
import { useQueryClient } from '@tanstack/react-query';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { DataLayer, moduleData } from './data-layer';
import { workspaceSession } from './request';

type Reads = { 'team-members': { answer: { owner: string } } };
const team = moduleData<Reads, Record<never, never>>('staff');

let owner: string;
let asked: number;
beforeEach(() => {
  asked = 0;
  vi.stubGlobal('fetch', async () => {
    asked++;
    return Response.json({ owner });
  });
});
afterEach(() => {
  vi.unstubAllGlobals();
});

function Members() {
  const read = team.useRead(workspaceSession('staff'), 'team-members');
  const keys = useQueryClient()
    .getQueryCache()
    .getAll()
    .map((query) => JSON.stringify(query.queryKey));
  return (
    <p data-testid="members" data-keys={keys.join(' ')}>
      {read.data?.owner ?? 'loading'}
    </p>
  );
}

// Tenant isolation in the browser (decision 121, docs/architecture/multi-tenancy.md): one person's or one salon's
// answers are never shown under another's scope.
describe('the data scope', () => {
  it('starts every key with the scope, the session kind and the module', async () => {
    owner = 'salon-a';
    render(
      <DataLayer scope="staff:a">
        <Members />
      </DataLayer>,
    );
    await waitFor(() => expect(screen.getByTestId('members')).toHaveTextContent('salon-a'));
    expect(screen.getByTestId('members').dataset.keys).toBe('["staff:a","staff","staff","team-members"]');
  });

  it('starts empty for another scope: nothing read for one session is shown for the next', async () => {
    owner = 'person-a';
    const view = render(
      <DataLayer scope="staff:a">
        <Members />
      </DataLayer>,
    );
    await waitFor(() => expect(screen.getByTestId('members')).toHaveTextContent('person-a'));
    owner = 'person-b';
    view.rerender(
      <DataLayer scope="staff:b">
        <Members />
      </DataLayer>,
    );
    // The new scope's client has nothing: the earlier answer is not painted while the new one is read.
    expect(screen.getByTestId('members')).toHaveTextContent('loading');
    await waitFor(() => expect(screen.getByTestId('members')).toHaveTextContent('person-b'));
    expect(screen.getByTestId('members').dataset.keys).toBe('["staff:b","staff","staff","team-members"]');
    expect(asked).toBe(2);
  });
});

// F03-S03 AC-1: a suspension is a deliberate cut of access, not an outage.
describe('a suspended salon', () => {
  it('replaces the frame with its notice and leaves, from any read refused for it', async () => {
    vi.stubGlobal('fetch', async () => Response.json({ code: 'salon_suspended' }, { status: 503 }));
    const leave = vi.fn();
    render(
      <DataLayer scope="staff:a" onSuspended={leave} suspended={<p data-testid="frame-suspended" />}>
        <Members />
      </DataLayer>,
    );
    expect(await screen.findByTestId('frame-suspended')).toBeInTheDocument();
    expect(screen.queryByTestId('members')).toBeNull();
    expect(leave).toHaveBeenCalledOnce();
  });

  it('is not an API that could not be reached: that one leaves nothing', async () => {
    vi.stubGlobal('fetch', async () => Response.json({ code: 'service_unavailable' }, { status: 503 }));
    const leave = vi.fn();
    render(
      <DataLayer scope="staff:a" onSuspended={leave} suspended={<p data-testid="frame-suspended" />}>
        <Members />
      </DataLayer>,
    );
    await waitFor(() => expect(screen.getByTestId('members')).toHaveTextContent('loading'));
    await new Promise((resolve) => setTimeout(resolve, 20));
    expect(screen.queryByTestId('frame-suspended')).toBeNull();
    expect(leave).not.toHaveBeenCalled();
  });
});
