import { bookingPages } from '@/features/booking';
import { brandingPages } from '@/features/branding';
import { cataloguePages } from '@/features/catalogue';
import { identityPages } from '@/features/identity';
import { platformPages } from '@/features/platform';
import { staffPages } from '@/features/staff';

/**
 * The workspace's pages, as the modules declare them (decision 121; web M5), for the frame's title and the menu, which
 * render where the server's route table (`routes.ts`, the same pages with every module's BFF actions) cannot be read.
 * A test holds the two equal.
 */
export const WORKSPACE_PAGES = [
  ...identityPages,
  ...bookingPages,
  ...staffPages,
  ...cataloguePages,
  ...platformPages,
  ...brandingPages,
];

/** The page that opens each view: its permission and title. */
export function pageOf(view: string) {
  return WORKSPACE_PAGES.find((page) => page.view === view) ?? null;
}
