/**
 * How a salon's public photos may be kept (the lead's ruling on web M6, docs/engineering/web.md, "Tenant routing"):
 * by the visitor's own browser only (`private`: never an edge or any shared cache, no `public`, no `s-maxage`), under
 * the salon's own address, which names its hostname, so one salon's photo can never be served for another's address.
 * A photo is addressed by its id and its bytes never change under it (the API makes a new id for every upload and
 * deletes the one it replaces), so it is `immutable`. A person's photo is kept for an hour, so that one removed or of
 * someone who has left stops being shown within it; a service's photo and a published brand image for a day. No
 * `Vary: Cookie`: the answer kept is the one the API gives anyone, asked without a session. Every other answer (not
 * found, a draft fetched with the session, a suspended or unknown salon) is never stored.
 */
export const NOT_STORED = 'private, no-cache, no-store, max-age=0, must-revalidate';
export const SERVICE_PHOTO_CACHE = 'private, max-age=86400, immutable';
export const BRAND_IMAGE_CACHE = 'private, max-age=86400, immutable';
export const TEAM_PHOTO_CACHE = 'private, max-age=3600, immutable';
