// @vitest-environment node
import { execFileSync } from 'node:child_process';
import { mkdtempSync, rmSync, writeFileSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { join, resolve } from 'node:path';
import { afterEach, describe, expect, it } from 'vitest';
const folders: string[] = [];
afterEach(() => folders.splice(0).forEach((folder) => rmSync(folder, { recursive: true, force: true })));
function run(
  environment: Record<string, string>,
  contents?: string,
): { ok: boolean; message?: string; retained?: boolean } {
  const folder = mkdtempSync(join(tmpdir(), 'salon-operator-key-'));
  folders.push(folder);
  const file = join(folder, 'operator-keys.properties');
  if (contents !== undefined) writeFileSync(file, contents, { mode: 0o600 });
  const runtimePath = resolve('scripts/identity-runtime.mjs');
  const snippet = `import { loadOperatorRuntime } from ${JSON.stringify(runtimePath)}; const env=${JSON.stringify(environment)}; try { loadOperatorRuntime(env,${JSON.stringify(file)}); console.log(JSON.stringify({ok:true,retained:env.SALON_OPERATOR_PROXY_KEY===${JSON.stringify('cd'.repeat(32))}})); } catch(e) { console.log(JSON.stringify({ok:false,message:e.message})); }`;
  return JSON.parse(execFileSync(process.execPath, ['--input-type=module', '-e', snippet], { encoding: 'utf8' }));
}
describe("the local launcher's operator proxy key", () => {
  it('loads the retained value only for explicit local HTTP', () =>
    expect(run({ SALON_WEB_LOCAL_HTTP: 'true' }, `signing=x\nproxy=${'cd'.repeat(32)}\n`)).toEqual({
      ok: true,
      retained: true,
    }));
  it('never reads the file outside local HTTP', () =>
    expect(run({ SALON_WEB_LOCAL_HTTP: 'false' }, `proxy=${'cd'.repeat(32)}\n`)).toMatchObject({ ok: false }));
  it('accepts an explicit configured key', () =>
    expect(run({ SALON_WEB_LOCAL_HTTP: 'false', SALON_OPERATOR_PROXY_KEY: 'cd'.repeat(32) })).toEqual({
      ok: true,
      retained: true,
    }));
  it('refuses the identity proxy key reused for the console', () =>
    expect(
      run({
        SALON_WEB_LOCAL_HTTP: 'false',
        SALON_OPERATOR_PROXY_KEY: 'cd'.repeat(32),
        SALON_IDENTITY_PROXY_KEY: 'CD'.repeat(32),
      }),
    ).toMatchObject({ ok: false }));
  it.each([undefined, 'proxy=bad\n', `proxy=${'cd'.repeat(32)}\nproxy=${'cd'.repeat(32)}\n`])(
    'fails without printing for missing, invalid or duplicate values',
    (saved) => {
      const r = run({ SALON_WEB_LOCAL_HTTP: 'true' }, saved);
      expect(r.ok).toBe(false);
      expect(r.message).not.toContain('cd'.repeat(32));
    },
  );
});
