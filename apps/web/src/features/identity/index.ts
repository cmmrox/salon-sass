// The identity module's client-safe entry: the staff screens, the controls the workspace's other
// screens share, and the browser's side of the workspace session.
export { AccountScreen } from './components/AccountScreen';
export { AccountLoading, Button, Denied, Section } from './components/Controls';
export { SignIn } from './components/SignIn';
export {
  ensurePrincipal,
  IdentityFailure,
  identityRequest,
  leaveEndedSession,
  principalInsideLock,
  supportsLocks,
  withIdentityLock,
} from './client/session';
export type { Kind } from './types';
