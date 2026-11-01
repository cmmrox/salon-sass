'use client';

import { CircleCheckIcon } from 'lucide-react';
import { Toaster as Sonner, type ToasterProps } from 'sonner';

// Written by the shadcn/ui CLI (sonner), adapted to the theme (docs/engineering/web.md, "Styling"):
// there is no dark mode, so no next-themes and a fixed light theme; the toast is the theme's white
// card with ink text, a line border and the card radius, set through Tailwind classes rather than the
// CLI's inline style; only the success icon the product uses is kept. Sonner announces each toast in
// its own polite live region, and (as input-otp does) inserts its own runtime style sheet.
const Toaster = ({ ...props }: ToasterProps) => {
  return (
    <Sonner
      theme="light"
      className="toaster group"
      icons={{
        success: <CircleCheckIcon className="size-4" aria-hidden="true" />,
      }}
      toastOptions={{
        classNames: {
          toast: '!rounded-card !border-border !bg-card !font-sans !text-body !text-foreground !shadow-popover',
        },
      }}
      {...props}
    />
  );
};

export { Toaster };
