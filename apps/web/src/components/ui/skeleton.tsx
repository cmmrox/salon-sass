import { cn } from "@/lib/utils"

// One line of a loading skeleton, in the platform's neutral colour, never a salon's. The
// prototype's skeleton is still, so there is no pulse.
function Skeleton({ className, ...props }: React.ComponentProps<"div">) {
  return (
    <div
      data-slot="skeleton"
      className={cn("block h-skeleton-line rounded-field bg-muted", className)}
      {...props}
    />
  )
}

export { Skeleton }
