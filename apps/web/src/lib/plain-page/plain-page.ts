import messages from '../../../messages/en.json';
import { defaultLocale } from '../../i18n/config';
import baseStyles from '../../styles/base.css?raw';
import tokenStyles from '../../styles/tokens.css?raw';

import layoutStyles from './plain-page.css?raw';

/**
 * The answers on a salon's hostname that say there is no salon to show: "salon not found" (404),
 * "temporarily unavailable" (503) and "service unavailable" (503). They are complete HTML, written
 * here and answered by `proxy.ts` with their status, because the framework gives a page it renders
 * after a rewrite the status 200 (decision 028).
 *
 * They look as the application's own pages do: the same strings (messages/en.json), the same tokens
 * and base rules inlined from their one source (styles/tokens.css, styles/base.css), and the
 * layout of the components whose markup they repeat (plain-page.css, in tokens only). They need no
 * script, and show nothing of any salon, in the platform's neutral theme.
 */
export type PlainPage = 'salon-not-found' | 'salon-unavailable' | 'service-unavailable';

/** The style sheets inlined, in order. Each class name is defined by one of them only. */
export const PLAIN_PAGE_STYLE_SHEETS = [tokenStyles, baseStyles, layoutStyles];

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
    '<div class="shell">',
    `<header class="header"><p class="name" data-testid="app-name">${escape(messages.app.name)}</p></header>`,
    `<main class="main">${content}</main>`,
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
          '<section class="panel" aria-labelledby="status-heading" data-testid="status-panel">' +
          `<h1 id="status-heading" class="heading" data-testid="status-heading">${escape(status.heading)}</h1>` +
          '<div class="unavailable" role="alert" data-testid="status-unavailable">' +
          `<h2 class="stateTitle" data-testid="status-unavailable-title">${escape(status.unavailable.title)}</h2>` +
          `<p data-testid="status-unavailable-body">${escape(status.unavailable.body)}</p>` +
          // Try again asks for the same address, which resolves the salon again.
          `<a class="action" href="/" data-testid="status-retry">${escape(status.unavailable.retry)}</a>` +
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
      `<div class="notice" data-testid="${testId}">` +
      `<h1 class="title" data-testid="${testId}-title">${escape(text.title)}</h1>` +
      `<p data-testid="${testId}-body">${escape(text.body)}</p>` +
      '</div>',
  };
}

/** The style sheets as one, with what only the bundler understands taken out. */
function styles(): string {
  return PLAIN_PAGE_STYLE_SHEETS.map((sheet) =>
    sheet
      .replace(/\/\*[\s\S]*?\*\//g, ''),
  )
    .join('\n')
    .replace(/<\//g, '<\\/');
}

function escape(text: string): string {
  return text.replace(/[&<>"']/g, (character) => `&#${character.charCodeAt(0)};`);
}
