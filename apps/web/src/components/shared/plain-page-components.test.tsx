import { screen } from '@testing-library/react';
import { expect, it } from 'vitest';

import { Notice } from './Notice';
import { AppShell } from './AppShell';
import { StatusBody, StatusPanel } from './Status';
import { renderWithMessages } from '@/test/render';

import { plainPageHtml, type PlainPage } from '@/lib/plain-page/plain-page';

// The plain pages repeat the markup of the React components (CR-017): the same elements, the same
// shadcn/ui data-slots and the same Tailwind classes, so they look the same with no React. Each
// element a test id names, and the AppShell frame around it, is compared with the rendered component.

function classesOf(element: Element | null | undefined): string[] {
  expect(element).toBeTruthy();
  return [...element!.classList].sort();
}

function plain(page: PlainPage): Document {
  return new DOMParser().parseFromString(plainPageHtml(page), 'text/html');
}

/** The rendered and plain elements' tag, data-slot and classes are the same. */
function expectSame(rendered: Element | null, plainElement: Element | null, label: string) {
  expect(plainElement?.tagName, label).toBe(rendered?.tagName);
  expect(plainElement?.getAttribute('data-slot'), label).toBe(rendered?.getAttribute('data-slot'));
  expect(classesOf(plainElement), label).toEqual(classesOf(rendered));
}

/** AppShell: the frame, its header, the product's name and the main landmark. */
function expectSameFrame(document: Document, label: string) {
  const name = screen.getByTestId('app-name');
  const plainName = document.querySelector('[data-testid="app-name"]');
  expectSame(name, plainName, `${label}: app-name`);
  expectSame(name.closest('header'), plainName?.closest('header') ?? null, `${label}: header`);
  expectSame(name.closest('header')!.parentElement, plainName?.closest('header')?.parentElement ?? null, `${label}: frame`);
  expectSame(screen.getByRole('main'), document.querySelector('main'), `${label}: main`);
}

it.each([
  ['salon-not-found', 'notFound'],
  ['salon-unavailable', 'unavailable'],
] as const)('%s repeats Notice: AppShell, Card and CardTitle', (page, key) => {
  renderWithMessages(<Notice testId={page} title={key} body={key} />);
  const document = plain(page);

  expectSameFrame(document, page);
  for (const id of [page, `${page}-title`, `${page}-body`]) {
    expectSame(screen.getByTestId(id), document.querySelector(`[data-testid="${id}"]`), id);
  }
});

it('service-unavailable repeats the status page\'s unavailable state: Card, CardTitle, Alert, AlertTitle and Button', () => {
  renderWithMessages(
    <AppShell>
      <StatusPanel>
        <StatusBody state={{ kind: 'unavailable' }} retryHref="/" />
      </StatusPanel>
    </AppShell>,
  );
  const document = plain('service-unavailable');

  expectSameFrame(document, 'service-unavailable');
  for (const id of ['status-panel', 'status-heading', 'status-unavailable', 'status-unavailable-title', 'status-unavailable-body', 'status-retry']) {
    expectSame(screen.getByTestId(id), document.querySelector(`[data-testid="${id}"]`), id);
  }
});
