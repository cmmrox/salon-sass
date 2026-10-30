import 'server-only';
import { createApiClient } from '@salon/api-contracts/platform';
import { createServerApiClient } from '@/lib/api-core/client';

/**
 * A client for the platform module's paths, for calls made under this hostname: a salon's, the
 * console's or the platform's own. Null when the API is not configured.
 */
export const platformClient = (hostname: string) => createServerApiClient(createApiClient, { hostname });
