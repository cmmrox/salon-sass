"use client"

import * as React from "react"
import { type VariantProps } from "class-variance-authority"
import { ToggleGroup as ToggleGroupPrimitive } from "radix-ui"

// Written by the shadcn/ui CLI (toggle-group), adapted to the theme (docs/engineering/web.md, "Styling"):
// the one cn(), the items as the toggle's swatches in a wrapping row, no spacing variable through a style
// prop and no joined-button styling (unused). Radix keeps `type="single"` radio semantics, roving focus
// and arrow-key movement between the items.
import { cn } from "@/lib/utils"
import { toggleVariants } from "@/components/ui/toggle"

function ToggleGroup({
  className,
  children,
  ...props
}: React.ComponentProps<typeof ToggleGroupPrimitive.Root>) {
  return (
    <ToggleGroupPrimitive.Root
      data-slot="toggle-group"
      className={cn("flex flex-wrap items-center gap-2.5", className)}
      {...props}
    >
      {children}
    </ToggleGroupPrimitive.Root>
  )
}

function ToggleGroupItem({
  className,
  variant,
  ...props
}: React.ComponentProps<typeof ToggleGroupPrimitive.Item> &
  VariantProps<typeof toggleVariants>) {
  return (
    <ToggleGroupPrimitive.Item
      data-slot="toggle-group-item"
      className={cn(toggleVariants({ variant }), className)}
      {...props}
    />
  )
}

export { ToggleGroup, ToggleGroupItem }
