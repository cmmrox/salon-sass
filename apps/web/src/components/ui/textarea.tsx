import * as React from "react"
import { cn } from "@/lib/utils"

// The prototype's field look for several lines of text (used inside Input Group).

function Textarea({ className, ...props }: React.ComponentProps<"textarea">) {
  return (
    <textarea
      data-slot="textarea"
      className={cn(
        "flex field-sizing-content min-h-16 w-full rounded-field border-0 bg-card px-3.5 py-2.5 text-field text-foreground shadow-field placeholder:text-muted-foreground focus-visible:shadow-field-focus disabled:cursor-not-allowed disabled:opacity-(--opacity-disabled) aria-invalid:shadow-field-invalid",
        className
      )}
      {...props}
    />
  )
}

export { Textarea }
