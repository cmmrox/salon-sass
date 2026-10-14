import { cleanup, fireEvent, screen } from '@testing-library/react';
import { afterEach, beforeEach, expect, it, vi } from 'vitest';
import { renderWithMessages } from '@/test/render';
import { Confirmation } from './Confirmation';
import { Button } from './Controls';
beforeEach(() => {
 Object.defineProperty(HTMLDialogElement.prototype, 'showModal', { configurable: true, value: function (this: HTMLDialogElement) { this.open = true; } });
 Object.defineProperty(HTMLDialogElement.prototype, 'close', { configurable: true, value: function (this: HTMLDialogElement) { this.open = false; } });
});
afterEach(() => { cleanup(); Reflect.deleteProperty(HTMLDialogElement.prototype, 'showModal'); Reflect.deleteProperty(HTMLDialogElement.prototype, 'close'); });
it('focuses the safe cancellation action and returns focus to the initiating control', () => {
 const trigger = document.createElement('button'); document.body.append(trigger); trigger.focus();
 const cancel = vi.fn();
 const { unmount } = renderWithMessages(<Confirmation title="Confirm this action" onCancel={cancel}><Button testId="account-confirm-action" danger>Confirm</Button><Button testId="account-cancel-action" secondary onClick={cancel}>Cancel</Button></Confirmation>);
 expect(screen.getByRole('dialog', { name: 'Confirm this action' })).toHaveAttribute('open');
 expect(screen.getByTestId('account-cancel-action')).toHaveFocus();
 fireEvent.click(screen.getByTestId('account-cancel-action')); expect(cancel).toHaveBeenCalledOnce();
 unmount(); expect(trigger).toHaveFocus(); trigger.remove();
});
it('routes native Escape cancellation to the controlled state without confirming a mutation', () => {
 const cancel = vi.fn(); const mutate = vi.fn();
 renderWithMessages(<Confirmation title="Confirm this action" onCancel={cancel}><Button testId="account-confirm-action" onClick={mutate}>Confirm</Button><Button testId="account-cancel-action">Cancel</Button></Confirmation>);
 fireEvent(screen.getByRole('dialog'), new Event('cancel', { cancelable: true }));
 expect(cancel).toHaveBeenCalledOnce(); expect(mutate).not.toHaveBeenCalled();
});
