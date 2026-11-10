import { modulePages } from '@/lib/api-core/pages';

/**
 * The platform module's workspace pages: where each is, the view the workspace renders, who may see it, its title and its
 * place in the menu. Declared here, client-safe, so that the frame and the menu can read them; registered with the
 * module's BFF actions in `server/routes.ts` (decision 121).
 */
export const platformPages = modulePages('platform', [
  {
    path: '/workspace/billing',
    view: 'billing',
    permission: 'invoice.read',
    title: 'billing',
    nav: { order: 50, label: 'billing', icon: 'billing', testId: 'workspace-billing' },
  },
  {
    path: '/workspace/support-access',
    view: 'support-access',
    permission: 'support.manage',
    title: 'supportAccess',
    nav: { order: 60, label: 'supportAccess', icon: 'support', testId: 'workspace-support-access' },
  },
]);
