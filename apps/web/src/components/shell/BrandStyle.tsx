import { salonBrandRule } from '@/styles/brand';

/**
 * Puts the salon's brand on the root of a salon screen, sign-in and workspace alike (CR-013):
 * its colour, the readable colour drawn on it, its soft tint and its text colour, or the prototype's
 * default when the salon has no valid colour. Only a validated `#RRGGBB` reaches the rule.
 */
export function BrandStyle({ colour }: { colour: unknown }) {
  return <style data-testid="salon-brand" dangerouslySetInnerHTML={{ __html: salonBrandRule(colour) }} />;
}
