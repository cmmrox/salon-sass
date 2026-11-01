"use client"

import * as React from "react"
import { cn } from "@/lib/utils"
import { Switch as SwitchPrimitive } from "radix-ui"

// The Checkbox's colours as a track: off, a line in muted ink on white with a muted thumb; on, it fills with
// the salon's brand and its thumb takes the brand's readable foreground. Keyboard focus is the theme's outline;
// the 44px target is its label (F05-S01, "Bookable online").
function Switch({
  className,
  ...props
}: React.ComponentProps<typeof SwitchPrimitive.Root>) {
  return (
    <SwitchPrimitive.Root
      data-slot="switch"
      className={cn(
        "peer inline-flex h-7 w-12 shrink-0 cursor-pointer items-center rounded-action border border-muted-foreground bg-card p-0.5 transition-colors disabled:cursor-not-allowed disabled:opacity-(--opacity-disabled) data-[state=checked]:border-primary data-[state=checked]:bg-primary",
        className
      )}
      {...props}
    >
      <SwitchPrimitive.Thumb
        data-slot="switch-thumb"
        className="pointer-events-none block size-5 rounded-action bg-muted-foreground transition-transform data-[state=checked]:translate-x-5 data-[state=checked]:bg-primary-foreground"
      />
    </SwitchPrimitive.Root>
  )
}

export { Switch }
