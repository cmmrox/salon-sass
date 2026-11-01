"use client"

import * as React from "react"
import { cva, type VariantProps } from "class-variance-authority"
import { Toggle as TogglePrimitive } from "radix-ui"

// Written by the shadcn/ui CLI (toggle), adapted to the theme (docs/engineering/web.md, "Styling"): the one
// cn(), no dark variants, no CLI focus ring (the theme's focus outline), and the product's one variant, the
// prototype's colour swatch (`.av`, a 44px circle), with a visible ink ring when it is on. Radix keeps the
// pressed state, `aria-pressed` or the group's radio semantics, and keyboard handling.
import { cn } from "@/lib/utils"

const toggleVariants = cva(
  "inline-flex shrink-0 cursor-pointer items-center justify-center disabled:pointer-events-none disabled:opacity-(--opacity-disabled)",
  {
    variants: {
      variant: {
        swatch:
          "size-touch rounded-full border border-border data-[state=on]:outline-2 data-[state=on]:outline-offset-2 data-[state=on]:outline-foreground",
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
