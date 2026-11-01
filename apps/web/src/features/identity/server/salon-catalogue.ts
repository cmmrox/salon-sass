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
 * The catalogue paths the workspace BFF reaches (F05-S01, F05-S02): its two lists and the Popular setting, every
 * change, and a service's photo.
 * Identity checks the session and the permission first; the catalogue makes the calls and builds each request from
 * the contract's own fields, and the route serving the BFF hands its implementation in, so that neither module
 * imports the other.
 */
export interface SalonCatalogue {
  categories(call: Call): Promise<Answer>;
  services(call: Call): Promise<Answer>;
  popularSetting(call: Call): Promise<Answer>;
  change(call: Call, action: string, body: unknown): Promise<Answer>;
  uploadPhoto(call: Call, serviceId: string, file: Blob): Promise<Answer>;
}

/** The catalogue calls for one salon's hostname; null when the API is not configured. */
export type SalonCatalogueFor = (hostname: string) => SalonCatalogue | null;

/** The BFF's reads of the catalogue, each `catalogue.read`: every role and a support session (decision 093). */
export const CATALOGUE_READS = ['catalogue-categories', 'catalogue-services', 'catalogue-popular-setting'];

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
];
export const CATALOGUE_PHOTO = 'catalogue-photo';

/** The permission each action needs. */
export const CATALOGUE_PERMISSIONS: Record<string, string> = Object.fromEntries([
  ...CATALOGUE_READS.map((action) => [action, 'catalogue.read']),
  ...[...CATALOGUE_WRITES, CATALOGUE_PHOTO].map((action) => [action, 'catalogue.price.change']),
]);

/** One read. */
export function readCatalogue(catalogue: SalonCatalogue, action: string, call: Call) {
  if (action === 'catalogue-categories') return catalogue.categories(call);
  return action === 'catalogue-services' ? catalogue.services(call) : catalogue.popularSetting(call);
}
