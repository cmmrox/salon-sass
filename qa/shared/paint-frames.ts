/**
 * Records what the browser is about to paint on every rendering update of every document a context
 * loads, from the first frame on, and the main-frame document responses. CR-013 AC-11: entering a
 * salon's web app paints no intermediate generic frame ("Salon" name, "Loading your account…"
 * card or skeleton) before the screen the visitor reaches.
 *
 * A sample is taken in a requestAnimationFrame callback, which runs in the rendering step just
 * before each paint, so a sample is what that frame shows. Only visible elements count. Samples are
 * sent only when they differ from the previous one.
 */
import type { BrowserContext, Page, Response } from '@playwright/test';

export type PaintSample = {
  url: string; at: number;
  /** A visible `account-loading` element. */
  loading: boolean;
  /** Visible text equal to the product's account loading message. */
  loadingText: boolean;
  /** Visible workspace frame (shell, header or navigation). */
  workspace: boolean;
  /** Visible staff sign-in screen. */
  signIn: boolean;
  /** Visible salon identity texts (header name, sign-in name). */
  names: string[];
  /** Length of the visible body text: zero is a blank frame. */
  content: number;
};
export type DocumentAnswer = { url: string; status: number };

export class PaintLog {
  readonly samples: PaintSample[] = [];
  readonly documents: DocumentAnswer[] = [];
  clear() { this.samples.length = 0; this.documents.length = 0; }
}

let installed = 0;
/** Installs the recorder on a context before any of its pages opens. */
export async function recordPaints(context: BrowserContext, loadingMessage = ''): Promise<PaintLog> {
  const log = new PaintLog(), binding = `__qaPaint${++installed}`;
  await context.exposeBinding(binding, (_source, sample: PaintSample) => { log.samples.push(sample); });
  await context.addInitScript(({ binding, loadingMessage }) => {
    const send = (window as unknown as Record<string, (sample: unknown) => void>)[binding];
    const shown = (el: Element) => (el as HTMLElement).checkVisibility?.({ opacityProperty: true, visibilityProperty: true }) ?? true;
    const all = (selector: string) => Array.from(document.querySelectorAll(selector)).filter(shown);
    let previous = '';
    const sample = () => {
      const text = document.body ? document.body.innerText : '';
      const value = {
        url: location.href, at: performance.now(),
        loading: all('[data-testid="account-loading"]').length > 0,
        loadingText: loadingMessage !== '' && text.includes(loadingMessage),
        workspace: all('[data-testid="workspace-shell"],[data-testid="workspace-header"],[data-testid="workspace-navigation"]').length > 0,
        signIn: all('[data-testid="staff-sign-in-screen"]').length > 0,
        names: all('[data-testid="account-home"],[data-testid="salon-name"],[data-testid="staff-sign-in-name"]').map(el => (el.textContent ?? '').trim()),
        content: text.trim().length,
      };
      const key = JSON.stringify({ ...value, at: 0 });
      if (key !== previous) { previous = key; try { send(value); } catch { /* document is going away */ } }
    };
    const tick = () => { sample(); requestAnimationFrame(tick); };
    requestAnimationFrame(tick);
  }, { binding, loadingMessage });
  context.on('response', (response: Response) => {
    const request = response.request();
    if (request.isNavigationRequest() && response.frame().parentFrame() === null) log.documents.push({ url: response.url(), status: response.status() });
  });
  return log;
}

/** Waits until two more frames of the current document have been sampled and delivered. */
export async function settlePaints(page: Page) {
  await page.evaluate(() => new Promise<void>(resolve => requestAnimationFrame(() => requestAnimationFrame(() => resolve()))));
}

/**
 * What an entry painted that AC-11 forbids. `signedIn` false: nothing but the sign-in screen with
 * the salon's own name. `signedIn` true: the first non-blank frame is the workspace with the
 * salon's name, and a loading state only ever appears inside that identified workspace.
 */
export function entryViolations(log: PaintLog, salonName: string, signedIn: boolean, finalPath: string): string[] {
  const found: string[] = [], where = (s: PaintSample) => `${new URL(s.url).pathname}@${Math.round(s.at)}ms`;
  const pages = log.documents.filter(d => d.status < 300 || d.status >= 400);
  if (pages.length !== 1) found.push(`expected exactly one rendered document, got ${JSON.stringify(log.documents)}`);
  else if (new URL(pages[0].url).pathname !== finalPath || pages[0].status !== 200) found.push(`rendered document ${pages[0].url} ${pages[0].status}, expected 200 ${finalPath}`);
  for (const d of log.documents.slice(0, -1)) if (d.status < 300 || d.status >= 400) found.push(`intermediate document ${d.url} answered ${d.status}, not a server redirect`);
  const painted = log.samples.filter(s => s.content > 0);
  if (!painted.length) found.push('no non-blank frame was recorded');
  for (const s of log.samples) {
    if (new URL(s.url).pathname !== finalPath && s.content > 0) found.push(`painted ${where(s)} before reaching ${finalPath}`);
    for (const name of s.names) if (name !== salonName) found.push(`generic or foreign salon name "${name}" at ${where(s)}`);
    if (!signedIn) {
      if (s.loading || s.loadingText) found.push(`account loading state at ${where(s)}`);
      if (s.workspace) found.push(`workspace frame before sign-in at ${where(s)}`);
    } else {
      if (s.signIn) found.push(`sign-in screen painted for a signed-in member at ${where(s)}`);
      if ((s.loading || s.loadingText) && !(s.workspace && s.names.includes(salonName))) found.push(`loading state without the salon's workspace identity at ${where(s)}`);
    }
  }
  const first = painted[0];
  if (first) {
    if (signedIn && !(first.workspace && first.names.includes(salonName))) found.push(`first painted frame ${where(first)} is not the workspace with "${salonName}"`);
    if (!signedIn && !(first.signIn && first.names.includes(salonName))) found.push(`first painted frame ${where(first)} is not the sign-in with "${salonName}"`);
  }
  return found;
}
