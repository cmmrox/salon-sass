import { modulePages } from '@/lib/api-core/pages';

/**
 * The booking module's workspace pages: where each is, the view the workspace renders, who may see it, its title and its
 * place in the menu. Declared here, client-safe, so that the frame and the menu can read them; registered with the
 * module's BFF actions in `server/routes.ts` (decision 121).
 */
export const bookingPages = modulePages('booking', [
  {
    path: '/workspace/bookings',
    view: 'bookings',
    permission: 'calendar.read',
    title: 'bookings',
    // After Today's place in the prototype's order, which F10 delivers, so first after Account.
    nav: { order: 10, label: 'bookingsNav', icon: 'bookings', testId: 'workspace-bookings' },
  },
  { path: '/workspace/settings/rules', view: 'settings', permission: 'settings.read', title: 'settings' },
]);
