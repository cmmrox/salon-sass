import 'server-only';

/** What a call the workspace BFF hands on answers: its data or the API's problem, and the response. */
type Answer = {
  data?: unknown;
  error?: { code?: string; errors?: unknown };
  response: Response;
};

/** The caller's credential and the request's shared API budget, for one call. */
type Call = { headers: Record<string, string | undefined>; signal: AbortSignal };

/**
 * The catalogue paths the workspace BFF reaches (F05-S01 to F05-S03): its two lists, the Popular setting, the
 * products and the tax settings with their history and public read, every change, and a service's photo.
 * Identity checks the session and the permission first; the catalogue makes the calls and builds each request from
 * the contract's own fields, and the route serving the BFF hands its implementation in, so that neither module
 * imports the other.
 */
export interface SalonCatalogue {
  categories(call: Call): Promise<Answer>;
  services(call: Call): Promise<Answer>;
  popularSetting(call: Call): Promise<Answer>;
  products(call: Call): Promise<Answer>;
  taxSettings(call: Call): Promise<Answer>;
  taxHistory(call: Call, page: number): Promise<Answer>;
  /** The salon's current tax as customers may know it, or JSON null before the first save (F05-S03). */
  taxPublic(call: Call): Promise<Answer>;
  change(call: Call, action: string, body: unknown): Promise<Answer>;
  uploadPhoto(call: Call, serviceId: string, file: Blob): Promise<Answer>;
}

/** The catalogue calls for one salon's hostname; null when the API is not configured. */
export type SalonCatalogueFor = (hostname: string) => SalonCatalogue | null;

/** The catalogue's reads that are its settings (F05-S03): `settings.read`, the Owner, the Manager and support. */
const TAX_READS = ['catalogue-tax-settings', 'catalogue-tax-history'];

/**
 * The salon's current tax, read on the Services and Products tabs for their banner (F05-S03 AC-10). Public data, so
 * any signed-in session may read it; it needs no permission.
 */
const TAX_PUBLIC = 'catalogue-tax-public';

/**
 * The BFF's reads of the catalogue: each `catalogue.read` (every role and a support session, decision 093), but the
 * tax settings and their history (`settings.read`) and the public tax (none).
 */
export const CATALOGUE_READS = [
  'catalogue-categories',
  'catalogue-services',
  'catalogue-popular-setting',
  'catalogue-products',
  ...TAX_READS,
  TAX_PUBLIC,
];

/** The tax settings' save, `settings.manage` (decision 085). */
const TAX_SAVE = 'catalogue-tax-save';

/** Its changes, each `catalogue.price.change`; the photo upload is the one multipart action. */
export const CATALOGUE_WRITES = [
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
  TAX_SAVE,
];
export const CATALOGUE_PHOTO = 'catalogue-photo';

/** The permission each action needs. */
export const CATALOGUE_PERMISSIONS: Record<string, string> = Object.fromEntries([
  ...CATALOGUE_READS.filter((action) => action !== TAX_PUBLIC && !TAX_READS.includes(action)).map((action) => [
    action,
    'catalogue.read',
  ]),
  ...TAX_READS.map((action) => [action, 'settings.read']),
  ...[...CATALOGUE_WRITES.filter((action) => action !== TAX_SAVE), CATALOGUE_PHOTO].map((action) => [
    action,
    'catalogue.price.change',
  ]),
  [TAX_SAVE, 'settings.manage'],
]);

/**
 * One read: the tax history names its page in the query (a whole number from 0). The public tax is answered as
 * `{current}`, null before the first save, so that a JSON null is not taken for a failure.
 */
export async function readCatalogue(
  catalogue: SalonCatalogue,
  action: string,
  call: Call,
  query: URLSearchParams,
): Promise<Answer> {
  switch (action) {
    case 'catalogue-categories':
      return catalogue.categories(call);
    case 'catalogue-services':
      return catalogue.services(call);
    case 'catalogue-products':
      return catalogue.products(call);
    case 'catalogue-tax-settings':
      return catalogue.taxSettings(call);
    case 'catalogue-tax-history': {
      const page = Number(query.get('page') ?? 0);
      return catalogue.taxHistory(call, Number.isInteger(page) && page >= 0 ? page : 0);
    }
    case TAX_PUBLIC: {
      const answer = await catalogue.taxPublic(call);
      return answer.response.ok ? { data: { current: answer.data ?? null }, response: answer.response } : answer;
    }
    default:
      return catalogue.popularSetting(call);
  }
}
