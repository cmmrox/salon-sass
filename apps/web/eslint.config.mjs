import { readFileSync } from 'node:fs';

import { defineConfig, globalIgnores } from 'eslint/config';
import nextVitals from 'eslint-config-next/core-web-vitals';
import nextTs from 'eslint-config-next/typescript';
import boundaries from 'eslint-plugin-boundaries';

// Attributes whose value a person sees or hears. Their text comes from the translation files too.
const SPOKEN_ATTRIBUTES =
  'aria-label|aria-description|aria-placeholder|aria-roledescription|aria-valuetext|alt|label|placeholder|title';

// Every string a person sees comes from the translation files (docs/engineering/web.md).
const SPOKEN_LITERALS = [
  {
    selector: `JSXAttribute[name.name=/^(${SPOKEN_ATTRIBUTES})$/] > Literal`,
    message: 'Text a person sees or hears comes from the translation files, not from a literal.',
  },
  {
    selector: `JSXAttribute[name.name=/^(${SPOKEN_ATTRIBUTES})$/] > JSXExpressionContainer > :matches(Literal, TemplateLiteral)`,
    message: 'Text a person sees or hears comes from the translation files, not from a literal.',
  },
];

// docs/engineering/web.md, "Styling", rules 1 and 4 (CR-016, CR-017): a control, table or dialog
// is the shadcn/ui component from components/ui, never the raw element, and never Native Select;
// no CSS Module. components/ui is where the raw elements live, so it alone may write them.
const RAW_ELEMENTS = 'button|input|select|textarea|table|dialog';
const SHADCN_ONLY = [
  {
    selector: `JSXOpeningElement[name.name=/^(${RAW_ELEMENTS})$/]`,
    message:
      'Use the shadcn/ui component from components/ui, not the raw element (docs/engineering/web.md, "Styling").',
  },
  {
    selector: 'JSXOpeningElement[name.name=/^NativeSelect/]',
    message: 'Use the shadcn/ui Select, never Native Select (CR-017).',
  },
];
const NO_CSS_MODULES = [
  {
    selector:
      ':matches(ImportDeclaration, ImportExpression, ExportAllDeclaration, ExportNamedDeclaration)[source.value=/\\.module\\.(css|scss|sass|less)(\\?.*)?$/]',
    message: 'No CSS Modules: build it from shadcn/ui components and Tailwind utilities (CR-016).',
  },
  {
    selector: ':matches(ImportDeclaration, ImportExpression)[source.value=/native-select$/]',
    message: 'Use the shadcn/ui Select, never Native Select (CR-017).',
  },
];
// Rule 4: no `style` prop for a visual value, except a value that exists only at run time and that
// no utility can name. STYLE_PROP_ALLOWED lists the files that set one, and why; nothing else may.
const NO_STYLE_PROP = [
  {
    selector: 'JSXAttribute[name.name="style"]',
    message: 'No style prop: use Tailwind utilities on the theme (docs/engineering/web.md, "Styling", rule 4).',
  },
];
const STYLE_PROP_ALLOWED = [
  // The salon's own brand colour as it is typed, shown in its swatch and in each preset swatch.
  'src/features/branding/components/ColourField.tsx',
  // The draft brand's colours (brandPreviewStyle) and its uploaded cover image, previewed before publishing.
  'src/features/branding/components/BrandPreview.tsx',
  // The uploaded logo or cover image, previewed in its field.
  'src/features/branding/components/ImageField.tsx',
];

// The API's modules, as the generated client exports them (`@salon/api-contracts/<module>`).
const CONTRACTS = '@salon/api-contracts';
const API_MODULES = Object.keys(
  JSON.parse(readFileSync(new URL('../../packages/api-contracts/package.json', import.meta.url), 'utf8')).exports,
)
  .filter((entry) => entry !== '.')
  .map((entry) => entry.slice(2));

/**
 * Only `allowed` modules' clients may be created here; any module's schema types may be read anywhere.
 * The package root is not used: a call names its module (docs/engineering/web.md, "Structure").
 */
function contractImports(allowed = []) {
  return [
    'error',
    {
      paths: [{ name: CONTRACTS, message: `Import one module's contract: ${CONTRACTS}/<module>.` }],
      patterns: API_MODULES.filter((module) => !allowed.includes(module)).map((module) => ({
        group: [`${CONTRACTS}/${module}`],
        allowTypeImports: true,
        message:
          module === 'shared'
            ? 'The shared module (the API clock) is reached through src/lib/api-core.'
            : `Calls to the ${module} module are made in src/features/${module}/server; import its types only.`,
      })),
    },
  ];
}

/** Where each kind of code lives (docs/engineering/web.md, "Structure"). */
const ELEMENTS = [
  { type: 'app', pattern: 'src/app', partialMatch: false },
  { type: 'surface', pattern: 'src/surfaces/*', capture: ['surface'], partialMatch: false },
  { type: 'feature', pattern: 'src/features/*', capture: ['module'], partialMatch: false },
  { type: 'ui', pattern: 'src/components/ui', partialMatch: false },
  { type: 'shared-component', pattern: 'src/components/shared', partialMatch: false },
  { type: 'lib', pattern: ['src/lib', 'src/hooks', 'src/i18n', 'src/styles'], partialMatch: false },
  { type: 'test-support', pattern: 'test', partialMatch: false },
];

const element = (type, extra = {}) => ({ to: { element: { type, ...extra } } });
const CROSS_CUTTING = ['shared-component', 'ui', 'lib'].map((type) => element(type));
// Another folder is reached through its entries only: a feature's index.ts and server.ts, a surface's index.ts and
// its route table, routes.ts (decision 121), which the proxy and the BFF route read without the surface's screens.
const FEATURE_ENTRIES = element('feature', { fileInternalPath: ['index.ts', 'server.ts'] });
const SURFACE_ENTRY = element('surface', { fileInternalPath: ['index.ts', 'routes.ts'] });

/**
 * Routes are registered per module (decision 121, docs/engineering/web.md, "Structure"): `moduleRoutes('<module>', …)`
 * is called only in that module's own `src/features/<module>/server/routes.ts`, so no module declares another's pages,
 * actions or permissions, and nothing outside the modules declares any.
 */
const ownRoutes = {
  meta: {
    type: 'problem',
    messages: {
      elsewhere: "A module's routes are declared in its own src/features/<module>/server/routes.ts.",
      another: "The {{module}} module's routes are declared by the {{module}} module, not here.",
    },
  },
  create(context) {
    const owner = /\/src\/features\/([^/]+)\/server\/routes\.ts$/.exec(context.filename.replaceAll('\\', '/'))?.[1];
    return {
      CallExpression(node) {
        if (node.callee.type !== 'Identifier' || node.callee.name !== 'moduleRoutes') return;
        if (!owner) return context.report({ node, messageId: 'elsewhere' });
        const [first] = node.arguments;
        if (first?.type !== 'Literal' || first.value !== owner)
          context.report({
            node,
            messageId: 'another',
            data: { module: first?.type === 'Literal' ? String(first.value) : 'another' },
          });
      },
    };
  },
};

/**
 * Imports point one way: app -> surfaces -> features -> components and lib. A feature never imports
 * another feature and a surface never imports another surface; the folder above composes them.
 */
const IMPORT_POLICIES = [
  { from: { element: { type: 'app' } }, allow: [element('app'), SURFACE_ENTRY, FEATURE_ENTRIES, ...CROSS_CUTTING] },
  {
    from: { element: { type: 'surface' } },
    allow: [
      element('surface', { captured: { surface: '{{ from.element.captured.surface }}' } }),
      FEATURE_ENTRIES,
      ...CROSS_CUTTING,
    ],
  },
  {
    from: { element: { type: 'feature' } },
    allow: [element('feature', { captured: { module: '{{ from.element.captured.module }}' } }), ...CROSS_CUTTING],
  },
  { from: { element: { type: 'shared-component' } }, allow: CROSS_CUTTING },
  { from: { element: { type: 'ui' } }, allow: [element('ui'), element('lib')] },
  { from: { element: { type: 'lib' } }, allow: [element('lib')] },
  // The test helpers render any part of the application; only tests import them.
  { from: { element: { type: 'test-support' } }, allow: [{ to: { element: { type: '*' } } }] },
  { from: { file: { categories: 'test' } }, allow: [element('test-support')] },
];

const eslintConfig = defineConfig([
  ...nextVitals,
  ...nextTs,
  globalIgnores(['.next/**', '.next-qa/**', 'out/**', 'build/**', 'coverage/**', 'next-env.d.ts']),
  {
    files: ['src/**/*.{ts,tsx}', 'test/**/*.{ts,tsx}'],
    plugins: { boundaries },
    settings: {
      'boundaries/elements': ELEMENTS,
      'boundaries/files': [{ category: 'test', pattern: ['src/**/*.test.{ts,tsx}', 'test/**'] }],
    },
    rules: {
      'boundaries/dependencies': ['error', { default: 'disallow', policies: IMPORT_POLICIES }],
    },
  },
  {
    files: ['src/**/*.{ts,tsx}'],
    ignores: ['src/**/*.test.{ts,tsx}'],
    plugins: { local: { rules: { 'own-routes': ownRoutes } } },
    rules: { 'local/own-routes': 'error' },
  },
  {
    files: ['src/**/*.{ts,tsx}'],
    ignores: ['src/**/*.test.{ts,tsx}'],
    rules: {
      'react/jsx-no-literals': ['error', { noStrings: true, ignoreProps: true }],
      'no-restricted-syntax': ['error', ...SPOKEN_LITERALS, ...SHADCN_ONLY, ...NO_CSS_MODULES, ...NO_STYLE_PROP],
      '@typescript-eslint/no-restricted-imports': contractImports(),
    },
  },
  {
    // The shadcn/ui components themselves are built from the raw elements.
    files: ['src/components/ui/**/*.{ts,tsx}'],
    ignores: ['src/**/*.test.{ts,tsx}'],
    rules: { 'no-restricted-syntax': ['error', ...SPOKEN_LITERALS, ...NO_CSS_MODULES, ...NO_STYLE_PROP] },
  },
  {
    files: STYLE_PROP_ALLOWED,
    rules: { 'no-restricted-syntax': ['error', ...SPOKEN_LITERALS, ...SHADCN_ONLY, ...NO_CSS_MODULES] },
  },
  ...API_MODULES.filter((module) => module !== 'shared').map((module) => ({
    files: [`src/features/${module}/server/**/*.ts`],
    ignores: ['src/**/*.test.ts'],
    rules: { '@typescript-eslint/no-restricted-imports': contractImports([module]) },
  })),
  {
    files: ['src/lib/api-core/**/*.ts'],
    ignores: ['src/**/*.test.ts'],
    rules: { '@typescript-eslint/no-restricted-imports': contractImports(['shared']) },
  },
  {
    // Server code never reaches a browser bundle: Next.js fails the build when a client module imports it.
    files: ['src/features/*/server.ts', 'src/features/*/server/**/*.ts'],
    ignores: ['src/**/*.test.ts'],
    rules: {
      'no-restricted-syntax': [
        'error',
        ...SPOKEN_LITERALS,
        ...NO_CSS_MODULES,
        {
          selector: 'Program > :first-child:not(ImportDeclaration[source.value="server-only"])',
          message: "Server code starts with import 'server-only'.",
        },
      ],
    },
  },
]);

export default eslintConfig;
