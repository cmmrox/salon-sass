import 'server-only';
import { brandingPages } from '../pages';
import { moduleRoutes, type BffChange, type Call } from '@/lib/api-core/routes';
import { salonSettingsFor } from './salon-settings';

type Settings = NonNullable<ReturnType<typeof salonSettingsFor>>;

const settingsChange = (
  ask: (settings: Settings, call: Call, body: Record<string, unknown>) => ReturnType<BffChange['change']>,
): BffChange => ({
  permission: 'settings.manage',
  change: (hostname, call, body) => {
    const settings = salonSettingsFor(hostname);
    return settings ? ask(settings, call, body) : null;
  },
});

/**
 * The branding module's workspace pages and BFF actions: Settings (F04-S01), opened on its Profile tab, with the
 * Branding tab (F04-S02); the profile's and the brand's drafts read with `settings.read`, saved, published, discarded
 * (and the brand reset) with `settings.manage`, and a brand image uploaded. The draft the API answers is allowlisted
 * by its contract; the request is rebuilt by branding.
 */
export const brandingRoutes = moduleRoutes('branding', {
  pages: brandingPages,
  reads: {
    'profile-draft': {
      permission: 'settings.read',
      read: (hostname, call) => salonSettingsFor(hostname)?.profileDraft(call) ?? null,
    },
    'brand-draft': {
      permission: 'settings.read',
      read: (hostname, call) => salonSettingsFor(hostname)?.brandDraft(call) ?? null,
    },
  },
  changes: {
    'profile-save': settingsChange((settings, call, body) =>
      settings.saveProfileDraft(call, body.version, body.profile),
    ),
    'profile-publish': settingsChange((settings, call, body) => settings.publishProfile(call, body.version)),
    'profile-discard': settingsChange((settings, call, body) => settings.discardProfileDraft(call, body.version)),
    'brand-save': settingsChange((settings, call, body) => settings.saveBrandDraft(call, body.version, body.brand)),
    'brand-reset': settingsChange((settings, call, body) => settings.resetBrandDraft(call, body.version)),
    'brand-publish': settingsChange((settings, call, body) => settings.publishBrand(call, body.version)),
    'brand-discard': settingsChange((settings, call, body) => settings.discardBrandDraft(call, body.version)),
  },
  uploads: {
    'brand-image': {
      permission: 'settings.manage',
      field: 'kind',
      created: true,
      upload: (hostname, call, kind, file) => salonSettingsFor(hostname)?.uploadBrandImage(call, kind, file) ?? null,
    },
  },
});
