import { modulePages } from '@/lib/api-core/pages';

/**
 * The identity module's workspace pages: where each is, the view the workspace renders, who may see it, its title and its
 * place in the menu. Declared here, client-safe, so that the frame and the menu can read them; registered with the
 * module's BFF actions in `server/routes.ts` (decision 121).
 */
export const identityPages = modulePages('identity', [
  { path: '/workspace/account', view: 'account', permission: 'session.manage', title: 'account' },
  { path: '/workspace/staff', view: 'staff', permission: 'staff.list', title: 'team' },
  {
    path: '/workspace/audit',
    view: 'audit',
    permission: 'audit.list',
    title: 'audit',
    nav: { order: 40, label: 'audit', icon: 'audit', testId: 'workspace-audit' },
  },
]);
