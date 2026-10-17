import * as React from "react"
import { cn } from "@/lib/utils"
import { inputVariants } from "@/components/ui/input"

// A native select drawn as the prototype's field, keeping the platform's own picker and arrow
// (the CLI's chevron overlay is not used).
function NativeSelect({
  className,
  ...props
}: Omit<React.ComponentProps<"select">, "size">) {
  return (
    <div
      className="group/native-select relative w-full min-w-0"
      data-slot="native-select-wrapper"
    >
      <select
        data-slot="native-select"
        className={cn(inputVariants(), "block", className)}
        {...props}
      />
    </div>
  )
}

function NativeSelectOption({
  className,
  ...props
}: React.ComponentProps<"option">) {
  return (
    <option
      data-slot="native-select-option"
      className={className}
      {...props}
    />
  )
}

function NativeSelectOptGroup({
  className,
  ...props
}: React.ComponentProps<"optgroup">) {
  return (
    <optgroup
      data-slot="native-select-optgroup"
      className={className}
      {...props}
    />
  )
}

export { NativeSelect, NativeSelectOptGroup, NativeSelectOption }
