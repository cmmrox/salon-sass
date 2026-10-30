import '@testing-library/jest-dom/vitest';

import { cleanup } from '@testing-library/react';
import { afterEach } from 'vitest';

import { installMatchMedia, setViewportWidth } from './viewport';

// jsdom has no matchMedia; the Sidebar asks it whether the viewport is below the rail breakpoint.
if (typeof window !== 'undefined') {
  installMatchMedia();
  // jsdom lacks these layout and pointer APIs: Radix Select scrolls its chosen option into view and
  // checks pointer capture; input-otp asks which element sits at a point.
  Element.prototype.scrollIntoView ??= () => {};
  Element.prototype.hasPointerCapture ??= () => false;
  Element.prototype.releasePointerCapture ??= () => {};
  document.elementFromPoint ??= () => null;
}

afterEach(() => {
  cleanup();
  if (typeof window !== 'undefined') setViewportWidth(1024);
});
