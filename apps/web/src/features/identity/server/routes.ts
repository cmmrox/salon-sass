import 'server-only';
import { identityPages } from '../pages';
import { moduleRoutes } from '@/lib/api-core/routes';

/**
 * The identity module's workspace pages: Account, the Sign-in accounts tab of the Team page and the audit trail. Its
 * BFF actions are sign-in and the session's own (`identity-web.ts`); every other module registers its own.
 */
export const identityRoutes = moduleRoutes('identity', {
  pages: identityPages,
});
