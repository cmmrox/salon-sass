import { clsx, type ClassValue } from 'clsx';
import { extendTailwindMerge } from 'tailwind-merge';

/**
 * tailwind-merge told the theme's own Tailwind names (app/globals.css, `@theme inline`), so that
 * `text-input` is read as a font size and not a colour, and `rounded-card` and `rounded-field`
 * replace each other. Keep this list in step with globals.css.
 */
const twMerge = extendTailwindMerge({
  extend: {
    theme: {
      text: ['small', 'body', 'large', 'title', 'workspace-title', 'label', 'field', 'rail', 'login-title', 'login-welcome', 'login-name', 'login-initial', 'login-help', 'login-label', 'login-action'],
      leading: ['body', 'heading'],
      radius: ['field', 'card', 'action', 'rail-item', 'checkbox'],
      shadow: ['popover', 'field', 'field-focus', 'field-invalid'],
      spacing: ['header', 'rail-row', 'icon', 'touch', 'action', 'field', 'skeleton-line'],
      container: ['content', 'workspace'],
      breakpoint: ['rail', 'wide', 'login'],
    },
  },
});

/** Joins class names, letting a later Tailwind utility replace an earlier one of the same kind. */
export function cn(...inputs: ClassValue[]) {
  return twMerge(clsx(inputs));
}
