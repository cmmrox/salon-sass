import { modulePages } from '@/lib/api-core/pages';

/**
 * The catalogue module's workspace pages: where each is, the view the workspace renders, who may see it, its title and its
 * place in the menu. Declared here, client-safe, so that the frame and the menu can read them; registered with the
 * module's BFF actions in `server/routes.ts` (decision 121).
 */
export const cataloguePages = modulePages('catalogue', [
  {
    path: '/workspace/services',
    view: 'services',
    permission: 'catalogue.read',
    title: 'services',
    nav: { order: 30, label: 'servicesNav', icon: 'services', testId: 'workspace-services' },
  },
  { path: '/workspace/services/categories', view: 'services', permission: 'catalogue.read', title: 'services' },
  { path: '/workspace/services/products', view: 'services', permission: 'catalogue.read', title: 'services' },
  { path: '/workspace/settings/tax', view: 'settings', permission: 'settings.read', title: 'settings' },
]);
