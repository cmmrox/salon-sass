import type { components } from '@salon/api-contracts/branding';

/**
 * What the web app carries about a salon: its public brand. The contract's salon holds more, which
 * no screen uses yet; a screen that needs more of it takes it then.
 */
export type Salon = Pick<components['schemas']['Salon'], 'slug' | 'name' | 'brandColour' | 'timeZone'>;
