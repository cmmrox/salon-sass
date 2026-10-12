import { readFileSync } from 'node:fs';
import { join } from 'node:path';

import { describe, expect, it } from 'vitest';

import messages from '../../../messages/en.json';

import { PLAIN_PAGE_STYLE_SHEETS, plainPageHtml, plainPageStatus, type PlainPage } from './plain-page';

const PAGES: PlainPage[] = ['salon-not-found', 'salon-unavailable', 'service-unavailable'];

function parse(page: PlainPage): Document {
  return new DOMParser().parseFromString(plainPageHtml(page), 'text/html');
}

function byTestId(document: Document, id: string): Element {
  const element = document.querySelector(`[data-testid="${id}"]`);
  expect(element, id).not.toBeNull();
  return element!;
}

/** Every string a person sees in the page, in document order. */
function visibleTexts(document: Document): string[] {
  const walker = document.createTreeWalker(document.body, NodeFilter.SHOW_TEXT);
  const texts: string[] = [];
  for (let node = walker.nextNode(); node; node = walker.nextNode()) {
    if (node.textContent?.trim()) {
      texts.push(node.textContent.trim());
    }
  }
  return [document.title, ...texts];
}

function allMessages(value: unknown): string[] {
  return typeof value === 'string' ? [value] : Object.values(value as object).flatMap(allMessages);
}

describe('the plain pages', () => {
  it('answer with their status: 404 for no salon, 503 for a suspended salon and for an unreachable service', () => {
    expect(plainPageStatus('salon-not-found')).toBe(404);
    expect(plainPageStatus('salon-unavailable')).toBe(503);
    expect(plainPageStatus('service-unavailable')).toBe(503);
  });

  it('"salon not found": its title, heading and sentence, with their test ids', () => {
    const document = parse('salon-not-found');

    expect(document.title).toBe(messages.salon.notFound.title);
    expect(byTestId(document, 'salon-not-found-title').tagName).toBe('H1');
    expect(byTestId(document, 'salon-not-found-title').textContent).toBe(messages.salon.notFound.title);
    expect(byTestId(document, 'salon-not-found-body').textContent).toBe(messages.salon.notFound.body);
    expect(byTestId(document, 'salon-not-found').contains(byTestId(document, 'salon-not-found-body'))).toBe(true);
  });

  it('"temporarily unavailable": its title, heading and sentence, with their test ids', () => {
    const document = parse('salon-unavailable');

    expect(document.title).toBe(messages.salon.unavailable.title);
    expect(byTestId(document, 'salon-unavailable-title').tagName).toBe('H1');
    expect(byTestId(document, 'salon-unavailable-title').textContent).toBe(messages.salon.unavailable.title);
    expect(byTestId(document, 'salon-unavailable-body').textContent).toBe(messages.salon.unavailable.body);
  });

  it('"service unavailable": the status page\'s state, with its test ids and a retry of the same address', () => {
    const document = parse('service-unavailable');

    expect(document.title).toBe(messages.status.unavailable.title);
    expect(byTestId(document, 'status-heading').textContent).toBe(messages.status.heading);
    expect(byTestId(document, 'status-unavailable').getAttribute('role')).toBe('alert');
    expect(byTestId(document, 'status-unavailable-title').textContent).toBe(messages.status.unavailable.title);
    expect(byTestId(document, 'status-unavailable-body').textContent).toBe(messages.status.unavailable.body);
    expect(byTestId(document, 'status-retry').getAttribute('href')).toBe('/');
    expect(byTestId(document, 'status-retry').textContent).toBe(messages.status.unavailable.retry);
    expect(document.querySelector('[data-testid="salon-not-found"]')).toBeNull();
  });

  it.each(PAGES)('%s: a complete document in the app\'s language, with a viewport, that needs no script', (page) => {
    const html = plainPageHtml(page);
    const document = parse(page);

    expect(html.startsWith('<!DOCTYPE html>')).toBe(true);
    expect(document.documentElement.lang).toBe('en');
    expect(document.querySelector('meta[name="viewport"]')?.getAttribute('content')).toBe('width=device-width, initial-scale=1');
    expect(document.querySelectorAll('script')).toHaveLength(0);
    expect(html).not.toMatch(/<script|\son[a-z]+=/i);
    expect(byTestId(document, 'app-name').textContent).toBe(messages.app.name);
    expect(document.querySelectorAll('h1')).toHaveLength(1);
    expect(document.querySelector('main')).not.toBeNull();
  });

  it.each(PAGES)('%s: every string it shows comes from the translation file', (page) => {
    const known = new Set(allMessages(messages));

    for (const text of visibleTexts(parse(page))) {
      expect(known, text).toContain(text);
    }
  });

  it.each(PAGES)('%s: nothing of any salon, and no brand colour', (page) => {
    const html = plainPageHtml(page);

    for (const leak of ['Alpha Studio', 'Beta Hair', 'Gamma', '#D6086A', '#1F2937', '#FDE68A', '--color-brand']) {
      expect(html, leak).not.toContain(leak);
    }
  });

  it('the not-found page is the same whatever the hostname was: it takes no input', () => {
    expect(plainPageHtml('salon-not-found')).toBe(plainPageHtml('salon-not-found'));
  });
});

describe('the plain pages\' styles', () => {
  it('inline the neutral theme from its one source, tokens.css', () => {
    const tokens = readFileSync(join(__dirname, '..', '..', 'styles', 'tokens.css'), 'utf8');
    const declarations = tokens.match(/--[a-z0-9-]+:\s*[^;]+;/g) ?? [];
    const html = plainPageHtml('salon-not-found');

    expect(declarations.length).toBeGreaterThan(10);
    for (const declaration of declarations) {
      expect(html, declaration).toContain(declaration);
    }
  });

  it('lay the pages out in tokens only: no colour, size or space of their own', () => {
    const layout = PLAIN_PAGE_STYLE_SHEETS[2]!.replace(/\/\*[\s\S]*?\*\//g, '');

    expect(layout).toMatch(/var\(--/);
    expect(layout).not.toMatch(/#[0-9a-f]{3,8}\b/i);
    expect(layout).not.toMatch(/\b\d+(\.\d+)?(px|rem|em)\b/);
    expect(layout).not.toMatch(/rgb|hsl/i);
  });

  it('carry no bundler syntax and cannot close the style element', () => {
    const style = parse('salon-not-found').querySelector('style')?.textContent ?? '';

    expect(style).not.toContain('composes');
    expect(style).not.toContain('@import');
    expect(style).not.toContain('</');
  });

  it('define each class name in one style sheet only, so the inlined sheets cannot override each other', () => {
    const owner = new Map<string, number>();
    PLAIN_PAGE_STYLE_SHEETS.forEach((sheet, index) => {
      const withoutComments = sheet.replace(/\/\*[\s\S]*?\*\//g, '');
      for (const [, name] of withoutComments.matchAll(/\.([a-zA-Z][\w-]*)(?=[^{}]*\{)/g)) {
        expect(owner.get(name!) ?? index, `.${name} is defined in more than one sheet`).toBe(index);
        owner.set(name!, index);
      }
    });
  });

  it('use every class name the markup does', () => {
    const style = parse('service-unavailable').querySelector('style')?.textContent ?? '';
    for (const page of PAGES) {
      for (const element of parse(page).querySelectorAll('[class]')) {
        for (const name of element.classList) {
          expect(style, `.${name}`).toMatch(new RegExp(`\\.${name}\\b`));
        }
      }
    }
  });
});
