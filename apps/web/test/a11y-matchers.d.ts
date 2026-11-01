import type { AxeMatchers } from 'vitest-axe';

// vitest-axe declares its matcher on the retired global `Vi` namespace; this puts it on Vitest's own
// Assertion, so `expect(results).toHaveNoViolations()` type-checks.
declare module 'vitest' {
  // The type parameter must repeat Vitest's own declaration exactly, `any` included.
  // eslint-disable-next-line @typescript-eslint/no-explicit-any, @typescript-eslint/no-empty-object-type, @typescript-eslint/no-unused-vars
  interface Assertion<T = any> extends AxeMatchers {}
  // eslint-disable-next-line @typescript-eslint/no-empty-object-type
  interface AsymmetricMatchersContaining extends AxeMatchers {}
}
