import type { components } from '@salon/api-contracts/platform';

/** The signed-in operator the API confirms: `GET /api/v1/operator/me`. */
export type OperatorPrincipal = components['schemas']['OperatorPrincipal'];

/** The salon's plan, limits, usage and subscription state: `GET /api/v1/salon/entitlements` (F03-S02). */
export type Entitlements = components['schemas']['Entitlements'];
