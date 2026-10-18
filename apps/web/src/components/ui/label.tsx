"use client"

import * as React from "react"
import { cva, type VariantProps } from "class-variance-authority"
import { cn } from "@/lib/utils"
import { Label as LabelPrimitive } from "radix-ui"

// A field's label, which holds its control and its error below the text. The staff sign-in's
// labels (CR-012) are larger and regular weight.
const labelVariants = cva("flex min-w-0 flex-col gap-2", {
  variants: {
    size: {
      default: "text-label font-medium",
      login: "text-login-label font-normal max-login:text-body",
    },
  },
  defaultVariants: { size: "default" },
})

function Label({
  className,
  size,
  ...props
}: React.ComponentProps<typeof LabelPrimitive.Root> & VariantProps<typeof labelVariants>) {
  return (
    <LabelPrimitive.Root
      data-slot="label"
      className={cn(labelVariants({ size }), className)}
      {...props}
    />
  )
}

export { Label, labelVariants }
