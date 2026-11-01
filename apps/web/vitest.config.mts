import { readFileSync } from 'node:fs';
import { fileURLToPath } from 'node:url';

import react from '@vitejs/plugin-react';
import { playwright } from '@vitest/browser-playwright';
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
  resolve: {
    // The `@/` and `@test/` aliases, from tsconfig.json.
    tsconfigPaths: true,
    // Tests run server code as Next.js's server compilers do, where `server-only` is an empty module.
    alias: { 'server-only': fileURLToPath(new URL('./node_modules/server-only/empty.js', import.meta.url)) },
  },
  test: {
    projects: [
      {
        extends: true,
        test: {
          name: 'unit',
          environment: 'jsdom',
          include: ['src/**/*.test.{ts,tsx}'],
          exclude: ['src/**/*.a11y.test.{ts,tsx}'],
          setupFiles: ['./test/setup.ts'],
        },
      },
      {
        // The accessibility check (docs/engineering/web.md, "Tests"; F04-S01): each screen rendered in
        // real Chromium with the application's own compiled Tailwind CSS, so axe-core measures colour
        // contrast from the real styles, which jsdom cannot.
        extends: true,
        // These files are outside tsconfig.json (see tsconfig.a11y.json), which is where `tsconfigPaths`
        // finds the `@/` and `@test/` aliases, so they are named here as well.
        resolve: {
          alias: [
            { find: /^@\//, replacement: fileURLToPath(new URL('./src/', import.meta.url)) },
            { find: /^@test\//, replacement: fileURLToPath(new URL('./test/', import.meta.url)) },
          ],
        },
        test: {
          name: 'a11y',
          include: ['src/**/*.a11y.test.{ts,tsx}'],
          setupFiles: ['./test/a11y-setup.ts'],
          browser: {
            enabled: true,
            headless: true,
            provider: playwright(),
            instances: [{ browser: 'chromium' }],
            // Nothing is written into the source tree; a failure's axe report says what is wrong.
            screenshotFailures: false,
          },
        },
      },
    ],
  },
});
