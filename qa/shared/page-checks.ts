/**
 * Questions a browser test asks of whatever page is open: what a person can read on it, whether
 * it fits its width, what can be pressed, and what an automated accessibility scan finds.
 */
import AxeBuilder from '@axe-core/playwright';
import type { Locator, Page } from '@playwright/test';

/** The two widths the stage's spec asks for (its screen is scaffolding); the full matrix is in docs/design/ui-ux-standards.md. */
export const WIDTHS = [
  { name: 'a small Android phone', width: 360, height: 740 },
  { name: 'a desktop', width: 1280, height: 800 },
] as const;

/**
 * Every string a person sees or hears on the page: the text of each rendered text node (text that
 * is hidden from sight but read by a screen reader counts), the document's title, and the
 * attributes a browser or a screen reader speaks.
 */
export async function stringsShown(page: Page): Promise<string[]> {
  return page.evaluate(() => {
    const found: string[] = [];
    const walker = document.createTreeWalker(document.body, NodeFilter.SHOW_TEXT);
    while (walker.nextNode()) {
      const parent = walker.currentNode.parentElement;
      if (!parent || parent.closest('script, style, noscript, template, [hidden]')) {
        continue;
      }
      const style = getComputedStyle(parent);
      if (style.display === 'none' || style.visibility === 'hidden') {
        continue;
      }
      const text = (walker.currentNode.textContent ?? '').replace(/\s+/g, ' ').trim();
      if (text) {
        found.push(text);
      }
    }
    for (const name of ['aria-label', 'aria-description', 'aria-placeholder', 'aria-roledescription', 'aria-valuetext', 'alt', 'title', 'placeholder']) {
      for (const element of Array.from(document.querySelectorAll(`[${name}]`))) {
        const value = (element.getAttribute(name) ?? '').trim();
        if (value && !element.closest('script, style, template')) {
          found.push(value);
        }
      }
    }
    if (document.title.trim()) {
      found.push(document.title.trim());
    }
    return found;
  });
}

export type Overflow = { pageScrolls: boolean; outside: string[]; clipped: string[]; counted: number };

/**
 * How the page fits the viewport: whether it scrolls sideways, which elements a test can name
 * (those with a test id) reach outside it, and which of them hold more text than they show.
 */
export async function fit(page: Page): Promise<Overflow> {
  return page.evaluate(() => {
    const viewport = document.documentElement.clientWidth;
    const outside: string[] = [];
    const clipped: string[] = [];
    const elements = Array.from(document.querySelectorAll<HTMLElement>('[data-testid]')).filter(
      (element) => element.getClientRects().length > 0 && !element.closest('[hidden]'),
    );
    for (const element of elements) {
      const box = element.getBoundingClientRect();
      const id = element.getAttribute('data-testid') ?? '?';
      if (box.width === 0 || box.height === 0) {
        continue;
      }
      if (box.left < -0.5 || box.right > viewport + 0.5) {
        outside.push(`${id} (${Math.round(box.left)} to ${Math.round(box.right)} of ${viewport})`);
      }
      if (element.scrollWidth > element.clientWidth + 1) {
        clipped.push(`${id} (holds ${element.scrollWidth}, shows ${element.clientWidth})`);
      }
    }
    return {
      pageScrolls: document.documentElement.scrollWidth > viewport || document.body.scrollWidth > viewport,
      outside,
      clipped,
      counted: elements.length,
    };
  });
}

export type Pressable = { tag: string; testId: string | null; text: string };

/** Everything on the page a person can press or type into, with its test id if it has one. */
export async function pressables(page: Page): Promise<Pressable[]> {
  return page.evaluate(() =>
    Array.from(
      document.querySelectorAll<HTMLElement>(
        'a[href], button, input, select, textarea, summary, [role="button"], [role="link"], [role="checkbox"], [role="tab"], [role="menuitem"], [onclick], [tabindex]:not([tabindex="-1"])',
      ),
    )
      .filter((element) => element.getClientRects().length > 0 && !element.closest('[hidden]'))
      .map((element) => ({
        tag: element.tagName.toLowerCase(),
        testId: element.getAttribute('data-testid'),
        text: (element.textContent ?? '').trim().slice(0, 60),
      })),
  );
}

/** The test ids on the page, in order, including any that appear more than once. */
export async function testIds(page: Page): Promise<string[]> {
  return page.evaluate(() =>
    Array.from(document.querySelectorAll('[data-testid]'))
      .filter((element) => element.getClientRects().length > 0 && !element.closest('[hidden]'))
      .map((element) => element.getAttribute('data-testid') ?? ''),
  );
}

/** The serious and critical violations of WCAG 2.2 level AA an automated scan finds, one line each. */
export async function seriousViolations(page: Page): Promise<string[]> {
  const results = await new AxeBuilder({ page })
    .withTags(['wcag2a', 'wcag2aa', 'wcag21a', 'wcag21aa', 'wcag22aa', 'best-practice'])
    .analyze();
  if (results.passes.length === 0) {
    throw new Error('the accessibility scan checked nothing on this page');
  }
  return results.violations
    .filter((violation) => violation.impact === 'serious' || violation.impact === 'critical')
    .map((violation) => `${violation.impact}: ${violation.id} (${violation.help}) on ${violation.nodes.map((node) => node.target.join(' ')).join(', ')}`);
}

/** How the focused element shows that it has the focus: its outline, as the browser draws it. */
export async function focusIndicator(locator: Locator): Promise<{ focused: boolean; focusVisible: boolean; outlineWidth: number; outlineStyle: string }> {
  return locator.evaluate((element) => {
    const style = getComputedStyle(element);
    return {
      focused: document.activeElement === element,
      focusVisible: element.matches(':focus-visible'),
      outlineWidth: Number.parseFloat(style.outlineWidth) || 0,
      outlineStyle: style.outlineStyle,
    };
  });
}

/** A test id is named for what the element is, in kebab-case: `<area>-<thing>` (docs/design/ui-ux-standards.md). */
export const TEST_ID_NAME = /^[a-z][a-z0-9]*(?:-[a-z0-9]+)+$/;
