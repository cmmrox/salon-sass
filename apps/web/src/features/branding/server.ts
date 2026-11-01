// The branding module's server entry: the salon's published brand, asked for by hostname, the header
// that hands it from the proxy to the pages, and the settings calls the workspace BFF makes (F04-S01).
import 'server-only';

export { findSalon, type SalonLookup } from './server/salon';
export { decodeSalon, encodeSalon, SALON_HEADER } from './server/salon-header';
export { salonSettingsFor } from './server/salon-settings';
