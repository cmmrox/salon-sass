/**
 * A salon's brand colour as the page uses it (docs/engineering/web.md, "Branding"). The colour
 * arrives from the API at run time and is applied as three custom properties on the root:
 *
 * - `--color-brand`: the salon's colour;
 * - `--color-on-brand`: the colour of anything drawn on it, computed here for contrast. No
 *   component assumes white.
 * - `--color-brand-soft`: validated brand plus 22 alpha, the prototype's soft tint (`--acs`);
 * - `--color-brand-text`: the brand as a text colour on a white surface (links), or the platform
 *   text colour when the brand is too light to read there.
 *
 * Every salon screen, sign-in and workspace alike, takes its accent from these (CR-013). A salon
 * without a valid colour gets the prototype's default, {@link DEFAULT_BRAND_COLOUR}.
 */

/** The prototype's default brand (`--ac`), for a salon without a valid colour of its own. */
export const DEFAULT_BRAND_COLOUR = '#D6086A';

/** The surface brand-coloured text is drawn on: the white card (`--color-surface` in tokens.css). */
export const BRAND_TEXT_SURFACE = '#ffffff';

/** The least contrast normal-size text may have (WCAG 2.2 AA). */
const READABLE = 4.5;

/** A brand colour as the API gives it: `#RRGGBB`. */
const HEX_COLOUR = /^#[0-9a-f]{6}$/i;

/**
 * The two colours text on a brand colour can take: white, and the platform's own text colour
 * (`--color-text` in tokens.css). Whichever contrasts more with the brand colour is used.
 */
export const ON_BRAND_COLOURS = { light: '#ffffff', dark: '#111827' } as const;

/** Whether a value is a colour this module accepts: `#RRGGBB`. */
export function isHexColour(value: unknown): value is string {
  return typeof value === 'string' && HEX_COLOUR.test(value);
}

/**
 * The relative luminance of a colour, as WCAG 2.2 defines it: 0 for black, 1 for white.
 *
 * @throws Error when the colour is not `#RRGGBB`
 */
export function relativeLuminance(colour: string): number {
  if (!isHexColour(colour)) {
    throw new Error(`not a #RRGGBB colour: '${colour}'`);
  }
  const linear = (start: number): number => {
    const channel = Number.parseInt(colour.slice(start, start + 2), 16) / 255;
    return channel <= 0.04045 ? channel / 12.92 : ((channel + 0.055) / 1.055) ** 2.4;
  };
  return 0.2126 * linear(1) + 0.7152 * linear(3) + 0.0722 * linear(5);
}

/** The contrast ratio of two colours, as WCAG 2.2 defines it: from 1 (none) to 21. */
export function contrastRatio(first: string, second: string): number {
  const one = relativeLuminance(first);
  const other = relativeLuminance(second);
  return (Math.max(one, other) + 0.05) / (Math.min(one, other) + 0.05);
}

/** The colour to draw text in on a brand colour: whichever of {@link ON_BRAND_COLOURS} contrasts more. */
export function contrastColour(brandColour: string): string {
  const onLight = contrastRatio(brandColour, ON_BRAND_COLOURS.dark);
  const onDark = contrastRatio(brandColour, ON_BRAND_COLOURS.light);
  return onDark >= onLight ? ON_BRAND_COLOURS.light : ON_BRAND_COLOURS.dark;
}

/**
 * The colour brand-coloured text (a link) takes on a white surface: the brand itself when it is
 * readable there, otherwise the platform's text colour, so a light brand never makes a link unreadable.
 */
export function brandTextColour(brandColour: string): string {
  return contrastRatio(brandColour, BRAND_TEXT_SURFACE) >= READABLE ? brandColour : ON_BRAND_COLOURS.dark;
}

/**
 * The rule that puts a brand on the root: `:root{--color-brand:…;--color-on-brand:…;…}`. Only a
 * `#RRGGBB` colour is accepted, so nothing else can reach the style sheet.
 *
 * @throws Error when the colour is not `#RRGGBB`
 */
export function brandRootRule(brandColour: string): string {
  if (!isHexColour(brandColour)) {
    throw new Error(`not a #RRGGBB colour: '${brandColour}'`);
  }
  // Exactly as the API gave it, so that the property reads back as the salon's colour.
  return `:root{--color-brand:${brandColour};--color-on-brand:${contrastColour(brandColour)};--color-brand-soft:${brandColour}22;--color-brand-text:${brandTextColour(brandColour)}}`;
}

/**
 * The brand rule of a salon screen: the salon's colour when it is a valid `#RRGGBB`, otherwise
 * the prototype's default. Never throws, so a salon screen always has its accent.
 */
export function salonBrandRule(brandColour: unknown): string {
  return brandRootRule(isHexColour(brandColour) ? brandColour : DEFAULT_BRAND_COLOUR);
}
