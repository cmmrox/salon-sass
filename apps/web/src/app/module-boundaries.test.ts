// @vitest-environment node
import { join } from 'node:path';

import { ESLint } from 'eslint';
import { beforeAll, describe, expect, it } from 'vitest';

/**
 * docs/engineering/web.md, "Structure" (CR-021, F23-S06): the lint rules that keep the web app organised
 * by module. Each fixture is linted as if it were the named file; the application itself is linted by
 * the `lint` gate with the same configuration.
 */
const eslint = new ESLint({ cwd: join(__dirname, '..', '..') });

// Loading the configuration (Next.js's rules, the plugins and the import resolver) takes seconds on a
// busy machine; it is done once here, so that each case's time is its own lint.
beforeAll(() => eslint.lintText('', { filePath: 'src/lib/probe.ts' }), 60_000);

async function ruleIds(filePath: string, code: string): Promise<string[]> {
  const [result] = await eslint.lintText(code, { filePath });
  return (result?.messages ?? []).map((message) => message.ruleId ?? message.message);
}

const use = (name: string) => `export const used = ${name};\n`;

describe('import boundaries (AC-2)', () => {
  it.each([
    [
      'a feature importing another feature',
      'src/features/identity/components/Probe.tsx',
      "import { BillingBanner } from '@/features/platform';\n",
      'BillingBanner',
    ],
    [
      'a surface importing another surface',
      'src/surfaces/console/Probe.tsx',
      "import { AccountPage } from '@/surfaces/workspace';\n",
      'AccountPage',
    ],
    [
      'a feature importing a surface',
      'src/features/platform/components/Probe.tsx',
      "import { ConsolePage } from '@/surfaces/console';\n",
      'ConsolePage',
    ],
    [
      'a deep import into a feature',
      'src/surfaces/workspace/Probe.tsx',
      "import { AccountScreen } from '@/features/identity/components/AccountScreen';\n",
      'AccountScreen',
    ],
    [
      'a deep import into a surface',
      'src/app/probe.ts',
      "import { AccountFrame } from '@/surfaces/workspace/AccountFrame';\n",
      'AccountFrame',
    ],
    [
      'lib importing a feature',
      'src/lib/tenancy/probe.ts',
      "import { findSalon } from '@/features/branding/server';\n",
      'findSalon',
    ],
    [
      'a shared component importing a feature',
      'src/components/shared/Probe.tsx',
      "import { AccountScreen } from '@/features/identity';\n",
      'AccountScreen',
    ],
  ])('refuses %s', async (_case, filePath, code, name) => {
    expect(await ruleIds(filePath, code + use(name))).toContain('boundaries/dependencies');
  });

  it.each([
    [
      'a surface using a feature through its entry',
      'src/surfaces/workspace/Probe.tsx',
      "import { AccountScreen } from '@/features/identity';\n",
      'AccountScreen',
    ],
    [
      'a route using a feature through its server entry',
      'src/app/probe.ts',
      "import { handlePlatform } from '@/features/platform/server';\n",
      'handlePlatform',
    ],
    [
      'a feature using its own internals',
      'src/features/identity/components/Probe.tsx',
      "import { Denied } from './Controls';\n",
      'Denied',
    ],
  ])('allows %s', async (_case, filePath, code, name) => {
    expect(await ruleIds(filePath, code + use(name))).toEqual([]);
  });
});

describe("API calls through each module's client, from server code only (AC-3)", () => {
  it.each([
    [
      'the package root',
      'src/features/identity/server/probe.ts',
      "import 'server-only';\nimport { createApiClient } from '@salon/api-contracts';\n",
    ],
    [
      "another module's client",
      'src/features/identity/server/probe.ts',
      "import 'server-only';\nimport { createApiClient } from '@salon/api-contracts/platform';\n",
    ],
    [
      'a client outside server code',
      'src/features/platform/components/Probe.tsx',
      "import { createApiClient } from '@salon/api-contracts/platform';\n",
    ],
    [
      "the shared module's client outside lib/api-core",
      'src/features/platform/server/probe.ts',
      "import 'server-only';\nimport { createApiClient } from '@salon/api-contracts/shared';\n",
    ],
  ])('refuses %s', async (_case, filePath, code) => {
    expect(await ruleIds(filePath, code + use('createApiClient'))).toContain(
      '@typescript-eslint/no-restricted-imports',
    );
  });

  it('refuses server code that does not start with the server-only guard', async () => {
    const code = "import { createApiClient } from '@salon/api-contracts/platform';\n" + use('createApiClient');
    expect(await ruleIds('src/features/platform/server/probe.ts', code)).toContain('no-restricted-syntax');
  });

  it.each([
    [
      "the module's own client in its server code",
      'src/features/identity/server/probe.ts',
      "import 'server-only';\nimport { createApiClient } from '@salon/api-contracts/identity';\n" +
        use('createApiClient'),
    ],
    [
      "a module's schema types anywhere",
      'src/features/platform/components/Probe.tsx',
      "import type { components } from '@salon/api-contracts/platform';\nexport type Plan = components['schemas']['PlanDefinition'];\n",
    ],
  ])('allows %s', async (_case, filePath, code) => {
    expect(await ruleIds(filePath, code)).toEqual([]);
  });
});

// Decision 121 (web H4): each module registers its own routes, in its own server/routes.ts.
describe('routes are registered per module', () => {
  const declare = (module: string) =>
    "import 'server-only';\nimport { moduleRoutes } from '@/lib/api-core/routes';\n" +
    `export const routes = moduleRoutes('${module}', {});\n`;

  it.each([
    ["another module's routes in a module's routes.ts", 'src/features/staff/server/routes.ts', 'catalogue'],
    ["a module's routes outside its routes.ts", 'src/features/staff/server/salon-team.ts', 'staff'],
    ["a module's routes from identity", 'src/features/identity/server/routes.ts', 'booking'],
    ["a module's routes from a surface", 'src/surfaces/workspace/routes.ts', 'staff'],
    ["a module's routes from the app", 'src/app/identity-web/[kind]/[action]/route.ts', 'staff'],
  ])('refuses %s', async (_case, filePath, module) => {
    expect(await ruleIds(filePath, declare(module))).toContain('local/own-routes');
  });

  it('allows a module its own routes in its routes.ts', async () => {
    expect(await ruleIds('src/features/staff/server/routes.ts', declare('staff'))).toEqual([]);
  });

  it("allows the app the workspace's route table, and no other file of the surface", async () => {
    const table = "import { WORKSPACE_ROUTES } from '@/surfaces/workspace/routes';\n" + use('WORKSPACE_ROUTES');
    expect(await ruleIds('src/app/_proxy/probe.ts', table)).toEqual([]);
    const screen = "import { BookingsScreen } from '@/surfaces/workspace/BookingsScreen';\n" + use('BookingsScreen');
    expect(await ruleIds('src/app/_proxy/probe.ts', screen)).toContain('boundaries/dependencies');
  });
});
