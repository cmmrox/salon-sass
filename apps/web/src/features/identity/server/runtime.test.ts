// @vitest-environment node
import { execFileSync } from 'node:child_process';
import { mkdtempSync, rmSync, writeFileSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { join, resolve } from 'node:path';
import { afterEach, describe, expect, it } from 'vitest';
const folders: string[] = [];
afterEach(() => folders.splice(0).forEach((folder) => rmSync(folder, { recursive: true, force: true })));
function run(environment: Record<string, string>, contents?: string): { ok: boolean; message?: string; retained?: boolean } {
  const folder = mkdtempSync(join(tmpdir(), 'salon-web-key-')); folders.push(folder);
  const file = join(folder, 'keys.properties'); if (contents !== undefined) writeFileSync(file, contents, { mode: 0o600 });
  const runtimePath = resolve('scripts/identity-runtime.mjs');
  const snippet = `import { loadIdentityRuntime } from ${JSON.stringify(runtimePath)}; const env=${JSON.stringify(environment)}; try { loadIdentityRuntime(env,${JSON.stringify(file)}); console.log(JSON.stringify({ok:true,retained:env.SALON_IDENTITY_PROXY_KEY===${JSON.stringify('ab'.repeat(32))}})); } catch(e) { console.log(JSON.stringify({ok:false,message:e.message})); }`;
  return JSON.parse(execFileSync(process.execPath, ['--input-type=module', '-e', snippet], { encoding: 'utf8' }));
}
describe('nonbundled local launcher key loading', () => {
  it('loads the retained proxy value only for explicit local HTTP', () => { expect(run({ SALON_WEB_LOCAL_HTTP: 'true' }, `signing=unchanged\nproxy=${'ab'.repeat(32)}\n`)).toEqual({ ok: true, retained: true }); });
  it('never uses a local file outside local HTTP', () => { expect(run({ SALON_WEB_LOCAL_HTTP: 'false' }, `proxy=${'ab'.repeat(32)}\n`)).toMatchObject({ ok: false }); });
  it('requires a valid explicit nonlocal key', () => { expect(run({ SALON_WEB_LOCAL_HTTP: 'false', SALON_IDENTITY_PROXY_KEY: 'ab'.repeat(32) })).toEqual({ ok: true, retained: true }); });
  it.each([undefined, 'proxy=not-a-key\n', `proxy=${'ab'.repeat(32)}\nproxy=${'ab'.repeat(32)}\n`])('fails safely for missing, invalid or duplicate retained values', (saved) => { const r = run({ SALON_WEB_LOCAL_HTTP: 'true' }, saved); expect(r.ok).toBe(false); expect(r.message).not.toContain('ab'.repeat(32)); });
  it('does not mask an invalid explicit key with file fallback', () => { expect(run({ SALON_WEB_LOCAL_HTTP: 'true', SALON_IDENTITY_PROXY_KEY: 'invalid' }, `proxy=${'ab'.repeat(32)}\n`)).toMatchObject({ ok: false }); });
});
