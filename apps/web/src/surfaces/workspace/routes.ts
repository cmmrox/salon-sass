import 'server-only';
import { bookingRoutes } from '@/features/booking/server';
import { brandingRoutes } from '@/features/branding/server';
import { catalogueRoutes } from '@/features/catalogue/server';
import { identityRoutes } from '@/features/identity/server';
import { platformRoutes } from '@/features/platform/server';
import { staffRoutes } from '@/features/staff/server';
import { routeTable } from '@/lib/api-core/routes';

/**
 * The workspace's one route table (decision 121; web H4, M5): every module's pages, BFF actions and the permissions
 * they need, as each module declares them in its own `server/routes.ts`. The proxy protects its pages, the page
 * checks their permission, the frame titles them, the navigation lists them and the BFF serves their actions from it;
 * a page or an action is declared nowhere else.
 */
export const WORKSPACE_ROUTES = routeTable(
  identityRoutes,
  bookingRoutes,
  staffRoutes,
  catalogueRoutes,
  platformRoutes,
  brandingRoutes,
);
