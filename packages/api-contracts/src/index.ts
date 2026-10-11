import type { components } from './generated/schema';

export { createApiClient } from './client';
export type { ApiClient, ApiClientOptions } from './client';
export type { components, operations, paths } from './generated/schema';

/** What the service says about itself: `GET /api/v1/meta`. */
export type Meta = components['schemas']['Meta'];

/** Every error response: problem details with a stable `code`. Branch on `code`, never on the wording. */
export type Problem = components['schemas']['Problem'];
