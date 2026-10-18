"use client"

import * as React from "react"
import { cva, type VariantProps } from "class-variance-authority"
import { cn } from "@/lib/utils"

import { Button } from "@/components/ui/button"
import { Input } from "@/components/ui/input"
import { Textarea } from "@/components/ui/textarea"

// The prototype's field with a control drawn on it (the password visibility toggle): the group is the
// white field with its line ring, the brand ring while its input is focused and the negative ring
// when refused; the input inside is transparent. `login` is the staff sign-in's field (CR-012).

function InputGroup({ className, size = "default", ...props }: React.ComponentProps<"div"> & { size?: "default" | "login" }) {
  return (
    <div
      data-slot="input-group"
      data-size={size}
      role="group"
      className={cn(
        "group/input-group relative flex w-full items-center bg-card shadow-field",
        "min-h-field min-w-0 rounded-field has-[>textarea]:h-auto data-[size=login]:min-h-(--size-login-field) data-[size=login]:rounded-(--radius-login-field)",

        // Variants based on alignment.
        "has-[>[data-align=inline-start]]:[&>input]:pl-2",
        "has-[>[data-align=inline-end]]:[&>input]:pr-2",
        "has-[>[data-align=block-start]]:h-auto has-[>[data-align=block-start]]:flex-col has-[>[data-align=block-start]]:[&>input]:pb-3",
        "has-[>[data-align=block-end]]:h-auto has-[>[data-align=block-end]]:flex-col has-[>[data-align=block-end]]:[&>input]:pt-3",

        // Focus state.
        "has-[[data-slot=input-group-control]:focus]:shadow-field-focus",

        // Error state.
        "has-[[data-slot][aria-invalid=true]]:shadow-field-invalid",

        className
      )}
      {...props}
    />
  )
}

const inputGroupAddonVariants = cva(
  "flex h-auto cursor-text items-center justify-center gap-2 py-1.5 text-small font-medium text-muted-foreground select-none group-data-[disabled=true]/input-group:opacity-(--opacity-disabled) [&>svg:not([class*='size-'])]:size-icon",
  {
    variants: {
      align: {
        "inline-start":
          "order-first pl-3.5 has-[>button]:py-0 has-[>button]:pl-0",
        "inline-end":
          "order-last pr-3.5 has-[>button]:py-0 has-[>button]:pr-0",
        "block-start":
          "order-first w-full justify-start px-3 pt-3 group-has-[>input]/input-group:pt-2.5 [.border-b]:pb-3",
        "block-end":
          "order-last w-full justify-start px-3 pb-3 group-has-[>input]/input-group:pb-2.5 [.border-t]:pt-3",
      },
    },
    defaultVariants: {
      align: "inline-start",
    },
  }
)

function InputGroupAddon({
  className,
  align = "inline-start",
  ...props
}: React.ComponentProps<"div"> & VariantProps<typeof inputGroupAddonVariants>) {
  return (
    <div
      role="group"
      data-slot="input-group-addon"
      data-align={align}
      className={cn(inputGroupAddonVariants({ align }), className)}
      onClick={(e) => {
        if ((e.target as HTMLElement).closest("button")) {
          return
        }
        e.currentTarget.parentElement?.querySelector("input")?.focus()
      }}
      {...props}
    />
  )
}

const inputGroupButtonVariants = cva(
  "flex items-center gap-2 text-small shadow-none",
  {
    variants: {
      size: {
        xs: "min-h-touch gap-1 rounded-field px-3",
        sm: "min-h-touch gap-1.5 rounded-field px-3",
        // A 44px icon control filling the field's height at its end (the visibility toggle).
        "icon-xs": "min-h-touch min-w-touch self-stretch rounded-field p-0",
        "icon-sm": "min-h-touch min-w-touch self-stretch rounded-field p-0",
      },
    },
    defaultVariants: {
      size: "xs",
    },
  }
)

function InputGroupButton({
  className,
  type = "button",
  variant = "ghost",
  size = "xs",
  ...props
}: Omit<React.ComponentProps<typeof Button>, "size"> &
  VariantProps<typeof inputGroupButtonVariants>) {
  return (
    <Button
      type={type}
      data-size={size}
      variant={variant}
      className={cn(inputGroupButtonVariants({ size }), className)}
      {...props}
    />
  )
}

function InputGroupText({ className, ...props }: React.ComponentProps<"span">) {
  return (
    <span
      className={cn(
        "flex items-center gap-2 text-small text-muted-foreground [&_svg]:pointer-events-none [&_svg:not([class*='size-'])]:size-icon",
        className
      )}
      {...props}
    />
  )
}

function InputGroupInput({
  className,
  ...props
}: React.ComponentProps<typeof Input>) {
  return (
    <Input
      data-slot="input-group-control"
      className={cn(
        "flex-1 rounded-none border-0 bg-transparent shadow-none focus-visible:shadow-none aria-invalid:shadow-none",
        className
      )}
      {...props}
    />
  )
}

function InputGroupTextarea({
  className,
  ...props
}: React.ComponentProps<"textarea">) {
  return (
    <Textarea
      data-slot="input-group-control"
      className={cn(
        "flex-1 resize-none rounded-none border-0 bg-transparent py-3 shadow-none focus-visible:shadow-none",
        className
      )}
      {...props}
    />
  )
}

export {
  InputGroup,
  InputGroupAddon,
  InputGroupButton,
  InputGroupText,
  InputGroupInput,
  InputGroupTextarea,
}
