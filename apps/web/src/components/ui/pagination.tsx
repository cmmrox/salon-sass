import * as React from "react"
import { cn } from "@/lib/utils"
import { ChevronLeftIcon, ChevronRightIcon, MoreHorizontalIcon } from "lucide-react"

import { Button } from "@/components/ui/button"

// Paging through a list. Adapted from the CLI's component: the workspace pages its lists in place,
// so a page control is a Button (a <button>, or the element given with `asChild`, such as a link)
// rather than always a link; the spoken and visible words come from the caller (the translation
// files) instead of the CLI's English; the controls are the prototype's ink-outlined pills, the
// current page a plain marker with aria-current.
function Pagination({ className, ...props }: React.ComponentProps<"nav">) {
  return (
    <nav
      data-slot="pagination"
      className={cn("flex w-full min-w-0", className)}
      {...props}
    />
  )
}

function PaginationContent({
  className,
  ...props
}: React.ComponentProps<"ul">) {
  return (
    <ul
      data-slot="pagination-content"
      className={cn("flex min-w-0 flex-row flex-wrap items-center gap-2", className)}
      {...props}
    />
  )
}

function PaginationItem({ ...props }: React.ComponentProps<"li">) {
  return <li data-slot="pagination-item" {...props} />
}

type PaginationLinkProps = {
  isActive?: boolean
} & React.ComponentProps<typeof Button>

function PaginationLink({
  className,
  isActive,
  variant = "outline",
  size = "sm",
  ...props
}: PaginationLinkProps) {
  return (
    <Button
      aria-current={isActive ? "page" : undefined}
      data-slot="pagination-link"
      data-active={isActive}
      variant={variant}
      size={size}
      className={className}
      {...props}
    />
  )
}

function PaginationPrevious({
  className,
  children,
  ...props
}: React.ComponentProps<typeof PaginationLink>) {
  return (
    <PaginationLink data-slot="pagination-previous" className={cn("gap-1 pl-3", className)} {...props}>
      <ChevronLeftIcon aria-hidden="true" />
      <span>{children}</span>
    </PaginationLink>
  )
}

function PaginationNext({
  className,
  children,
  ...props
}: React.ComponentProps<typeof PaginationLink>) {
  return (
    <PaginationLink data-slot="pagination-next" className={cn("gap-1 pr-3", className)} {...props}>
      <span>{children}</span>
      <ChevronRightIcon aria-hidden="true" />
    </PaginationLink>
  )
}

/** The page being shown, as text: `aria-current="page"`, not a control. */
function PaginationCurrent({ className, ...props }: React.ComponentProps<"span">) {
  return (
    <span
      aria-current="page"
      data-slot="pagination-current"
      className={cn("inline-flex min-h-touch items-center px-2 font-medium", className)}
      {...props}
    />
  )
}

/** More pages exist here; give `label` from the translation files. */
function PaginationEllipsis({
  className,
  label,
  ...props
}: React.ComponentProps<"span"> & { label: string }) {
  return (
    <span
      data-slot="pagination-ellipsis"
      className={cn("flex size-touch items-center justify-center", className)}
      {...props}
    >
      <MoreHorizontalIcon className="size-4" aria-hidden="true" />
      <span className="sr-only">{label}</span>
    </span>
  )
}

export {
  Pagination,
  PaginationContent,
  PaginationLink,
  PaginationItem,
  PaginationPrevious,
  PaginationNext,
  PaginationCurrent,
  PaginationEllipsis,
}
