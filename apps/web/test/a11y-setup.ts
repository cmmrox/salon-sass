// The accessibility check's browser: the application's one style sheet (Tailwind, the theme, the base
// layer), compiled by Vite through the app's PostCSS configuration exactly as Next.js compiles it, and
// vitest-axe's matcher.
import '../src/app/globals.css';
import { cleanup } from '@testing-library/react';
import { afterEach, expect } from 'vitest';
import * as matchers from 'vitest-axe/matchers';

expect.extend(matchers);

// Testing Library unmounts after each test only where test globals are on; here it is said outright.
afterEach(() => {
  cleanup();
});
