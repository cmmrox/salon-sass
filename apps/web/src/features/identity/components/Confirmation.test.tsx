import { act, cleanup, fireEvent, screen } from '@testing-library/react';
import { afterEach, expect, it, vi } from 'vitest';
import { renderWithMessages } from '@test/render';
import { Confirmation } from './Confirmation';
import { Button } from './Controls';

// Radix restores focus after its own cleanup tick.
const settle = () => act(() => new Promise((resolve) => setTimeout(resolve, 20)));
afterEach(() => {
  cleanup();
  document.body.innerHTML = '';
});

const actions = (busy = false, confirm = vi.fn(), cancel = vi.fn()) => (
  <>
    <Button testId="account-confirm-action" danger disabled={busy} onClick={confirm}>
      Confirm
    </Button>
    <Button testId="account-cancel-action" secondary disabled={busy} onClick={cancel}>
      Cancel
    </Button>
  </>
);

it('is a labelled modal dialog that focuses the safe cancellation action and returns focus to the initiating control', async () => {
  const trigger = document.createElement('button');
  document.body.append(trigger);
  trigger.focus();
  const cancel = vi.fn();
  const { unmount } = renderWithMessages(
    <Confirmation title="Confirm this action" onCancel={cancel}>
      {actions(false, vi.fn(), cancel)}
    </Confirmation>,
  );
  const dialog = screen.getByRole('dialog', { name: 'Confirm this action' });
  expect(dialog).toHaveAttribute('aria-modal', 'true');
  expect(dialog).toHaveAttribute('data-testid', 'account-confirmation');
  expect(screen.getByTestId('account-cancel-action')).toHaveFocus();
  fireEvent.click(screen.getByTestId('account-cancel-action'));
  expect(cancel).toHaveBeenCalledOnce();
  unmount();
  await settle();
  expect(trigger).toHaveFocus();
});

it('routes Escape to cancellation without confirming a mutation', () => {
  const cancel = vi.fn();
  const mutate = vi.fn();
  renderWithMessages(
    <Confirmation title="Confirm this action" onCancel={cancel}>
      {actions(false, mutate)}
    </Confirmation>,
  );
  fireEvent.keyDown(screen.getByRole('dialog'), { key: 'Escape' });
  expect(cancel).toHaveBeenCalledOnce();
  expect(mutate).not.toHaveBeenCalled();
});

it('cancels on a press outside the sheet, and not on a press inside it', async () => {
  const cancel = vi.fn();
  renderWithMessages(
    <Confirmation title="Confirm this action" onCancel={cancel}>
      {actions()}
    </Confirmation>,
  );
  await settle();
  // A whole press: in jsdom Radix completes an outside press on the click that follows it.
  const press = (target: Element) => {
    fireEvent.pointerDown(target);
    fireEvent.mouseDown(target);
    fireEvent.mouseUp(target);
    fireEvent.click(target);
  };
  press(screen.getByTestId('account-cancel-action').parentElement!);
  expect(cancel).not.toHaveBeenCalled();
  press(document.querySelector('[data-slot="sheet-overlay"]')!);
  expect(cancel).toHaveBeenCalledOnce();
});

it('wraps Tab and Shift+Tab inside the modal action controls', () => {
  renderWithMessages(
    <Confirmation title="Confirm this action" onCancel={() => {}}>
      {actions()}
    </Confirmation>,
  );
  const first = screen.getByTestId('account-confirm-action');
  const last = screen.getByTestId('account-cancel-action');
  expect(last).toHaveFocus();
  fireEvent.keyDown(last, { key: 'Tab' });
  expect(first).toHaveFocus();
  fireEvent.keyDown(first, { key: 'Tab', shiftKey: true });
  expect(last).toHaveFocus();
});

it('keeps a submitted confirmation open and focuses the sheet while its actions are disabled', async () => {
  const cancel = vi.fn();
  renderWithMessages(
    <Confirmation busy title="Confirm this action" onCancel={cancel}>
      {actions(true)}
    </Confirmation>,
  );
  await settle();
  const dialog = screen.getByRole('dialog');
  expect(dialog).toHaveFocus();
  const overlay = document.querySelector('[data-slot="sheet-overlay"]')!;
  fireEvent.keyDown(dialog, { key: 'Escape' });
  fireEvent.pointerDown(overlay);
  fireEvent.click(overlay);
  fireEvent.keyDown(dialog, { key: 'Tab' });
  expect(dialog).toHaveFocus();
  expect(cancel).not.toHaveBeenCalled();
  expect(screen.getByRole('dialog')).toBeInTheDocument();
});
