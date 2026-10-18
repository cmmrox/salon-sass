// @vitest-environment node
import { mkdirSync, mkdtempSync, rmSync, writeFileSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { afterEach, describe, expect, it } from 'vitest';
import { knownKeyValues, scanArtifacts } from '../../../scripts/scan-artifacts.mjs';

const folders: string[] = [];
afterEach(() => folders.splice(0).forEach((folder) => rmSync(folder, { recursive: true, force: true })));
function build(files: Record<string, string>): string {
  const root = mkdtempSync(join(tmpdir(), 'salon-artifacts-')); folders.push(root);
  for (const [path, content] of Object.entries(files)) { mkdirSync(join(root, path, '..'), { recursive: true }); writeFileSync(join(root, path), content); }
  return root;
}
const KEY = 'cd'.repeat(32);

describe('the built-artifact scan (F03-S01 AC-20)', () => {
  it('passes a clean build', () => {
    expect(scanArtifacts([build({ 'server.js': 'console.log(1)', 'chunks/a.js': 'x' })], [KEY])).toEqual([]);
  });
  it.each(['.env', '.env.local', 'nested/.env.production', 'identity-keys.properties', 'deep/operator-keys.properties', 'billing-keys.properties'])('fails on a copied %s', (file) => {
    const root = build({ 'server.js': 'ok', [file]: 'anything' });
    expect(scanArtifacts([root], [])).toEqual([join(root, file)]);
  });
  it('fails on any file holding a known key value, in any letter case', () => {
    const root = build({ 'server.js': 'ok', 'chunks/leak.js': `const k="${KEY.toUpperCase()}"` });
    expect(scanArtifacts([root], [KEY])).toEqual([join(root, 'chunks/leak.js')]);
  });
  it('knows configured and retained key values, and only 64-hex ones', () => {
    const root = build({ 'operator-keys.properties': `signing=${'ef'.repeat(32)}\nproxy=${KEY}\nbad=xyz\n` });
    const values = knownKeyValues({ SALON_IDENTITY_PROXY_KEY: 'ab'.repeat(32), SALON_OPERATOR_SIGNING_KEY: 'short' }, [join(root, 'operator-keys.properties'), join(root, 'missing')]);
    expect(values.sort()).toEqual(['ab'.repeat(32), KEY, 'ef'.repeat(32)].sort());
  });
  it('knows the billing provider key, configured or retained (F03-S02)', () => {
    const root = build({ 'billing-keys.properties': `provider=${'12'.repeat(32)}\n` });
    expect(knownKeyValues({ SALON_BILLING_PROVIDER_KEY: '34'.repeat(32) }, [join(root, 'billing-keys.properties')]).sort()).toEqual(['12'.repeat(32), '34'.repeat(32)]);
  });
});
