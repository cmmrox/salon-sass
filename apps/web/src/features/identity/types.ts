import type { components } from '@salon/api-contracts/identity';

/** Who a workspace session belongs to: salon staff, or the operator's support view of the salon (F03-S04). */
export type Kind = 'staff' | 'support';

/** The signed-in principal the API confirms: `GET /api/v1/identity/me`. */
export type Principal = components['schemas']['Principal'];
