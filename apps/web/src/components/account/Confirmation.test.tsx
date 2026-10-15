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

it('cancels only outside the dialog bounds, preserving clicks in its padding', () => {
 const cancel = vi.fn();
 renderWithMessages(<Confirmation title="Confirm this action" onCancel={cancel}><Button testId="account-cancel-action">Cancel</Button></Confirmation>);
 const dialog = screen.getByRole('dialog');
 vi.spyOn(dialog, 'getBoundingClientRect').mockReturnValue({ left: 100, top: 100, right: 400, bottom: 500, width: 300, height: 400, x: 100, y: 100, toJSON: () => ({}) });
 fireEvent.click(dialog, { clientX: 120, clientY: 120 }); expect(cancel).not.toHaveBeenCalled();
 fireEvent.click(dialog, { clientX: 20, clientY: 20 }); expect(cancel).toHaveBeenCalledOnce();
});

it('wraps Tab and Shift+Tab inside the modal action controls', () => {
 renderWithMessages(<Confirmation title="Confirm this action" onCancel={() => {}}><Button testId="account-confirm-action">Confirm</Button><Button testId="account-cancel-action">Cancel</Button></Confirmation>);
 const first = screen.getByTestId('account-confirm-action'); const last = screen.getByTestId('account-cancel-action');
 expect(last).toHaveFocus(); fireEvent.keyDown(last, { key: 'Tab' }); expect(first).toHaveFocus();
 fireEvent.keyDown(first, { key: 'Tab', shiftKey: true }); expect(last).toHaveFocus();
});

it('keeps a submitted confirmation open and focuses the dialog while actions are disabled', () => {
 const cancel = vi.fn();
 renderWithMessages(<Confirmation busy title="Confirm this action" onCancel={cancel}><Button disabled testId="account-confirm-action">Confirm</Button><Button disabled testId="account-cancel-action">Cancel</Button></Confirmation>);
 const dialog = screen.getByRole('dialog'); expect(dialog).toHaveFocus();
 fireEvent(dialog, new Event('cancel', { cancelable: true })); fireEvent.click(dialog, { clientX: 20, clientY: 20 });
 fireEvent.keyDown(dialog, { key: 'Tab' }); expect(dialog).toHaveFocus(); expect(cancel).not.toHaveBeenCalled(); expect(dialog).toHaveAttribute('open');
});
