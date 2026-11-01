'use client';

import * as React from 'react';
import { Tabs as TabsPrimitive } from 'radix-ui';

// Written by the shadcn/ui CLI (tabs), adapted to the prototype (docs/engineering/web.md, "Styling"):
// the one cn(), and the prototype's settings section chips (`.chips.brtabs`): pill triggers outlined in
// ink, the selected one filled with the salon's brand and its readable foreground; a row that scrolls sideways below 900px
// and wraps from it. No dark variants, no CLI focus ring (the theme's focus outline), the unused
// variants removed. Radix keeps the tab roles, arrow-key movement and the active panel's labelling.
import { cn } from '@/lib/utils';

function Tabs({ className, ...props }: React.ComponentProps<typeof TabsPrimitive.Root>) {
  return <TabsPrimitive.Root data-slot="tabs" className={cn('flex flex-col gap-3', className)} {...props} />;
}

function TabsList({ className, ...props }: React.ComponentProps<typeof TabsPrimitive.List>) {
  return (
    <TabsPrimitive.List
      data-slot="tabs-list"
      className={cn('flex max-w-full gap-2 overflow-x-auto pb-1 rail:flex-wrap rail:overflow-visible', className)}
      {...props}
    />
  );
}

function TabsTrigger({ className, ...props }: React.ComponentProps<typeof TabsPrimitive.Trigger>) {
  return (
    <TabsPrimitive.Trigger
      data-slot="tabs-trigger"
      className={cn(
        'inline-flex min-h-touch shrink-0 items-center justify-center rounded-action border border-foreground bg-transparent px-4 text-small font-medium whitespace-nowrap text-foreground disabled:pointer-events-none disabled:opacity-50 data-[state=active]:border-primary data-[state=active]:bg-primary data-[state=active]:text-primary-foreground',
        className,
      )}
      {...props}
    />
  );
}

function TabsContent({ className, ...props }: React.ComponentProps<typeof TabsPrimitive.Content>) {
  return <TabsPrimitive.Content data-slot="tabs-content" className={cn('min-w-0', className)} {...props} />;
}

export { Tabs, TabsList, TabsTrigger, TabsContent };
