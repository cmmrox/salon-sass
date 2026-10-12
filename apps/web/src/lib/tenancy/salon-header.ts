import type { Salon } from '@salon/api-contracts';

import { isHexColour } from '@/styles/brand';

/**
 * The request header in which `proxy.ts` hands the salon it resolved to the page it rewrote to.
 *
 * The proxy removes this header from every request before anything else, and sets it only for a
 * salon it has just resolved from the hostname, so a value a client sends never reaches a page.
 */
export const SALON_HEADER = 'x-salon-resolved';

/** The salon, as the header carries it: URI-encoded JSON, so that any name fits in a header. */
export function encodeSalon(salon: Salon): string {
  return encodeURIComponent(JSON.stringify({ slug: salon.slug, name: salon.name, brandColour: salon.brandColour }));
}

/** The salon the header carries, or null when it carries none or anything else. */
export function decodeSalon(value: string | null | undefined): Salon | null {
  if (!value) {
    return null;
  }
  let parsed: unknown;
  try {
    parsed = JSON.parse(decodeURIComponent(value));
  } catch {
    return null;
  }
  if (typeof parsed !== 'object' || parsed === null) {
    return null;
  }
  const { slug, name, brandColour } = parsed as Record<string, unknown>;
  if (typeof slug !== 'string' || typeof name !== 'string' || name.trim() === '' || !isHexColour(brandColour)) {
    return null;
  }
  return { slug, name, brandColour };
}
