// The branding module's server entry: the salon's published brand, asked for by hostname, and the
// header that hands it from the proxy to the pages.
import 'server-only';

export { findSalon, type SalonLookup } from './server/salon';
export { decodeSalon, encodeSalon, SALON_HEADER } from './server/salon-header';
