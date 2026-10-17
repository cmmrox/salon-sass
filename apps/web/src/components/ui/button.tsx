import * as React from "react"
import { cva, type VariantProps } from "class-variance-authority"
import { cn } from "@/lib/utils"
import { Slot } from "radix-ui"

// The prototype's pill actions (docs/design/ui-ux-standards.md): at least 50px high, the salon's
// brand as the primary fill, ink outlines for secondary actions. Keyboard focus is the theme's
// outline from the base layer (globals.css), so no ring is drawn here.
const buttonVariants = cva(
  "inline-flex shrink-0 cursor-pointer items-center justify-center gap-2 rounded-action border font-medium whitespace-normal no-underline wrap-anywhere disabled:cursor-not-allowed disabled:opacity-(--opacity-disabled) [&_svg]:pointer-events-none [&_svg]:shrink-0 [&_svg:not([class*='size-'])]:size-icon",
  {
    variants: {
      variant: {
        default: "border-primary bg-primary text-primary-foreground",
        outline: "border-foreground bg-transparent text-foreground",
        destructive: "border-destructive bg-transparent text-destructive",
        // An icon-only control drawn on a field, such as the password visibility toggle.
        ghost: "border-transparent bg-transparent text-muted-foreground",
        // Text in the salon's colour (ink when the brand is too light), underlined in the brand.
        link: "rounded-none border-0 bg-transparent font-normal text-link underline decoration-primary underline-offset-2",
      },
      size: {
        default: "min-h-action px-6 py-3",
        sm: "min-h-action px-4 py-2",
        icon: "p-0",
        link: "min-h-touch px-0 py-2 text-small",
      },
    },
    defaultVariants: {
      variant: "default",
      size: "default",
    },
  }
)

function Button({
  className,
  variant = "default",
  size = "default",
  asChild = false,
  ...props
}: React.ComponentProps<"button"> &
  VariantProps<typeof buttonVariants> & {
    asChild?: boolean
  }) {
  const Comp = asChild ? Slot.Root : "button"

  return (
    <Comp
      data-slot="button"
      data-variant={variant}
      data-size={size}
      className={cn(buttonVariants({ variant, size, className }))}
      {...props}
    />
  )
}

export { Button, buttonVariants }
