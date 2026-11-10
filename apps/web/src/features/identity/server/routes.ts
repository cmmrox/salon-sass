import 'server-only';
import { moduleRoutes } from '@/lib/api-core/routes';

/**
 * The identity module's workspace pages: Account, the Sign-in accounts tab of the Team page and the audit trail. Its
 * BFF actions are sign-in and the session's own (`identity-web.ts`); every other module registers its own.
 */
export const identityRoutes = moduleRoutes('identity', {
  pages: [
    { path: '/workspace/account', view: 'account', permission: 'session.manage', title: 'account' },
    { path: '/workspace/staff', view: 'staff', permission: 'staff.list', title: 'team' },
    {
      path: '/workspace/audit',
      view: 'audit',
      permission: 'audit.list',
      title: 'audit',
      nav: { order: 40, label: 'audit', icon: 'audit', testId: 'workspace-audit' },
    },
  ],
});
