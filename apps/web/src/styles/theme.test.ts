import { readdirSync, readFileSync, statSync } from 'node:fs';
import { join } from 'node:path';
import { describe, expect, it } from 'vitest';

/** CR-013: one salon theme, the prototype's final tokens with the salon's brand as the accent. */
const source = join(__dirname, '..');
const read = (file: string) => readFileSync(join(source, file), 'utf8');
const tokens = read('styles/tokens.css');
const rule = (sheet: string, selector: string) => {
  const escaped = selector.replace(/[.*+?^${}()|[\]\\]/g, '\\$&');
  const match = new RegExp(`(?:^|\\n|\\})\\s*${escaped}\\s*\\{([^}]+)\\}`).exec(sheet);
  expect(match, selector).not.toBeNull();
  return match![1]!;
};
const modules = (dir: string): string[] => readdirSync(dir).flatMap((name) => {
  const path = join(dir, name);
  return statSync(path).isDirectory() ? modules(path) : name.endsWith('.module.css') ? [path] : [];
});

describe('the salon theme', () => {
  it.each([
    ['--color-background', '#F3F0EB'],
    ['--color-secondary', '#EAE5DC'],
    ['--color-border', '#DDD6CA'],
    ['--color-surface', '#ffffff'],
    ['--color-text', '#111827'],
    ['--color-text-muted', '#6B7280'],
  ])('keeps the prototype\'s final %s', (name, value) => {
    expect(tokens).toContain(`${name}: ${value};`);
  });

  it('has retired the separate sign-in palette and the black accent', () => {
    expect(tokens).not.toMatch(/--color-login-/);
    expect(tokens).not.toMatch(/--color-primary\b/);
    expect(tokens).not.toMatch(/#8A705E|#141413/i);
    for (const file of modules(join(source, 'components'))) {
      expect(readFileSync(file, 'utf8'), file).not.toMatch(/--color-login-|--color-primary\b|--color-action\b/);
    }
  });

  it('no component style sheet holds a raw colour', () => {
    for (const file of modules(join(source, 'components'))) {
      expect(readFileSync(file, 'utf8'), file).not.toMatch(/#[0-9a-f]{3,8}\b|rgba?\(/i);
    }
  });

  it('draws the sign-in accents in the salon brand', () => {
    const sheet = read('components/account/StaffSignIn.module.css');
    expect(rule(sheet, '.form > button[type=submit]')).toMatch(/background: var\(--color-brand\);.*color: var\(--color-on-brand\);.*border-color: var\(--color-brand\);/);
    expect(rule(sheet, '.initial')).toContain('solid var(--color-brand)');
    expect(rule(sheet, '.recovery button')).toContain('color: var(--color-brand-text)');
    expect(rule(sheet, '.form input:focus-visible')).toContain('var(--color-brand)');
    expect(rule(sheet, '.screen')).toContain('color: var(--color-text)');
  });

  it('draws the workspace accents in the salon brand', () => {
    const account = read('components/account/Account.module.css');
    expect(rule(account, '.button')).toMatch(/border: var\(--size-border\) solid var\(--color-brand\);.*background: var\(--color-brand\); color: var\(--color-on-brand\);/);
    expect(rule(account, '.input:focus-visible')).toContain('var(--color-brand)');
    expect(rule(account, '.check input')).toContain('accent-color: var(--color-brand)');
    const navigation = read('components/shell/Navigation.module.css');
    expect(rule(navigation, '.workspace .item[aria-current]')).toContain('background: var(--color-brand); color: var(--color-on-brand);');
    expect(rule(navigation, '.item[aria-current] .indicator')).toContain('background: var(--color-brand-soft)');
  });
});
