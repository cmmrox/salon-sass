import { createApiClient, type ApiClient } from '@salon/api-contracts';

/** How long the web app waits for the API before it treats the service as unavailable. */
export const API_TIMEOUT_MS = 3000;

/**
 * A client for calls made on the server while rendering. The address comes from the environment
 * at request time, so one build runs in every environment. Null when it is not configured.
 */
export function createServerApiClient(): ApiClient | null {
  const baseUrl = process.env.SALON_API_BASE_URL?.replace(/\/+$/, '');
  if (!baseUrl) {
    return null;
  }
  return createApiClient({ baseUrl });
}
