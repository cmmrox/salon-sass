/**
 * The one way an API test talks to the product: Playwright's request context, pointed at the
 * address in shared/env.ts. Response types come from the generated contract and are never copied.
 */
import { request, type APIRequestContext, type APIResponse } from '@playwright/test';

import type { components } from '@contracts/schema';

import { apiUrl } from './env';
import { onHostname, salonUrl, type Salon } from './tenants';

export type Meta = components['schemas']['Meta'];
export type Problem = components['schemas']['Problem'];
/** A salon's public brand, as the contract describes it. */
export type SalonBrand = components['schemas']['Salon'];

/** The path of the current salon's public brand. */
export const SALON_PATH = '/api/v1/salon';

export const PROBLEM_JSON = 'application/problem+json';

export class ApiClient {
  private constructor(private readonly context: APIRequestContext) {}

  static async create(): Promise<ApiClient> {
    return new ApiClient(
      await request.newContext({ baseURL: apiUrl, timeout: 15_000, maxRedirects: 0 }),
    );
  }

  get(path: string, headers?: Record<string, string>): Promise<APIResponse> {
    return this.context.get(path, { headers });
  }

  send(method: string, path: string, options: { headers?: Record<string, string>; data?: string | Buffer } = {}): Promise<APIResponse> {
    return this.context.fetch(path, { method, headers: options.headers, data: options.data });
  }

  /**
   * A GET on the API as a named host: the same port, another hostname. This is how a salon is
   * addressed, so the name really is resolved and the request really carries it as its Host.
   */
  getOn(hostname: string, path: string, headers?: Record<string, string>): Promise<APIResponse> {
    return this.context.get(`${onHostname(apiUrl, hostname)}${path}`, { headers });
  }

  /** The public brand of a salon, asked for on that salon's own hostname. */
  salon(of: Salon, options: { query?: string; headers?: Record<string, string> } = {}): Promise<APIResponse> {
    return this.context.get(`${salonUrl(of, apiUrl)}${SALON_PATH}${options.query ?? ''}`, { headers: options.headers });
  }

  meta(): Promise<APIResponse> {
    return this.get('/api/v1/meta');
  }

  health(): Promise<APIResponse> {
    return this.get('/actuator/health');
  }

  info(): Promise<APIResponse> {
    return this.get('/actuator/info');
  }

  dispose(): Promise<void> {
    return this.context.dispose();
  }
}

/** The media type of a response, without parameters such as the character set. */
export function mediaType(response: APIResponse): string {
  return (response.headers()['content-type'] ?? '').split(';')[0].trim().toLowerCase();
}
