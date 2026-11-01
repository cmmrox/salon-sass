/**
 * The prototype's sheet (CR-017, web.md "Styling" rule 11): a bottom sheet on phones capped in height, and from 900px
 * a full-height right drawer, as the booking rules' history uses it.
 */
export const SHEET_CLASSES = [
  'mx-auto max-h-(--size-sheet-max-height) w-full max-w-(--size-customer) gap-4 overflow-y-auto rounded-(--radius-sheet) p-6 pb-[calc(var(--spacing)*6+env(safe-area-inset-bottom,0px))] outline-none',
  'before:mx-auto before:mb-2 before:block before:h-1 before:w-(--size-sheet-handle) before:rounded-action before:bg-border before:content-[""]',
  'rail:top-0 rail:left-auto rail:m-0 rail:h-full rail:max-h-none rail:w-(--size-drawer) rail:max-w-full rail:rounded-none rail:px-8 rail:py-6 rail:before:hidden',
];
