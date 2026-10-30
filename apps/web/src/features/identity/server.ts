// The identity module's server entry: the workspace BFF, the entry decision for protected workspace
// pages, and the session cookies the proxy reads and clears.
import 'server-only';

export { hasCredentials, workspaceEntry, type Entry } from './server/entry';
export {
  clearCookies,
  cookieName,
  createIdentityWeb,
  endSupportView,
  proxyKey,
  requestHost,
  supportCsrf,
  supportEnded,
  type Credentials,
} from './server/identity-web';
export type { Kind, Principal } from './types';
