'use client';
import { useEffect, useRef, useState, type ReactNode } from 'react';
import { Sheet, SheetContent, SheetTitle } from '@/components/ui/sheet';
import { cn } from '@/lib/utils';

/**
 * A confirmation before a destructive action: the prototype's bottom sheet on phones and, in the
 * workspace from 900px, its right-side drawer. shadcn/ui's Sheet (a Radix modal dialog) makes the
 * page inert, contains keyboard focus and returns it to the control that opened it. Cancel takes
 * focus first; Escape and the backdrop cancel, except while the action is being sent (`busy`), when
 * the sheet holds focus itself and cannot be dismissed.
 */
export function Confirmation({ title, children, onCancel, workspace = false, busy = false }: { title: string; children: ReactNode; workspace?: boolean; busy?: boolean; onCancel: () => void }) {
  const content = useRef<HTMLDivElement>(null);
  // The control that opened the confirmation, read before the sheet takes focus. Radix returns
  // focus only to its own trigger, so the sheet restores this one itself.
  const [opener] = useState(() => (typeof document !== 'undefined' && document.activeElement instanceof HTMLElement ? document.activeElement : null));
  // Where the opener sat in the viewport. Radix locks the page's scroll while the sheet is open, so
  // the browser's scroll anchoring cannot keep the opener in place when feedback appears above it;
  // the sheet does that itself when it closes.
  const [openerTop] = useState(() => opener?.getBoundingClientRect().top ?? 0);
  const cancel = () => { if (!busy) onCancel(); };
  useEffect(() => { if (busy) content.current?.focus(); }, [busy]);
  const restore = () => {
    if (!opener?.isConnected) return;
    const shift = opener.getBoundingClientRect().top - openerTop;
    if (shift) window.scrollBy(0, shift);
    opener.focus({ preventScroll: true });
    // A failed submission closes the sheet in the same commit that enables its trigger. Wait for
    // that DOM update only when the immediate restoration could not take focus.
    if (document.activeElement !== opener) queueMicrotask(() => { if (opener.isConnected && document.activeElement === document.body) opener.focus(); });
  };
  return (
    <Sheet open onOpenChange={(open) => { if (!open) cancel(); }}>
      <SheetContent
        ref={content}
        side="bottom"
        aria-modal="true"
        aria-describedby={undefined}
        data-testid="account-confirmation"
        onOpenAutoFocus={(event) => { event.preventDefault(); const safe = content.current?.querySelector<HTMLButtonElement>('[data-testid="account-cancel-action"]:not(:disabled)'); (busy || !safe ? content.current : safe)?.focus(); }}
        onCloseAutoFocus={(event) => { event.preventDefault(); restore(); }}
        onEscapeKeyDown={(event) => { if (busy) event.preventDefault(); }}
        onInteractOutside={(event) => { if (busy) event.preventDefault(); }}
        className={cn(
          'mx-auto max-h-(--size-sheet-max-height) w-full max-w-(--size-customer) overflow-y-auto rounded-(--radius-sheet) p-6 pb-[calc(var(--spacing)*6+env(safe-area-inset-bottom,0px))]',
          // The sheet's handle.
          'before:mx-auto before:mb-2 before:block before:h-1 before:w-(--size-sheet-handle) before:rounded-action before:bg-border before:content-[""]',
          workspace && 'rail:top-0 rail:left-auto rail:h-full rail:max-h-none rail:w-(--size-drawer) rail:max-w-full rail:m-0 rail:rounded-none rail:px-8 rail:py-6 rail:before:hidden',
        )}
      >
        <SheetTitle className={cn(workspace && 'rail:text-workspace-title')}>{title}</SheetTitle>
        {children}
      </SheetContent>
    </Sheet>
  );
}
