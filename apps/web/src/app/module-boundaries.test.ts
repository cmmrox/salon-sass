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
