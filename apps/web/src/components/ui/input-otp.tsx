"use client"

import * as React from "react"
import { cn } from "@/lib/utils"
import { OTPInput, OTPInputContext } from "input-otp"
import { MinusIcon } from "lucide-react"
import { cva, type VariantProps } from "class-variance-authority"

// The authenticator code as six separate prototype fields. The library draws one transparent input
// over the slots; the slot it is typing into takes the brand ring, and while the input has focus
// that slot also carries the theme's keyboard outline (the input itself has none). `login` is the
// staff sign-in's field size (CR-012). The library's <noscript> style fallback is not rendered:
// the code is only entered with JavaScript.

function InputOTP({
  className,
  containerClassName,
  ...props
}: React.ComponentProps<typeof OTPInput> & {
  containerClassName?: string
}) {
  return (
    <OTPInput
      data-slot="input-otp"
      noScriptCSSFallback={null}
      containerClassName={cn(
        "flex w-full min-w-0 items-center gap-2 has-disabled:opacity-(--opacity-disabled)",
        containerClassName
      )}
      className={cn("disabled:cursor-not-allowed", className)}
      {...props}
    />
  )
}

function InputOTPGroup({ className, ...props }: React.ComponentProps<"div">) {
  return (
    <div
      data-slot="input-otp-group"
      className={cn("flex w-full min-w-0 items-center gap-2", className)}
      {...props}
    />
  )
}

const inputOTPSlotVariants = cva(
  "relative flex min-w-0 flex-1 items-center justify-center bg-card text-field font-medium text-foreground shadow-field aria-invalid:shadow-field-invalid data-[active=true]:z-10 data-[active=true]:shadow-field-focus data-[active=true]:outline-(length:--size-focus-ring) data-[active=true]:outline-offset-(--size-focus-offset) data-[active=true]:outline-ring data-[active=true]:outline-solid",
  {
    variants: {
      size: {
        default: "h-field max-w-14 rounded-field",
        login: "h-(--size-login-field) max-w-16 rounded-(--radius-login-field)",
      },
    },
    defaultVariants: { size: "default" },
  }
)

function InputOTPSlot({
  index,
  size,
  className,
  ...props
}: React.ComponentProps<"div"> & VariantProps<typeof inputOTPSlotVariants> & {
  index: number
}) {
  const inputOTPContext = React.useContext(OTPInputContext)
  const { char, hasFakeCaret, isActive } = inputOTPContext?.slots[index] ?? {}

  return (
    <div
      data-slot="input-otp-slot"
      data-active={isActive}
      className={cn(inputOTPSlotVariants({ size }), className)}
      {...props}
    >
      {char}
      {hasFakeCaret && (
        <div className="pointer-events-none absolute inset-0 flex items-center justify-center">
          <div className="h-4 w-px animate-caret-blink bg-foreground duration-1000" />
        </div>
      )}
    </div>
  )
}

function InputOTPSeparator({ ...props }: React.ComponentProps<"div">) {
  return (
    <div data-slot="input-otp-separator" role="separator" {...props}>
      <MinusIcon aria-hidden="true" />
    </div>
  )
}

export { InputOTP, InputOTPGroup, InputOTPSlot, InputOTPSeparator }
