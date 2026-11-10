import { modulePages } from '@/lib/api-core/pages';

/**
 * The staff module's workspace pages: where each is, the view the workspace renders, who may see it, its title and its
 * place in the menu. Declared here, client-safe, so that the frame and the menu can read them; registered with the
 * module's BFF actions in `server/routes.ts` (decision 121).
 */
export const staffPages = modulePages('staff', [
  {
    path: '/workspace/team',
    view: 'team',
    permission: 'team.read',
    title: 'team',
    nav: { order: 20, label: 'teamNav', icon: 'staff', testId: 'workspace-team', alsoFor: ['staff'] },
  },
  { path: '/workspace/settings/hours', view: 'settings', permission: 'settings.read', title: 'settings' },
]);
