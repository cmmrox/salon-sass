/**
 * The security headers every response carries (web M7), and the content security policy every page carries.
 *
 * The policy allows a script only from the application's own origin and only when it carries the request's nonce,
 * which the proxy makes fresh for every request and Next.js puts on its own scripts as it renders the page;
 * `'strict-dynamic'` lets those scripts load the chunks they need. No inline or injected script runs without the nonce;
 * scripts are never allowed `'unsafe-inline'`. Styles may be inline: the salon's brand rule (`BrandStyle`), the
 * runtime styles of input-otp and Sonner, and the style attributes Radix renders on the server have no nonce, and a
 * style cannot run code. Nothing may frame a page, no plugin loads, forms and the base URL stay on the origin, and
 * images, fonts and requests come from the origin only (a photo is passed through it; `data:` and `blob:` are a picked
 * file's preview).
 */
export function contentSecurityPolicy(nonce: string, development = false): string {
  return [
    "default-src 'self'",
    `script-src 'self' 'nonce-${nonce}' 'strict-dynamic'${development ? " 'unsafe-eval'" : ''}`,
    "style-src 'self' 'unsafe-inline'",
    "img-src 'self' blob: data:",
    "font-src 'self'",
    "connect-src 'self'",
    "object-src 'none'",
    "base-uri 'self'",
    "form-action 'self'",
    "frame-ancestors 'none'",
  ].join('; ');
}

/** A fresh nonce for one request: 128 random bits. */
export function newNonce(): string {
  const bytes = crypto.getRandomValues(new Uint8Array(16));
  return btoa(String.fromCharCode(...bytes));
}

/** The headers every response carries, page or not, set by the proxy and by `next.config.ts` for what it skips. */
export const SECURITY_HEADERS: Readonly<Record<string, string>> = {
  'X-Content-Type-Options': 'nosniff',
  'Referrer-Policy': 'same-origin',
  'X-Frame-Options': 'DENY',
};
