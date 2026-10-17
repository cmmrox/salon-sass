import { cn } from "@/lib/utils"
import { Loader2Icon } from "lucide-react"

// A busy indicator. Decorative by default, beside the words that say what is happening (a busy
// action keeps its translated text); give it `label` from the translation files to announce it alone.
function Spinner({ className, label, ...props }: React.ComponentProps<"svg"> & { label?: string }) {
  return (
    <Loader2Icon
      data-slot="spinner"
      role={label ? "status" : undefined}
      aria-label={label}
      aria-hidden={label ? undefined : true}
      className={cn("size-icon animate-spin motion-reduce:animate-none", className)}
      {...props}
    />
  )
}

export { Spinner }
