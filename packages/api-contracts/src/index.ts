import type { components } from './generated/schema';

export { createApiClient } from './client';
export type { ApiClient, ApiClientOptions } from './client';
export type { components, operations, paths } from './generated/schema';

/** What the service says about itself: `GET /api/v1/meta`. */
export type Meta = components['schemas']['Meta'];

/** The public brand of the salon the request's hostname names: `GET /api/v1/salon`. */
export type Salon = components['schemas']['Salon'];

/** Every error response: problem details with a stable `code`. Branch on `code`, never on the wording. */
export type Problem = components['schemas']['Problem'];
