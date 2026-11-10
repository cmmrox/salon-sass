import 'server-only';
import { cataloguePages } from '../pages';
import { moduleRoutes, type BffChange, type BffRead } from '@/lib/api-core/routes';
import { salonCatalogueFor } from './salon-catalogue';

const READ = 'catalogue.read';
const CHANGE = 'catalogue.price.change';

const read = (
  ask: (
    catalogue: NonNullable<ReturnType<typeof salonCatalogueFor>>,
    ...rest: Parameters<BffRead['read']>
  ) => ReturnType<BffRead['read']>,
  permission?: string,
  open = false,
): BffRead => ({
  permission,
  open,
  read: (hostname, call, query) => {
    const catalogue = salonCatalogueFor(hostname);
    return catalogue ? ask(catalogue, hostname, call, query) : null;
  },
});

/** A change named by its action, which the catalogue rebuilds from the contract's own fields. */
const named = (action: string, permission = CHANGE): [string, BffChange] => [
  action,
  { permission, change: (hostname, call, body) => salonCatalogueFor(hostname)?.change(call, action, body) ?? null },
];

/**
 * The catalogue's workspace pages and BFF actions (F05-S01 to F05-S03): Services & pricing with its Categories and
 * Products tabs, the Tax & charges tab of Settings, every read with `catalogue.read` (the tax's with
 * `settings.read`), every change with `catalogue.price.change` (the tax's with `settings.manage`), and a service's
 * photo. The salon's current tax is read by its customers too: its endpoint answers anyone.
 */
export const catalogueRoutes = moduleRoutes('catalogue', {
  pages: cataloguePages,
  reads: {
    'catalogue-categories': read((catalogue, _host, call) => catalogue.categories(call), READ),
    'catalogue-services': read((catalogue, _host, call) => catalogue.services(call), READ),
    'catalogue-popular-setting': read((catalogue, _host, call) => catalogue.popularSetting(call), READ),
    'catalogue-products': read((catalogue, _host, call) => catalogue.products(call), READ),
    'catalogue-tax-settings': read((catalogue, _host, call) => catalogue.taxSettings(call), 'settings.read'),
    'catalogue-tax-history': read((catalogue, _host, call, query) => {
      const page = Number(query.get('page') ?? 0);
      return catalogue.taxHistory(call, Number.isInteger(page) && page >= 0 ? page : 0);
    }, 'settings.read'),
    // JSON null before the first save is an answer: it is passed on as `{ current: null }`.
    'catalogue-tax-public': read(
      async (catalogue, _host, call) => {
        const answer = await catalogue.taxPublic(call);
        return answer.response.ok ? { data: { current: answer.data ?? null }, response: answer.response } : answer;
      },
      undefined,
      true,
    ),
  },
  changes: Object.fromEntries([
    ...[
      'catalogue-category-add',
      'catalogue-category-rename',
      'catalogue-category-move',
      'catalogue-category-delete',
      'catalogue-service-create',
      'catalogue-service-save',
      'catalogue-service-duplicate',
      'catalogue-service-online',
      'catalogue-service-delete',
      'catalogue-photo-remove',
      'catalogue-service-popular',
      'catalogue-popular-mode',
      'catalogue-product-add',
      'catalogue-product-save',
      'catalogue-product-delete',
    ].map((action) => named(action)),
    named('catalogue-tax-save', 'settings.manage'),
  ]),
  uploads: {
    'catalogue-photo': {
      permission: CHANGE,
      field: 'serviceId',
      upload: (hostname, call, serviceId, file) =>
        salonCatalogueFor(hostname)?.uploadPhoto(call, serviceId, file) ?? null,
    },
  },
});
