import * as React from "react"
import { cva, type VariantProps } from "class-variance-authority"
import { cn } from "@/lib/utils"

// The prototype's fields: a white, 20px-radius field at least 48px high with an inset line, the
// brand's ring when focused (the keyboard outline stays the theme's) and the negative colour when
// refused. The staff sign-in's fields (CR-012) are 54px with 10px corners.
const inputVariants = cva(
  "w-full min-w-0 border-0 bg-card text-field font-normal text-foreground shadow-field focus-visible:shadow-field-focus aria-invalid:shadow-field-invalid disabled:cursor-not-allowed",
  {
    variants: {
      size: {
        default: "min-h-field rounded-field px-3.5 py-2.5",
        login: "min-h-(--size-login-field) rounded-(--radius-login-field) px-3.5 py-2.5",
      },
    },
    defaultVariants: { size: "default" },
  }
)

function Input({
  className,
  type,
  size,
  ...props
}: Omit<React.ComponentProps<"input">, "size"> & VariantProps<typeof inputVariants>) {
  return (
    <input
      type={type}
      data-slot="input"
      className={cn(inputVariants({ size }), className)}
      {...props}
    />
  )
}

export { Input, inputVariants }
