import type { NextConfig } from 'next';
import createNextIntlPlugin from 'next-intl/plugin';

const withNextIntl = createNextIntlPlugin('./src/i18n/request.ts');

const nextConfig: NextConfig = {
  // The framework's defaults for a new application: data is fetched at request time unless a
  // component says it may be cached, and a static shell is served while it streams in.
  cacheComponents: true,
  partialPrefetching: true,
  // AGENTS.md here is the lead's file; `next dev` must not write to it.
  agentRules: false,
  // The generated API client is a workspace package shipped as TypeScript source.
  transpilePackages: ['@salon/api-contracts'],
  poweredByHeader: false,
  turbopack: {
    rules: {
      // `import text from './x.css?raw'` gives a style sheet's source as a string, for the plain
      // HTML answers of proxy.ts, which inline the same style sheets the pages use.
      // Plain style sheets only: Turbopack cannot give a CSS module's source as text.
      '*.css': { condition: { query: '?raw' }, type: 'text' },
    },
  },
};

export default withNextIntl(nextConfig);
