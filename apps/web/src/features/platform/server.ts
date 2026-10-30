// The platform module's server entry: the operator console's BFF and entry decision, the platform
// host's export BFF, and the salon account calls the workspace BFF makes for a salon user.
import 'server-only';

export { hasOperatorCredentials, operatorEntry, type OperatorEntry } from './server/operator-entry';
export { clearOperatorCookies, credentialsOf, handleOperator, operatorCookie } from './server/operator-web';
export { handlePlatform } from './server/platform-web';
export { readEntitlements, salonAccountFor } from './server/salon-account';
