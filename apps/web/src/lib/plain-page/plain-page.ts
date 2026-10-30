import messages from '../../../messages/en.json';
import { defaultLocale } from '../../i18n/config';
import themeStyles from '../../styles/theme.css?raw';

import { PLAIN_PAGE_CLASSES as c } from './classes';
import tailwindStyles from './plain-page.generated.css?raw';

/**
 * The answers on a salon's hostname that say there is no salon to show: "salon not found" (404),
 * "temporarily unavailable" (503) and "service unavailable" (503). They are complete HTML, written
 * here and answered by `proxy.ts` with their status, because the framework gives a page it renders
 * after a rewrite the status 200 (decision 028).
 *
 * They look as the application's own pages do: the same strings (messages/en.json), the markup and
 * Tailwind classes of the shadcn/ui Card and Alert and the components they repeat (classes.ts), the
 * theme inlined from its one source (styles/theme.css), and the CSS Tailwind generates for those
 * classes from the application's theme and base layer (plain-page.generated.css, written by
 * `pnpm --filter @salon/web plain-page:css`, never by hand; docs/engineering/web.md, "Styling",
 * rule 9). They need no script, and show nothing of any salon, in the platform's neutral theme.
 */
export type PlainPage = 'salon-not-found' | 'salon-unavailable' | 'service-unavailable';

/** The style sheets inlined, in order: the theme's variables, then Tailwind's output. */
export const PLAIN_PAGE_STYLE_SHEETS = [themeStyles, tailwindStyles];

const STATUS: Record<PlainPage, number> = {
  'salon-not-found': 404,
  'salon-unavailable': 503,
  'service-unavailable': 503,
};

/** The status each answer has. */
export function plainPageStatus(page: PlainPage): number {
  return STATUS[page];
}

/** The complete HTML of one answer. */
export function plainPageHtml(page: PlainPage): string {
  const { title, content } = body(page);
  return [
    '<!DOCTYPE html>',
    `<html lang="${escape(defaultLocale)}">`,
    '<head>',
    '<meta charset="utf-8">',
    '<meta name="viewport" content="width=device-width, initial-scale=1">',
    '<meta name="robots" content="noindex">',
    `<title>${escape(title)}</title>`,
    '<link rel="icon" href="/icon.svg" type="image/svg+xml">',
    `<style>${styles()}</style>`,
    '</head>',
    '<body>',
    // AppShell: the product's name above the page's content.
    `<div class="${c.shell}">`,
    `<header class="${c.header}"><p class="${c.name}" data-testid="app-name">${escape(messages.app.name)}</p></header>`,
    `<main class="${c.main}">${content}</main>`,
    '</div>',
    '</body>',
    '</html>',
  ].join('');
}

function body(page: PlainPage): { title: string; content: string } {
  switch (page) {
    case 'salon-not-found':
      return notice('salon-not-found', messages.salon.notFound);
    case 'salon-unavailable':
      return notice('salon-unavailable', messages.salon.unavailable);
    case 'service-unavailable': {
      // The status page's "service unavailable" state (components/status/Status.tsx).
      const status = messages.status;
      return {
        title: status.unavailable.title,
        content:
          `<section class="${c.panel}" data-slot="card" aria-labelledby="status-heading" data-testid="status-panel">` +
          `<h1 id="status-heading" class="${c.panelTitle}" data-slot="card-title" data-testid="status-heading">${escape(status.heading)}</h1>` +
          `<div class="${c.alert}" data-slot="alert" role="alert" data-testid="status-unavailable">` +
          `<h2 class="${c.alertTitle}" data-slot="alert-title" data-testid="status-unavailable-title">${escape(status.unavailable.title)}</h2>` +
          `<p data-testid="status-unavailable-body">${escape(status.unavailable.body)}</p>` +
          // Try again asks for the same address, which resolves the salon again.
          `<a class="${c.retry}" data-slot="button" data-variant="outline" data-size="sm" href="/" data-testid="status-retry">${escape(status.unavailable.retry)}</a>` +
          '</div>' +
          '</section>',
      };
    }
  }
}

/** The markup of components/notice/Notice.tsx. */
function notice(testId: string, text: { title: string; body: string }): { title: string; content: string } {
  return {
    title: text.title,
    content:
      `<div class="${c.notice}" data-slot="card" data-testid="${testId}">` +
      `<h1 class="${c.noticeTitle}" data-slot="card-title" data-testid="${testId}-title">${escape(text.title)}</h1>` +
      `<p data-testid="${testId}-body">${escape(text.body)}</p>` +
      '</div>',
  };
}

/** The style sheets as one, without their comments, unable to close the style element. */
function styles(): string {
  return PLAIN_PAGE_STYLE_SHEETS.map((sheet) => sheet.replace(/\/\*[\s\S]*?\*\//g, ''))
    .join('\n')
    .replace(/<\//g, '<\\/');
}

function escape(text: string): string {
  return text.replace(/[&<>"']/g, (character) => `&#${character.charCodeAt(0)};`);
}
