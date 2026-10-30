import { readFileSync } from 'node:fs';

import react from '@vitejs/plugin-react';
import { defineConfig, type Plugin } from 'vitest/config';

/**
 * `import source from './x.css?raw'` gives the style sheet's source, as the build does
 * (next.config.ts, turbopack.rules). Vitest otherwise replaces every style sheet with nothing.
 */
function rawStyleSheets(): Plugin {
  // A virtual id that does not end in `.css`, so that no style sheet plugin treats the source as a
  // style sheet afterwards.
  const prefix = '\0raw-style-sheet:';
  const suffix = '.js';
  return {
    name: 'raw-style-sheets',
    enforce: 'pre',
    async resolveId(source, importer) {
      const [file, query] = source.split('?');
      if (query !== 'raw' || !file?.endsWith('.css')) {
        return null;
      }
      const resolved = await this.resolve(file, importer, { skipSelf: true });
      return resolved ? `${prefix}${resolved.id}${suffix}` : null;
    },
    load(id) {
      return id.startsWith(prefix)
        ? `export default ${JSON.stringify(readFileSync(id.slice(prefix.length, -suffix.length), 'utf8'))};`
        : null;
    },
  };
}

export default defineConfig({
  plugins: [rawStyleSheets(), react()],
  // The `@/` and `@test/` aliases, from tsconfig.json.
  resolve: { tsconfigPaths: true },
  test: {
    environment: 'jsdom',
    include: ['src/**/*.test.{ts,tsx}'],
    setupFiles: ['./test/setup.ts'],
  },
});
