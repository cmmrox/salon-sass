'use client';
import type { ReactNode } from 'react';
import { Sheet, SheetContent, SheetDescription, SheetTitle, SheetTrigger } from '@/components/ui/sheet';
import { cn } from '@/lib/utils';

/**
 * A create form in the prototype's sheet grammar: a bottom sheet on phones and a full-height right
 * drawer from 900px (shadcn/ui Sheet). The page behind is inert, focus stays inside, Escape and the
 * backdrop cancel except while a request is being sent, and focus returns to the opening button.
 */
export function ConsoleSheet({
  title,
  description,
  trigger,
  open,
  onOpenChange,
  busy = false,
  testId,
  children,
}: {
  title: string;
  description?: string;
  trigger: ReactNode;
  open: boolean;
  onOpenChange: (open: boolean) => void;
  busy?: boolean;
  testId: string;
  children: ReactNode;
}) {
  return (
    <Sheet
      open={open}
      onOpenChange={(next) => {
        if (!busy || next) onOpenChange(next);
      }}
    >
      <SheetTrigger asChild>{trigger}</SheetTrigger>
      <SheetContent
        side="bottom"
        data-testid={testId}
        onEscapeKeyDown={(event) => {
          if (busy) event.preventDefault();
        }}
        onInteractOutside={(event) => {
          if (busy) event.preventDefault();
        }}
        className={cn(
          'mx-auto max-h-(--size-sheet-max-height) w-full max-w-(--size-customer) gap-4 overflow-y-auto rounded-(--radius-sheet) p-6 pb-[calc(var(--spacing)*6+env(safe-area-inset-bottom,0px))] outline-none',
          'before:mx-auto before:mb-2 before:block before:h-1 before:w-(--size-sheet-handle) before:rounded-action before:bg-border before:content-[""]',
          'rail:top-0 rail:left-auto rail:m-0 rail:h-full rail:max-h-none rail:w-(--size-drawer) rail:max-w-full rail:rounded-none rail:px-8 rail:py-6 rail:before:hidden',
        )}
      >
        <SheetTitle className="rail:text-workspace-title">{title}</SheetTitle>
        {description ? (
          <SheetDescription>{description}</SheetDescription>
        ) : (
          <SheetDescription className="sr-only">{title}</SheetDescription>
        )}
        {children}
      </SheetContent>
    </Sheet>
  );
}
