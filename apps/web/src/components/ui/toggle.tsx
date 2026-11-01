"use client"

import * as React from "react"
import { cva, type VariantProps } from "class-variance-authority"
import { Toggle as TogglePrimitive } from "radix-ui"

// Written by the shadcn/ui CLI (toggle), adapted to the theme (docs/engineering/web.md, "Styling"): the one
// cn(), no dark variants, no CLI focus ring (the theme's focus outline), and the product's two variants: the
// prototype's colour swatch (`.av`, a 44px circle), with a visible ink ring when it is on (F04-S02), and its
// chip (`.chip`, a weekday open or closed, F04-S03), filled in the brand colour when pressed as the settings
// tabs are; and a segment of its segmented control (`.seg2`, F05-S02: a service's deposit and how Popular is
// chosen), ink when pressed. Unpressed text is ink, not the prototype's muted grey, which is under 4.5:1 on the
// canvas. Radix keeps the pressed state, `aria-pressed` or the group's radio semantics, and keyboard handling.
import { cn } from "@/lib/utils"

const toggleVariants = cva(
  "inline-flex shrink-0 cursor-pointer items-center justify-center disabled:pointer-events-none disabled:opacity-(--opacity-disabled)",
  {
    variants: {
      variant: {
        swatch:
          "size-touch rounded-full border border-border data-[state=on]:outline-2 data-[state=on]:outline-offset-2 data-[state=on]:outline-foreground",
        chip:
          "min-h-touch min-w-20 rounded-action border border-foreground bg-transparent px-4 text-small font-medium text-foreground data-[state=on]:border-primary data-[state=on]:bg-primary data-[state=on]:text-primary-foreground",
        segment:
          "min-h-touch flex-1 rounded-action px-4 text-small font-medium whitespace-nowrap text-foreground data-[state=on]:bg-foreground data-[state=on]:text-card",
      },
    },
    defaultVariants: {
      variant: "swatch",
    },
  }
)

function Toggle({
  className,
  variant,
  ...props
}: React.ComponentProps<typeof TogglePrimitive.Root> &
  VariantProps<typeof toggleVariants>) {
  return (
    <TogglePrimitive.Root
      data-slot="toggle"
      className={cn(toggleVariants({ variant, className }))}
      {...props}
    />
  )
}

export { Toggle, toggleVariants }
