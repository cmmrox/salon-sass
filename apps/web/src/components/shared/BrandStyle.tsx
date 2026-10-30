import { neutralRootRule, salonBrandRule } from '@/styles/brand';

/**
 * Puts the salon's brand on the root of a salon screen, sign-in and workspace alike (CR-013):
 * its colour, the readable colour drawn on it, its soft tint and its text colour, or the prototype's
 * default when the salon has no valid colour. Only a validated `#RRGGBB` reaches the rule. With
 * `neutral`, the platform's neutral accent instead (decision 047): the operator console, which belongs
 * to no salon.
 */
export function BrandStyle({ colour, neutral = false }: { colour?: unknown; neutral?: boolean }) {
  return <style data-testid={neutral ? 'console-accent' : 'salon-brand'} dangerouslySetInnerHTML={{ __html: neutral ? neutralRootRule() : salonBrandRule(colour) }} />;
}
