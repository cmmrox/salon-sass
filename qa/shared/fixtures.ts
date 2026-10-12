/**
 * The fixtures every Playwright test of this product starts from. Import `test` and `expect` from
 * here, never from @playwright/test, so that sign-in and data builders have one home when the
 * stages that need them arrive (F01-S02 for the synthetic salons, F02 for accounts).
 */
import { test as base, expect } from '@playwright/test';

import { ApiClient } from './api-client';

type Fixtures = {
  /** A client for the product's API, at the address in shared/env.ts. */
  api: ApiClient;
};

export const test = base.extend<Fixtures>({
  api: async ({}, use) => {
    const client = await ApiClient.create();
    await use(client);
    await client.dispose();
  },
});

export { expect };
