import { readdirSync, readFileSync, statSync } from 'node:fs';
import { join } from 'node:path';
import { describe, expect, it } from 'vitest';
import { declarations, globalSheet, tailwindNames, themeSheet, themeValue } from '@/test/theme';

/** CR-013 and CR-016: one salon theme, the prototype's final tokens under shadcn/ui's names, with
 * the salon's brand as the accent. */
const source = join(__dirname, '..');
const read = (file: string) => readFileSync(join(source, file), 'utf8');
const modules = (dir: string): string[] => readdirSync(dir).flatMap((name) => {
  const path = join(dir, name);
  return statSync(path).isDirectory() ? modules(path) : name.endsWith('.module.css') ? [path] : [];
});

describe('the salon theme', () => {
  it.each([
    ['--background', '#F3F0EB'],
    ['--foreground', '#111827'],
    ['--card', '#ffffff'],
    ['--card-foreground', '#111827'],
    ['--popover', '#ffffff'],
    ['--secondary', '#EAE5DC'],
    ['--muted', '#EAE5DC'],
    ['--muted-foreground', '#6B7280'],
    ['--border', '#DDD6CA'],
    ['--input', '#DDD6CA'],
    ['--destructive', '#B3261E'],
    ['--ring', '#1D5FD0'],
    ['--radius', '1.25rem'],
    ['--radius-medium', '1.5rem'],
    ['--radius-pill', '999px'],
  ])('keeps the prototype\'s final %s under its shadcn/ui name', (name, value) => {
    expect(themeValue(name)).toBe(value);
  });

  it('takes the accent from the salon brand that BrandStyle sets, and holds no brand colour itself', () => {
    const theme = declarations(themeSheet);
    expect(theme.get('--primary')).toBe('var(--color-brand)');
    expect(theme.get('--primary-foreground')).toBe('var(--color-on-brand)');
    expect(theme.get('--accent')).toBe('var(--color-brand-soft)');
    expect([...theme.keys()].filter((name) => name.startsWith('--color-brand') || name === '--color-on-brand')).toEqual([]);
    expect(themeSheet).not.toMatch(/#D6086A/i);
  });

  it('maps every theme colour to its Tailwind name in globals.css', () => {
    const names = tailwindNames();
    for (const name of ['background', 'foreground', 'card', 'primary', 'primary-foreground', 'secondary', 'muted', 'muted-foreground', 'accent', 'accent-foreground', 'destructive', 'border', 'input', 'ring', 'positive', 'positive-surface', 'caution', 'caution-surface']) {
      expect(names.get(`--color-${name}`), name).toBe(`var(--${name})`);
    }
    expect(names.get('--font-sans')).toBe('var(--font-family)');
    expect(themeValue('--font-family')).toMatch(/^'Poppins'/);
  });

  it('holds globals.css in the order the styling rules give, with no dark mode', () => {
    const order = ["@import 'tailwindcss'", "@import 'tw-animate-css'", "@import '../styles/theme.css'", '@theme inline', '@layer base'].map((part) => globalSheet.indexOf(part));
    expect(order.every((index) => index >= 0)).toBe(true);
    expect([...order].sort((a, b) => a - b)).toEqual(order);
    expect(globalSheet).toContain('@custom-variant dark (&:is(.dark *));');
    expect(themeSheet.replace(/\/\*[\s\S]*?\*\//g, '')).not.toMatch(/@import|@theme|@layer|@apply|@tailwind/);
  });

  it('gives no font size the name of a colour, so a text-* utility is never ambiguous', () => {
    const names = tailwindNames();
    const sizes = [...names.keys()].filter((name) => name.startsWith('--text-')).map((name) => name.slice('--text-'.length));
    const colours = new Set([...names.keys()].filter((name) => name.startsWith('--color-')).map((name) => name.slice('--color-'.length)));
    expect(sizes.filter((size) => colours.has(size))).toEqual([]);
  });

  it('loads the three Poppins weights the prototype uses', () => {
    for (const [weight, file] of [['400', 'Regular'], ['500', 'Medium'], ['600', 'SemiBold']]) {
      expect(themeSheet).toContain(`font-weight: ${weight}; font-display: swap; src: url('/fonts/poppins/Poppins-${file}.ttf')`);
    }
  });

  it('has retired the separate sign-in palette, the black accent and every CSS Module', () => {
    expect(themeSheet).not.toMatch(/--color-login-/);
    expect(themeSheet).not.toMatch(/--color-primary\b/);
    expect(themeSheet).not.toMatch(/#8A705E|#141413/i);
    expect(modules(source)).toEqual([]);
  });

  it('keeps no former token name beside the shadcn/ui ones', () => {
    for (const name of ['--color-text', '--color-surface', '--color-background', '--color-border', '--space-4', '--font-weight-strong']) {
      expect(declarations(themeSheet).has(name), name).toBe(name === '--font-weight-strong');
    }
  });

  it('draws the sign-in accents in the salon brand (CR-012, CR-013)', () => {
    const signIn = read('features/identity/components/SignIn.tsx');
    const frame = read('surfaces/workspace/StaffSignInFrame.tsx');
    const button = read('components/ui/button.tsx');
    // The sign-in action is the default (brand) action; the recovery control is the brand link.
    expect(signIn).toMatch(/testId="account-sign-in-submit"(?![^>]*secondary)/);
    expect(button).toContain('default: "border-primary bg-primary text-primary-foreground"');
    expect(signIn).toMatch(/variant="link"[^>]*data-testid="account-forgot-password"/);
    expect(button).toMatch(/link: "[^"]*text-link[^"]*decoration-primary/);
    expect(declarations(themeSheet).get('--link')).toBe('var(--color-brand-text)');
    expect(tailwindNames().get('--color-link')).toBe('var(--link)');
    expect(frame).toMatch(/data-testid="staff-sign-in-initial"/);
    expect(frame).toContain('border-2 border-primary');
    expect(frame).toMatch(/data-testid="staff-sign-in-screen"/);
    expect(frame).toContain('text-foreground');
  });

  it('draws the fields\' focus and the checked box in the salon brand, and refusal in the negative colour', () => {
    expect(read('components/ui/input.tsx')).toContain('focus-visible:shadow-field-focus aria-invalid:shadow-field-invalid');
    expect(declarations(themeSheet).get('--field-ring-focus')).toBe('inset 0 0 0 var(--size-focus-offset) var(--color-brand)');
    expect(declarations(themeSheet).get('--field-ring-invalid')).toBe('inset 0 0 0 var(--size-field-inset) var(--destructive)');
    expect(declarations(themeSheet).get('--field-ring')).toBe('inset 0 0 0 var(--size-field-inset) var(--border)');
    expect(read('components/ui/checkbox.tsx')).toContain('data-[state=checked]:border-primary data-[state=checked]:bg-primary data-[state=checked]:text-primary-foreground');
  });

  it('draws the workspace navigation\'s current route in the salon brand', () => {
    // The Sidebar (CR-017): the current route's menu button is active, and an active button is
    // filled with the sidebar's primary colours, which are the salon's brand and its readable colour.
    expect(read('components/shared/Navigation.tsx')).toContain('isActive={Boolean(item.current)}');
    expect(read('components/ui/sidebar.tsx')).toContain('data-[active=true]:bg-sidebar-primary data-[active=true]:text-sidebar-primary-foreground');
    expect(tailwindNames().get('--color-sidebar-primary')).toBe('var(--sidebar-primary)');
    expect(tailwindNames().get('--color-sidebar-primary-foreground')).toBe('var(--sidebar-primary-foreground)');
    expect(declarations(themeSheet).get('--sidebar-primary')).toBe('var(--color-brand)');
    expect(declarations(themeSheet).get('--sidebar-primary-foreground')).toBe('var(--color-on-brand)');
    expect(read('components/ui/button.tsx')).toContain('border-primary bg-primary text-primary-foreground');
  });
});
