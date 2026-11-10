import { modulePages } from '@/lib/api-core/pages';

/**
 * The branding module's workspace pages: where each is, the view the workspace renders, who may see it, its title and its
 * place in the menu. Declared here, client-safe, so that the frame and the menu can read them; registered with the
 * module's BFF actions in `server/routes.ts` (decision 121).
 */
export const brandingPages = modulePages('branding', [
  {
    path: '/workspace/settings',
    view: 'settings',
    permission: 'settings.read',
    title: 'settings',
    // Last, as in the prototype's navigation (F04-S01).
    nav: { order: 70, label: 'settings', icon: 'settings', testId: 'workspace-settings' },
  },
  { path: '/workspace/settings/profile', view: 'settings', permission: 'settings.read', title: 'settings' },
  { path: '/workspace/settings/branding', view: 'settings', permission: 'settings.read', title: 'settings' },
]);
