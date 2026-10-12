/**
 * The one way an API test talks to the product: Playwright's request context, pointed at the
 * address in shared/env.ts. Response types come from the generated contract and are never copied.
 */
import { request, type APIRequestContext, type APIResponse } from '@playwright/test';

import type { components } from '@contracts/schema';

import { apiUrl } from './env';

export type Meta = components['schemas']['Meta'];
export type Problem = components['schemas']['Problem'];

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
