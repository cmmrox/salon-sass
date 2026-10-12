import { defineConfig, globalIgnores } from 'eslint/config';
import nextVitals from 'eslint-config-next/core-web-vitals';
import nextTs from 'eslint-config-next/typescript';

// Attributes whose value a person sees or hears. Their text comes from the translation files too.
const SPOKEN_ATTRIBUTES = 'aria-label|aria-description|aria-placeholder|aria-roledescription|aria-valuetext|alt|label|placeholder|title';

const SURFACES = ['(booking)', '(workspace)', '(console)'];

const eslintConfig = defineConfig([
  ...nextVitals,
  ...nextTs,
  globalIgnores(['.next/**', 'out/**', 'build/**', 'coverage/**', 'next-env.d.ts']),
  {
    files: ['src/**/*.{ts,tsx}'],
    ignores: ['src/**/*.test.{ts,tsx}', 'src/test/**'],
    rules: {
      // Every string a person sees comes from the translation files (docs/engineering/web.md).
      'react/jsx-no-literals': ['error', { noStrings: true, ignoreProps: true }],
      'no-restricted-syntax': [
        'error',
        {
          selector: `JSXAttribute[name.name=/^(${SPOKEN_ATTRIBUTES})$/] > Literal`,
          message: 'Text a person sees or hears comes from the translation files, not from a literal.',
        },
        {
          selector: `JSXAttribute[name.name=/^(${SPOKEN_ATTRIBUTES})$/] > JSXExpressionContainer > :matches(Literal, TemplateLiteral)`,
          message: 'Text a person sees or hears comes from the translation files, not from a literal.',
        },
      ],
      // A surface never imports from another surface; what two surfaces share lives outside them.
      'import/no-restricted-paths': [
        'error',
        {
          zones: SURFACES.map((surface) => ({
            target: `./src/app/${surface}`,
            from: SURFACES.filter((other) => other !== surface).map((other) => `./src/app/${other}`),
            message: 'A surface never imports from another surface. Move what they share to src/components or src/lib.',
          })),
        },
      ],
    },
  },
]);

export default eslintConfig;
