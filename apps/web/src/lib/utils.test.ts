import { readFileSync } from 'node:fs';
import { join } from 'node:path';
import { describe, expect, it } from 'vitest';
import { cn } from './utils';

describe('cn', () => {
  it('reads the theme\'s font sizes as sizes, so a colour beside one never removes it', () => {
    expect(cn('text-field font-normal text-foreground')).toBe('text-field font-normal text-foreground');
    expect(cn('text-label text-caution', 'text-body')).toBe('text-caution text-body');
  });

  it('lets a later theme utility of the same kind replace an earlier one', () => {
    expect(cn('rounded-card', 'rounded-field')).toBe('rounded-field');
    expect(cn('min-h-field', 'min-h-action')).toBe('min-h-action');
    expect(cn('shadow-field', 'shadow-popover')).toBe('shadow-popover');
    expect(cn('leading-heading', 'leading-body')).toBe('leading-body');
    expect(cn('max-w-content', 'max-w-workspace')).toBe('max-w-workspace');
    expect(cn('rail:p-4', 'rail:p-6')).toBe('rail:p-6');
  });

  it('knows every font size, radius and shadow name that globals.css maps', () => {
    const sheet = readFileSync(join(__dirname, '..', 'app', 'globals.css'), 'utf8');
    const names = (prefix: string) => [...sheet.matchAll(new RegExp(`--${prefix}-([a-z-]+):`, 'g'))].map((match) => match[1]!);
    for (const name of names('text')) expect(cn(`text-${name}`, 'text-foreground'), name).toBe(`text-${name} text-foreground`);
    for (const name of names('radius').filter((n) => !['sm', 'md', 'lg', 'xl'].includes(n))) expect(cn(`rounded-${name}`, 'rounded-none'), name).toBe('rounded-none');
    for (const name of names('shadow')) expect(cn(`shadow-${name}`, 'shadow-none'), name).toBe('shadow-none');
  });
});
