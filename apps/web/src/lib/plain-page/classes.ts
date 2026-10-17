/**
 * The Tailwind classes of the plain pages (plain-page.ts): exactly the classes the React
 * components they repeat render, as plain-page.test.ts proves by rendering those components.
 * Tailwind generates the pages' CSS from this file alone (scripts/plain-page-css.mjs), so change a
 * class here, then run `pnpm --filter @salon/web plain-page:css` and commit the generated file.
 */
export const PLAIN_PAGE_CLASSES = {
  // components/shell/AppShell.tsx
  shell: 'flex min-h-screen flex-col items-center gap-6 px-4 py-8',
  header: 'w-full max-w-content',
  name: 'text-large leading-heading font-semibold text-foreground wrap-anywhere',
  main: 'w-full max-w-content',
  // components/notice/Notice.tsx: Card with `gap-3`, CardTitle with `wrap-anywhere`
  notice: 'flex min-w-0 flex-col rounded-card bg-card p-6 text-card-foreground gap-3',
  noticeTitle: 'text-title leading-heading font-semibold wrap-anywhere',
  // components/status/Status.tsx: StatusPanel (Card, CardTitle)
  panel: 'flex min-w-0 flex-col gap-4 rounded-card bg-card p-6 text-card-foreground',
  panelTitle: 'text-title leading-heading font-semibold',
  // Status.tsx's "unavailable" state: Alert (caution), AlertTitle, Button (outline, sm)
  alert: 'relative flex w-full min-w-0 flex-col gap-3 rounded-field p-4 text-foreground bg-caution-surface *:data-[slot=alert-title]:text-caution',
  alertTitle: 'text-large leading-heading font-semibold wrap-anywhere',
  retry:
    "inline-flex shrink-0 cursor-pointer items-center justify-center gap-2 rounded-action border whitespace-normal no-underline wrap-anywhere disabled:cursor-not-allowed disabled:opacity-(--opacity-disabled) [&_svg]:pointer-events-none [&_svg]:shrink-0 [&_svg:not([class*='size-'])]:size-icon border-foreground text-foreground min-h-action px-4 py-2 self-start bg-card font-semibold",
} as const;
