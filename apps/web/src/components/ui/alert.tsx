import * as React from "react"
import { cva, type VariantProps } from "class-variance-authority"
import { cn } from "@/lib/utils"
import { Slot } from "radix-ui"

// The prototype's state panels: a 20px-radius surface in the state's colour, with ink text.
// Normal-size text stays ink on every status surface (BUG-F01-003).
const alertVariants = cva(
  "relative flex w-full min-w-0 flex-col gap-3 rounded-field p-4 text-foreground",
  {
    variants: {
      variant: {
        default: "bg-card",
        caution: "bg-caution-surface *:data-[slot=alert-title]:text-caution",
        positive: "bg-positive-surface",
        destructive: "bg-negative-surface",
      },
    },
    defaultVariants: {
      variant: "default",
    },
  }
)

function Alert({
  className,
  variant,
  ...props
}: React.ComponentProps<"div"> & VariantProps<typeof alertVariants>) {
  return (
    <div
      data-slot="alert"
      role="alert"
      className={cn(alertVariants({ variant }), className)}
      {...props}
    />
  )
}

/** The alert's heading. `asChild` renders it as the heading element the page needs. */
function AlertTitle({
  className,
  asChild = false,
  ...props
}: React.ComponentProps<"div"> & { asChild?: boolean }) {
  const Comp = asChild ? Slot.Root : "div"
  return (
    <Comp
      data-slot="alert-title"
      className={cn(
        "text-large leading-heading font-semibold wrap-anywhere",
        className
      )}
      {...props}
    />
  )
}

function AlertDescription({
  className,
  ...props
}: React.ComponentProps<"div">) {
  return (
    <div
      data-slot="alert-description"
      className={cn("flex flex-col gap-3", className)}
      {...props}
    />
  )
}

export { Alert, AlertTitle, AlertDescription }
