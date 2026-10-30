import { readFileSync } from 'node:fs';
import { join } from 'node:path';

/** The theme's sources, for tests that check its values (docs/engineering/web.md, "Styling"). */
const source = join(__dirname, '..', 'src');
export const themeSheet = readFileSync(join(source, 'styles', 'theme.css'), 'utf8');
export const globalSheet = readFileSync(join(source, 'app', 'globals.css'), 'utf8');

/** Every `--name: value;` of a style sheet, comments removed; the last one of a name wins. */
export function declarations(sheet: string): Map<string, string> {
  const result = new Map<string, string>();
  for (const [, name, value] of sheet.replace(/\/\*[\s\S]*?\*\//g, '').matchAll(/(--[\w-]+)\s*:\s*([^;]+);/g)) {
    result.set(name!, value!.trim());
  }
  return result;
}

/** The `@theme inline` block of globals.css: Tailwind's names and the theme variables they read. */
export function tailwindNames(): Map<string, string> {
  const block = /@theme inline\s*\{([\s\S]*?)\n\}/.exec(globalSheet);
  if (!block) {
    throw new Error('globals.css has no @theme inline block');
  }
  return declarations(block[1]!);
}

/** A theme variable's value, following `var(--other)` through theme.css. */
export function themeValue(name: string): string {
  const theme = declarations(themeSheet);
  let value = theme.get(name);
  for (let hops = 0; value !== undefined && hops < 10; hops++) {
    const reference = /^var\((--[\w-]+)\)$/.exec(value);
    if (!reference) {
      return value;
    }
    value = theme.get(reference[1]!);
  }
  throw new Error(`theme.css does not resolve ${name}`);
}

/** The colour a Tailwind colour utility draws, `bg-positive-surface` -> `#DDF1E6`. */
export function utilityColour(className: string, prefix: 'bg' | 'text'): string {
  const name = className.slice(prefix.length + 1);
  const mapped = tailwindNames().get(`--color-${name}`);
  const reference = mapped && /^var\((--[\w-]+)\)$/.exec(mapped);
  if (!reference) {
    throw new Error(`no theme colour for ${className}`);
  }
  return themeValue(reference[1]!);
}
