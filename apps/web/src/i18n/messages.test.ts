import { readdirSync, readFileSync } from 'node:fs';
import { join } from 'node:path';

import { describe, expect, it } from 'vitest';

import { locales } from './config';

const directory = join(__dirname, '..', '..', 'messages');

function keysOf(value: unknown, prefix = ''): string[] {
  if (typeof value !== 'object' || value === null) {
    return [prefix];
  }
  return Object.entries(value).flatMap(([key, child]) => keysOf(child, prefix ? `${prefix}.${key}` : key));
}

function read(locale: string): Record<string, unknown> {
  return JSON.parse(readFileSync(join(directory, `${locale}.json`), 'utf8'));
}

function valueAt(messages: Record<string, unknown>, key: string): unknown {
  return key.split('.').reduce<unknown>((node, part) => (node as Record<string, unknown>)[part], messages);
}

describe('translation files', () => {
  it('has exactly one file per language the app lists', () => {
    expect(readdirSync(directory).sort()).toEqual(locales.map((locale) => `${locale}.json`).sort());
  });

  it.each(locales)('%s: every message is a non-empty string', (locale) => {
    const messages = read(locale);

    for (const key of keysOf(messages)) {
      const value = valueAt(messages, key);
      expect(typeof value, key).toBe('string');
      expect((value as string).trim(), key).not.toBe('');
    }
  });

  it.each(locales)('%s: has the same keys as English', (locale) => {
    expect(keysOf(read(locale)).sort()).toEqual(keysOf(read('en')).sort());
  });
});
