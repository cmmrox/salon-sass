import { readFileSync } from 'node:fs';
import { join } from 'node:path';
import { expect, it } from 'vitest';
import { contrastRatio } from './brand';
const tokens = readFileSync(join(__dirname, 'tokens.css'), 'utf8');
const token = (name: string) => new RegExp(`${name}:\\s*(#[0-9a-f]+)`, 'i').exec(tokens)![1]!;
it.each([
 ['components/status/Status.module.css', 'summary'],
 ['components/account/Account.module.css', 'success'],
])('%s pairs normal-size positive feedback with AA readable text', (file, selector) => {
 const sheet = readFileSync(join(__dirname, '..', file), 'utf8');
 const rule = new RegExp(`\\.${selector}\\s*\\{([^}]+)\\}`).exec(sheet)![1]!;
 const text = /(?:^|;)\s*color:\s*var\((--[\w-]+)\)/.exec(rule)![1]!;
 const background = /background:\s*var\((--[\w-]+)\)/.exec(rule)![1]!;
 expect(contrastRatio(token(text), token(background))).toBeGreaterThanOrEqual(4.5);
 expect(token('--color-positive')).toBe('#0F7B55');
 expect(token('--color-positive-surface')).toBe('#DDF1E6');
});
